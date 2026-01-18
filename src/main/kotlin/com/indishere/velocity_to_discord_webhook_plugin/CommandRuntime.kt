/* Command Runtime @ Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin


import kotlin.io.path.div
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

import org.slf4j.Logger
import net.kyori.adventure.text.Component
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.command.CommandSource


class CommandRuntime(
    private val server: ProxyServer,
    private val logger: Logger,
    private val plugin: InitHQ,
    private val dataDirectory: Path,
    private val webhookClient: DiscordWebhook,
    private val getConfig: () -> PluginConfig,
    private val isPluginFolderGood: () -> Boolean,
    private val isShuttingDown: () -> Boolean,
    private val reloadConfig: () -> Boolean
) {
    private val permissions = PermissionsManager()

    private val rateLimiterProvider = ConfigBackedRateLimiter(
        dataDirectory = dataDirectory,
        logger = logger,
        defaultPlayersPerMinute = 30,
        defaultConsolePerMinute = 60
    )

    private data class CachedMessage(
        val lastModified: Long,
        val parsed: YamlToJson.ParsedMessage,
        val cachedAt: Long = System.currentTimeMillis()
    )

    private data class MessageListCache(val dirMtime: Long, val names: List<String>)

    private val messageCache = ConcurrentHashMap<String, CachedMessage>()
    private val messageListCache = AtomicReference<MessageListCache?>()
    private var lastCleanupMs = 0L
    private val generation = AtomicLong(0L)
    private val ioLock = Any()
    @Volatile private var ioExecutor: ExecutorService = newIoExecutor()
    private val lastReloadMs = AtomicLong(0L)
    private val lastMessageListRefresh = AtomicLong(0L)

    private val maxCacheEntries = 500
    private val cacheCleanupIntervalMs = 300_000L
    private val cacheMaxAgeMs = 3_600_000L
    private val reloadCooldownMs = 5_000L
    private val messageListRefreshIntervalMs = 60_000L

    private class MessageBuildException(val userMessage: String, cause: Throwable? = null) :
        RuntimeException(userMessage, cause)

    init {
        refreshMessageListAsync(force = true)
    }

    fun execute(source: CommandSource, command: CommandParser.ParsedCommand) {
        if (!isPluginFolderGood()) {
            source.sendMessage(Component.text("Plugin is not initialized correctly. Check logs."))
            return
        }

        val limiter = rateLimiterProvider.current()
        if (!limiter.tryAcquire(keyFor(source))) {
            webhookClient.metrics.rateLimitHits.incrementAndGet()
            source.sendMessage(Component.text("Rate limited. Try again in a bit."))
            return
        }

        when (command) {
            is CommandParser.ParsedCommand.Info -> handleInfo(source)
            is CommandParser.ParsedCommand.Help -> handleHelp(source)
            is CommandParser.ParsedCommand.Reload -> handleReload(source)
            is CommandParser.ParsedCommand.Stats -> handleStats(source)
            is CommandParser.ParsedCommand.Send -> handleSend(source, command)
        }
    }

    private fun handleInfo(source: CommandSource) {
        source.sendMessage(Component.text("Velocity TO Discord Webhook Plugin"))
        source.sendMessage(Component.text("Version: ${PluginInfo.version}"))
        source.sendMessage(Component.text("Author: IND_is_Here"))
    }

    private fun handleHelp(source: CommandSource) {
        source.sendMessage(Component.text("=== Velocity Discord Webhook Plugin ==="))
        source.sendMessage(Component.text(""))
        source.sendMessage(Component.text("Usage:"))
        source.sendMessage(Component.text("  /vdiscord                    - Show plugin info"))
        source.sendMessage(Component.text("  /vdiscord help               - Show this help"))
        source.sendMessage(Component.text("  /vdiscord send <message>     - Send a message"))
        source.sendMessage(Component.text("  /vdiscord reload             - Reload config"))
        source.sendMessage(Component.text("  /vdiscord stats              - View webhook/send statistics"))
        source.sendMessage(Component.text(""))
        source.sendMessage(Component.text("Options:"))
        source.sendMessage(Component.text("  from <name>      - Override sender name"))
        source.sendMessage(Component.text("  to <webhook>     - Specify webhook to use"))
        source.sendMessage(Component.text(""))
        source.sendMessage(Component.text("Message argument:"))
        source.sendMessage(Component.text("  - Alias from config.yml (e.g., 'example1')"))
        source.sendMessage(Component.text("  - Filename (e.g., 'my-message' or 'my-message.yml')"))
        source.sendMessage(Component.text("  - Raw text in quotes (e.g., \"Hello world\")"))
        source.sendMessage(Component.text(""))
        source.sendMessage(Component.text("Quoting rules:"))
        source.sendMessage(Component.text("  - Use quotes for names/messages with spaces"))
        source.sendMessage(Component.text("  - Escape quotes inside values with \\\\\" or \\\\\'"))
    }

    private fun handleStats(source: CommandSource) {
        val metrics = webhookClient.metrics
        val limits = rateLimiterProvider.currentLimits()
        val pending = webhookClient.pendingCount()

        source.sendMessage(Component.text("=== Velocity Discord Webhook Stats ==="))
        source.sendMessage(Component.text("Pending webhooks: $pending"))
        source.sendMessage(Component.text("Sent: ${metrics.webhooksSent.get()} | Failed: ${metrics.webhooksFailed.get()}"))
        source.sendMessage(Component.text("Rate limit hits: ${metrics.rateLimitHits.get()}"))
        source.sendMessage(Component.text("Rate limits per min: players=${limits.first}, console=${limits.second}"))
    }

    private fun handleReload(source: CommandSource) {
        val now = System.currentTimeMillis()
        val last = lastReloadMs.get()
        if (now - last < reloadCooldownMs) {
            val waitMs = reloadCooldownMs - (now - last)
            val waitSeconds = (waitMs + 999) / 1000
            source.sendMessage(Component.text("Reload rate-limited. Try again in ${waitSeconds}s."))
            return
        }
        lastReloadMs.set(now)

        generation.incrementAndGet()
        restartIoExecutor()
        webhookClient.cancelAll()

        val ok = reloadConfig()
        rateLimiterProvider.forceRefresh()
        clearMessageCaches()
        refreshMessageListAsync(force = true)

        if (ok) {
            source.sendMessage(Component.text("Config reloaded successfully."))
            logger.info("Config reloaded by {}", sourceDebugName(source))
        } else {
            source.sendMessage(Component.text("Config reload failed. Check logs."))
        }
    }

    private fun handleSend(source: CommandSource, command: CommandParser.ParsedCommand.Send) {
        val cfg = getConfig()
        val requestGeneration = generation.get()
        val isConsole = source !is Player
        val playerId = (source as? Player)?.uniqueId

        if (isShuttingDown()) {
            source.sendMessage(Component.text("Server is shutting down. Not sending webhooks."))
            return
        }

        val messageToken = command.messageToken

        val buildFuture = try {
            if (messageToken.startsWith("RAW:")) {
                handleRawMessage(source, command, messageToken)?.let { CompletableFuture.completedFuture(it) }
            } else {
                handleFileMessageAsync(source, command, messageToken, cfg)
            }
        } catch (e: IllegalArgumentException) {
            source.sendMessage(Component.text("Error: ${e.message}"))
            null
        } catch (e: Exception) {
            source.sendMessage(Component.text("Failed to build webhook payload: ${e.message ?: "Unknown error"}"))
            logger.error("Payload build failed", e)
            null
        } ?: return

        buildFuture.whenComplete { result, err ->
            server.scheduler.buildTask(plugin, Runnable {
                if (requestGeneration != generation.get() || isShuttingDown()) {
                    return@Runnable
                }

                if (err != null) {
                    val cause = unwrapCompletion(err)
                    val userMsg = if (cause is MessageBuildException) {
                        cause.userMessage
                    } else {
                        "Failed to build webhook payload. Check logs."
                    }
                    if (isConsole) {
                        logger.warn("Console webhook build failed: {}", userMsg)
                    } else if (playerId != null) {
                        server.getPlayer(playerId).ifPresent { it.sendMessage(Component.text(userMsg)) }
                    }
                    if (cause !is MessageBuildException || cause.cause != null) {
                        logger.error("Payload build failed", cause)
                    }
                    return@Runnable
                }

                val built = result ?: return@Runnable
                sendWebhookMessage(isConsole, playerId, command, built, cfg, requestGeneration)
            }).schedule()
        }
    }

    private fun handleRawMessage(
        source: CommandSource,
        command: CommandParser.ParsedCommand.Send,
        messageToken: String
    ): YamlToJson.BuildResult? {
        val rawContent = messageToken.removePrefix("RAW:")

        val permCheck = permissions.checkRawMessagePermission(source)
        if (!permCheck.allowed) {
            source.sendMessage(Component.text(permCheck.reason!!))
            return null
        }

        val validatedFrom = validateAndCheckUsername(source, command.from) ?: return null

        if (rawContent.length > 2000) {
            source.sendMessage(Component.text("Message too long. Discord limit: 2000 characters."))
            return null
        }

        return YamlToJson.buildFromRawContent(
            content = rawContent,
            usernameOverride = validatedFrom
        )
    }

    private fun handleFileMessageAsync(
        source: CommandSource,
        command: CommandParser.ParsedCommand.Send,
        messageToken: String,
        cfg: PluginConfig
    ): CompletableFuture<YamlToJson.BuildResult>? {
        val fileBase = resolveMessageFileBase(messageToken, cfg)

        if (!isSafeKey(fileBase)) {
            source.sendMessage(Component.text("Invalid message name. Allowed: a-z, 0-9, _, -, ."))
            return null
        }

        val permCheck = permissions.checkMessagePermission(source, fileBase)
        if (!permCheck.allowed) {
            source.sendMessage(Component.text(permCheck.reason!!))
            return null
        }

        val validatedFrom = validateAndCheckUsername(source, command.from) ?: return null

        return buildMessageFromFileAsync(fileBase, validatedFrom)
    }

    private fun validateAndCheckUsername(source: CommandSource, username: String?): String? {
        if (username == null) return null

        val validated = validateUsername(username)
        if (validated == null) {
            source.sendMessage(Component.text("Invalid 'from' username. Discord limit is 1-80 chars; no control chars/newlines."))
        }
        return validated
    }

    private fun sendWebhookMessage(
        isConsole: Boolean,
        playerId: java.util.UUID?,
        command: CommandParser.ParsedCommand.Send,
        buildResult: YamlToJson.BuildResult,
        cfg: PluginConfig,
        requestGeneration: Long
    ) {
        val verbose = cfg.loggingMode == LoggingMode.DEBUG || cfg.loggingMode == LoggingMode.FINE
        val webhookName = command.to
            ?: buildResult.webhookNameOverride
            ?: cfg.pickDefaultOrGlobalWebhookName()

        if (webhookName == null) {
            respondToSender(isConsole, playerId, "No webhook specified. Use 'to <webhook>' or add 'default'/'global' webhook in config.yml")
            return
        }

        val webhookUrl = cfg.resolveWebhookUrlByName(webhookName)
        if (webhookUrl == null) {
            respondToSender(isConsole, playerId, "Unknown webhook '$webhookName'. Check config.yml.")
            return
        }

        if (!webhookClient.looksLikeDiscordWebhookUrl(webhookUrl)) {
            respondToSender(isConsole, playerId, "Blocked: webhook '$webhookName' is not a Discord webhook URL.")
            logger.warn("Blocked non-Discord webhook URL for '{}'", webhookName)
            return
        }

        val payloadBytes = buildResult.json.toByteArray(StandardCharsets.UTF_8).size
        if (payloadBytes > YamlToJson.maxJsonBytes()) {
            respondToSender(isConsole, playerId, "Message payload too large to send.")
            return
        }
        if (verbose) {
            logger.debug("Sending webhook '{}' ({} bytes)", webhookName, payloadBytes)
        }

        webhookClient.sendAsync(webhookUrl, buildResult.json, logger)
            .orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { result, err ->
                if (isShuttingDown() || requestGeneration != generation.get()) return@whenComplete

                try {
                    server.scheduler.buildTask(plugin, Runnable {
                        if (isShuttingDown() || requestGeneration != generation.get()) {
                            return@Runnable
                        }
                        val player = if (!isConsole && playerId != null) {
                            server.getPlayer(playerId).orElse(null)
                        } else {
                            null
                        }
                        when {
                            err != null -> {
                                webhookClient.metrics.webhooksFailed.incrementAndGet()
                                if (err is TimeoutException) {
                                    if (isConsole) {
                                        logger.warn("Webhook request timed out after 30s for console.")
                                    } else {
                                        player?.sendMessage(Component.text("Webhook request timed out after 30s."))
                                    }
                                } else {
                                    if (isConsole) {
                                        logger.warn("Webhook failed for console. Check logs.")
                                    } else {
                                        player?.sendMessage(Component.text("Webhook failed. Check logs."))
                                    }
                                    if (verbose) {
                                        logger.warn("Webhook '{}' failed: {}", webhookName, err.toString())
                                    }
                                }
                            }

                            result.ok -> {
                                webhookClient.metrics.webhooksSent.incrementAndGet()
                                if (isConsole) {
                                    logger.info("Webhook sent for console. (HTTP {})", result.status)
                                } else {
                                    player?.sendMessage(Component.text("Sent. (HTTP ${result.status})"))
                                }
                                if (verbose) {
                                    logger.debug("Webhook '{}' delivered with status {}", webhookName, result.status)
                                }
                            }

                            else -> {
                                webhookClient.metrics.webhooksFailed.incrementAndGet()
                                if (isConsole) {
                                    logger.warn("Webhook failed for console. (HTTP {})", result.status)
                                } else {
                                    player?.sendMessage(Component.text("Failed. (HTTP ${result.status})"))
                                }
                                if (verbose) {
                                    logger.warn("Webhook '{}' returned status {} body={}", webhookName, result.status, result.body)
                                }
                            }
                        }
                    }).schedule()
                } catch (e: Exception) {
                    logger.error("Failed to schedule webhook response task", e)
                }
            }
    }

    private fun resolveMessageFileBase(token: String, cfg: PluginConfig): String {
        val mapped = cfg.messages[token.lowercase()]
        if (mapped != null) {
            return mapped.trim().lowercase()
        }
        return token.lowercase()
    }

    private fun resolveAndValidateMessageFile(fileBase: String): Path {
        val messagesDir = dataDirectory / "messages"
        if (Files.notExists(messagesDir)) {
            logger.error("Missing messages directory: {}", messagesDir)
            throw MessageBuildException("Message '$fileBase' not found or blocked.")
        }

        val messagesDirReal = messagesDir.toRealPath()

        val filename = if (fileBase.endsWith(".yml", ignoreCase = true) || fileBase.endsWith(".yaml", ignoreCase = true)) {
            fileBase
        } else {
            "$fileBase.yml"
        }

        val candidate = messagesDirReal.resolve(filename)
        if (Files.notExists(candidate)) {
            throw MessageBuildException("Message not found.")
        }

        val messageFileReal = candidate.toRealPath()

        if (!messageFileReal.startsWith(messagesDirReal)) {
            logger.error("Blocked path traversal: fileBase={} resolved={}", fileBase, messageFileReal)
            throw MessageBuildException("Message '$fileBase' not found or blocked.")
        }

        val size = Files.size(messageFileReal)
        if (size > YamlToJson.MAX_MESSAGE_FILE_BYTES) {
            logger.warn("Blocked oversized message file: {} ({} bytes)", messageFileReal.fileName, size)
            throw MessageBuildException("Message '$fileBase' not found or blocked.")
        }

        return messageFileReal
    }

    private fun buildMessageFromFileAsync(
        fileBase: String,
        usernameOverride: String?
    ): CompletableFuture<YamlToJson.BuildResult> {
        val executor = ioExecutor
        return CompletableFuture.supplyAsync({
            val now = System.currentTimeMillis()
            if (now - lastCleanupMs > cacheCleanupIntervalMs) {
                val expired = messageCache.entries
                    .filter { (_, v) -> now - v.cachedAt > cacheMaxAgeMs }
                    .map { it.key }
                expired.forEach { key -> messageCache.remove(key) }
                val overage = messageCache.size - maxCacheEntries
                if (overage > 0) {
                    val oldest = messageCache.entries
                        .sortedBy { it.value.cachedAt }
                        .take(overage)
                    oldest.forEach { entry -> messageCache.remove(entry.key) }
                }
                lastCleanupMs = now
            }

            val messageFile = resolveAndValidateMessageFile(fileBase)

            val mtime = Files.getLastModifiedTime(messageFile).toMillis()
            val parsed = messageCache[fileBase]?.takeIf { it.lastModified == mtime }?.parsed
                ?: run {
                    val parsedMessage = YamlToJson.parseMessageFile(messageFile)
                    messageCache[fileBase] = CachedMessage(mtime, parsedMessage)
                    parsedMessage
                }

            YamlToJson.buildFromParsed(parsed, usernameOverride)
        }, executor).exceptionally { ex ->
            val cause = unwrapCompletion(ex)
            if (cause !is MessageBuildException) {
                logger.error("Failed to build message '{}': {}", fileBase, cause.message, cause)
            }
            if (cause is MessageBuildException) throw cause
            throw MessageBuildException("Failed to build message '$fileBase'.", cause)
        }
    }

    fun suggest(source: CommandSource, args: Array<String>): List<String> {
        val cfg = getConfig()
        val isConsole = source !is Player
        if (!isConsole && !source.hasPermission("vdiscord.use")) {
            return emptyList()
        }

        if (args.isEmpty()) {
            return listOf("help", "send", "message", "msg", "reload", "stats")
        }

        if (args.size == 1) {
            return listOf("help", "send", "message", "msg", "reload", "stats").filter {
                it.startsWith(args[0].lowercase())
            }
        }

        val subcommand = args[0].lowercase()
        if (subcommand !in listOf("send", "message", "msg")) {
            return emptyList()
        }

        val suggestions = mutableListOf<String>()
        suggestions += "from"
        suggestions += "to"

        val canListMessages = isConsole || source.hasPermission("vdiscord.send.message.*")
        if (canListMessages) {
            val cached = messageListCache.get()
            if (cached == null) {
                refreshMessageListAsync()
            } else {
                cached.names.forEach { name ->
                    val perm = "vdiscord.send.message.$name"
                    if (isConsole || source.hasPermission(perm)) {
                        suggestions += name
                    }
                }
                refreshMessageListAsync()
            }
        }

        cfg.webhooks.keys.sorted().forEach { suggestions += it }

        return suggestions.distinct()
    }

    private fun validateUsername(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return null
        if (trimmed.any { it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }) return null
        return trimmed
    }

    private fun isSafeKey(s: String): Boolean {
        return s.matches(Regex("^[a-z0-9._-]{1,64}$"))
    }

    private fun keyFor(source: CommandSource): String {
        return (source as? Player)?.uniqueId?.toString() ?: "CONSOLE"
    }

    private fun sourceDebugName(source: CommandSource): String {
        return (source as? Player)?.username ?: source.toString()
    }

    private fun respondToSender(isConsole: Boolean, playerId: java.util.UUID?, message: String) {
        if (isConsole) {
            logger.info(message)
            return
        }
        if (playerId != null) {
            server.getPlayer(playerId).ifPresent { it.sendMessage(Component.text(message)) }
        }
    }

    private fun refreshMessageListAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastMessageListRefresh.get()
            if (now - last < messageListRefreshIntervalMs) return
            if (!lastMessageListRefresh.compareAndSet(last, now)) return
        } else {
            lastMessageListRefresh.set(now)
        }

        val executor = ioExecutor
        if (executor.isShutdown) {
            return
        }
        executor.submit {
            try {
                val messagesDir = dataDirectory / "messages"
                if (!Files.exists(messagesDir)) {
                    messageListCache.set(MessageListCache(0L, emptyList()))
                    return@submit
                }

                val dirMtime = try {
                    Files.getLastModifiedTime(messagesDir).toMillis()
                } catch (_: Exception) {
                    0L
                }

                val names = Files.list(messagesDir).use { stream ->
                    stream
                        .filter { it.fileName.toString().endsWith(".yml", ignoreCase = true) }
                        .map { it.fileName.toString().removeSuffix(".yml").lowercase() }
                        .toList()
                }

                messageListCache.set(MessageListCache(dirMtime, names))
            } catch (e: Exception) {
                logger.debug("Async message list refresh failed", e)
            }
        }
    }

    private fun newIoExecutor(): ExecutorService {
        return Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "vdiscord-io-${System.identityHashCode(runnable)}").apply {
                isDaemon = true
            }
        }
    }

    private fun shutdownExecutor(executor: ExecutorService) {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
        }
    }

    private fun restartIoExecutor() {
        val old = synchronized(ioLock) {
            val current = ioExecutor
            ioExecutor = newIoExecutor()
            current
        }
        shutdownExecutor(old)
    }

    fun shutdown() {
        val executor = synchronized(ioLock) { ioExecutor }
        shutdownExecutor(executor)
    }

    private fun unwrapCompletion(err: Throwable): Throwable {
        var current = err
        while (current.cause != null &&
            (current is java.util.concurrent.CompletionException ||
                    current is java.util.concurrent.ExecutionException ||
                    current is java.lang.reflect.UndeclaredThrowableException)
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun clearMessageCaches() {
        messageCache.clear()
        messageListCache.set(null)
        lastCleanupMs = 0L
        lastMessageListRefresh.set(0L)
    }
}
