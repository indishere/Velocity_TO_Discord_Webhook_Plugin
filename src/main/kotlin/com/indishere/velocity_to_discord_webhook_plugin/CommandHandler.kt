package com.indishere.velocity_to_discord_webhook_plugin


import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.div


class CommandHandler(
    private val server: ProxyServer,
    private val logger: Logger,
    private val plugin: InitHQ,
    private val dataDirectory: Path,
    private val getConfig: () -> PluginConfig,
    private val isPluginFolderGood: () -> Boolean,
    private val isShuttingDown: () -> Boolean,
    private val reloadConfig: () -> Boolean
) : SimpleCommand {

    // 30/min per player. Console is still rate limited, just way higher.
    private val rateLimiter = RateLimiter(playerMaxPerMinute = 30, consoleMaxPerMinute = 300)

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        val cfg = getConfig()
        val verbose = cfg.loggingMode == LoggingMode.DEBUG

        if (verbose) {
            logger.debug("/vdiscord invoked by={} args={}", sourceDebugName(source), sanitizeArgs(args))
        }

        val isConsole = source !is Player

        // Console = GOD
        if (!isConsole && !source.hasPermission("vdiscord.use")) {
            source.sendMessage(Component.text("You do not have permission to use this plugin. (vdiscord.use)"))
            return
        }

        if (!isPluginFolderGood()) {
            source.sendMessage(Component.text("Plugin is not initialized correctly. Check logs."))
            return
        }

        if (!rateLimiter.tryAcquire(keyFor(source))) {
            DiscordWebhook.Metrics.rateLimitHits.incrementAndGet()
            source.sendMessage(Component.text("Rate limited. Try again in a bit."))
            return
        }

        if (args.isEmpty()) {
            sendUsage(source, cfg)
            return
        }

        when (args[0].lowercase(Locale.ROOT)) {
            "reload" -> handleReload(source, isConsole)
            "send" -> handleSend(source, isConsole, args.drop(1).toTypedArray(), cfg)
            else -> {
                source.sendMessage(Component.text("Unknown subcommand."))
                sendUsage(source, cfg)
            }
        }
    }

    private fun handleReload(source: CommandSource, isConsole: Boolean) {
        if (!isConsole && !source.hasPermission("vdiscord.reload")) {
            source.sendMessage(Component.text("You do not have permission to reload. (vdiscord.reload)"))
            return
        }

        val ok = reloadConfig()
        if (ok) {
            source.sendMessage(Component.text("Config reloaded."))
        } else {
            source.sendMessage(Component.text("Config reload failed. Check logs."))
        }
    }

    private fun handleSend(source: CommandSource, isConsole: Boolean, args: Array<String>, cfg: PluginConfig) {
        if (args.isEmpty()) {
            source.sendMessage(Component.text("Usage: /vdiscord send [from <name>] [to <webhook>] <message>"))
            return
        }

        if (isShuttingDown()) {
            source.sendMessage(Component.text("Server is shutting down. Not sending webhooks."))
            return
        }

        var index = 0
        var fromName: String? = null
        var toName: String? = null

        while (index < args.size) {
            when (args[index].lowercase(Locale.ROOT)) {
                "from" -> {
                    if (!isConsole && !source.hasPermission("vdiscord.send.from")) {
                        deny(source, "vdiscord.send.from")
                        return
                    }
                    fromName = validateUsername(args.getOrNull(index + 1))
                    if (fromName == null) {
                        source.sendMessage(Component.text("Invalid 'from' username. Discord limit is 1-80 chars; no control chars/newlines."))
                        return
                    }
                    index += 2
                }

                "to" -> {
                    if (!isConsole && !source.hasPermission("vdiscord.send.to")) {
                        deny(source, "vdiscord.send.to")
                        return
                    }
                    toName = args.getOrNull(index + 1)?.trim()
                    if (toName.isNullOrBlank()) {
                        source.sendMessage(Component.text("Missing value after 'to'."))
                        return
                    }
                    index += 2
                }

                else -> break
            }
        }

        val messageArgJoined = args.drop(index).joinToString(" ").trim()
        if (messageArgJoined.isBlank()) {
            source.sendMessage(Component.text("Message is required."))
            return
        }

        val rawParsed = parseRawMessage(messageArgJoined)
        val buildResult = try {
            if (rawParsed != null) {
                if (!isConsole && !source.hasPermission("vdiscord.send.message.raw")) {
                    deny(source, "vdiscord.send.message.raw")
                    return
                }

                if (rawParsed.length > 2000) {
                    source.sendMessage(Component.text("Message too long. Discord limit: 2000 characters."))
                    return
                }

                YamlToJson.buildFromRawContent(
                    content = rawParsed,
                    usernameOverride = fromName
                )
            } else {
                val token = messageArgJoined.lowercase(Locale.ROOT)

                if (!isSafeKey(token)) {
                    source.sendMessage(Component.text("Invalid message id '$token'. Allowed: a-z, 0-9, _, -, ."))
                    return
                }

                // Permission check on token FIRST (no bypass)
                val tokenPerm = "vdiscord.send.message.$token"
                if (!isConsole && !source.hasPermission(tokenPerm)) {
                    deny(source, tokenPerm)
                    return
                }

                val fileBase = cfg.resolveMessageFileBase(token)

                // If config maps token -> fileBase, also require permission for the mapped name.
                if (!fileBase.equals(token, true)) {
                    if (!isSafeKey(fileBase)) {
                        source.sendMessage(Component.text("Blocked unsafe mapped message id in config.yml: '$token' -> '$fileBase'."))
                        logger.error("Unsafe message mapping blocked: token={} fileBase={}", token, fileBase)
                        return
                    }

                    val mappedPerm = "vdiscord.send.message.$fileBase"
                    if (!isConsole && !source.hasPermission(mappedPerm)) {
                        deny(source, mappedPerm)
                        return
                    }
                }

                val messageFile = resolveMessageFilePathSafely(token, fileBase) ?: return
                YamlToJson.buildFromMessageFile(
                    messageFile = messageFile,
                    usernameOverride = fromName
                )
            }
        } catch (e: Exception) {
            source.sendMessage(Component.text("Failed to build webhook payload: ${e.message ?: "Unknown error"}"))
            logger.error("Payload build failed", e)
            return
        }

        // Resolve webhook name with your rule:
        // - explicit "to" wins
        // - message file override wins next
        // - otherwise ONLY allow default/global (no guessing)
        val webhookName = toName
            ?: buildResult.webhookNameOverride
            ?: cfg.pickDefaultOrGlobalWebhookName()

        if (webhookName == null) {
            source.sendMessage(
                Component.text(
                    "Missing 'to <webhook>'. Add 'default' or 'global' under discord.webhooks in config.yml to make 'to' optional."
                )
            )
            return
        }

        val webhookUrl = cfg.resolveWebhookUrlByName(webhookName)
        if (webhookUrl == null) {
            source.sendMessage(Component.text("Unknown webhook '$webhookName'. Check config.yml."))
            return
        }

        if (!DiscordWebhook.looksLikeDiscordWebhookUrl(webhookUrl)) {
            source.sendMessage(Component.text("Blocked: webhook '$webhookName' is not a Discord webhook URL."))
            logger.warn("Blocked non-Discord webhook URL for '{}'", webhookName)
            return
        }

        if (buildResult.json.length > 50_000) {
            source.sendMessage(Component.text("Message payload too large to send."))
            return
        }

        // Send async with timeout, then reply on scheduler thread.
        DiscordWebhook.sendAsync(webhookUrl, buildResult.json, logger)
            .orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { result, err ->
                if (isShuttingDown()) return@whenComplete

                try {
                    server.scheduler.buildTask(plugin, Runnable {
                        when {
                            err != null -> {
                                DiscordWebhook.Metrics.webhooksFailed.incrementAndGet()
                                if (err is TimeoutException) {
                                    source.sendMessage(Component.text("Webhook request timed out after 30s."))
                                } else {
                                    source.sendMessage(Component.text("Webhook failed. Check logs."))
                                }
                            }

                            result.ok -> {
                                DiscordWebhook.Metrics.webhooksSent.incrementAndGet()
                                source.sendMessage(Component.text("Sent. (HTTP ${result.status})"))
                            }

                            else -> {
                                DiscordWebhook.Metrics.webhooksFailed.incrementAndGet()
                                source.sendMessage(Component.text("Failed. (HTTP ${result.status})"))
                            }
                        }
                    }).schedule()
                } catch (e: Exception) {
                    logger.error("Failed to schedule webhook response task", e)
                }
            }
    }

    private fun resolveMessageFilePathSafely(token: String, fileBase: String): Path? {
        val messagesDir = (dataDirectory / "messages")
        if (Files.notExists(messagesDir)) {
            logger.error("Missing messages directory: {}", messagesDir)
            return null
        }

        val messagesDirReal = try {
            messagesDir.toRealPath()
        } catch (e: Exception) {
            logger.error("Failed to resolve messages directory path: {}", messagesDir, e)
            return null
        }

        val messageFileReal = try {
            val candidate = messagesDirReal.resolve("$fileBase.yml")
            // Force existence + resolve symlinks
            if (Files.notExists(candidate)) throw IllegalArgumentException("Message file not found: $fileBase.yml")
            candidate.toRealPath()
        } catch (e: Exception) {
            // Don't leak absolute paths to chat.
            logger.warn("Failed to resolve message file: token={} fileBase={}", token, fileBase, e)
            return null
        }

        if (!messageFileReal.startsWith(messagesDirReal)) {
            logger.error("Blocked path traversal: token={} resolved={}", token, messageFileReal)
            return null
        }

        // File size limit (prevent people from "accidentally" parsing a 2GB YAML)
        val maxBytes = 1_048_576L
        val size = Files.size(messageFileReal)
        if (size > maxBytes) {
            logger.warn("Blocked oversized message file: {} ({} bytes)", messageFileReal.fileName, size)
            return null
        }

        return messageFileReal
    }

    private fun sendUsage(source: CommandSource, cfg: PluginConfig) {
        source.sendMessage(Component.text("/vdiscord send [from <name>] [to <webhook>] <message>"))
        source.sendMessage(Component.text("/vdiscord reload"))

        if (cfg.webhooks.isEmpty()) {
            source.sendMessage(Component.text("No webhooks configured yet. Edit config.yml under discord.webhooks."))
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val source = invocation.source()
        val args = invocation.arguments()
        val cfg = getConfig()

        val isConsole = source !is Player
        val token = args.getOrNull(0)?.lowercase(Locale.ROOT)

        if (args.isEmpty()) return listOf("send", "reload")

        if (args.size == 1) {
            return listOf("send", "reload").filter { it.startsWith(token ?: "") }
        }

        if (!args[0].equals("send", true)) return emptyList()

        val suggestions = mutableListOf<String>()

        // Suggest flags
        suggestions += "from"
        suggestions += "to"

        // Suggest message IDs (respect perms)
        val messagesDir = dataDirectory / "messages"
        if (Files.exists(messagesDir)) {
            try {
                Files.list(messagesDir).use { stream ->
                    stream
                        .filter { it.fileName.toString().endsWith(".yml", ignoreCase = true) }
                        .map { it.fileName.toString().removeSuffix(".yml") }
                        .forEach { name ->
                            val perm = "vdiscord.send.message.${name.lowercase(Locale.ROOT)}"
                            if (isConsole || (source as? CommandSource)?.hasPermission(perm) == true) {
                                suggestions += name
                            }
                        }
                }
            } catch (_: Exception) {
                // ignore autocomplete failures
            }
        }

        // Also suggest webhook names for "to" (optional but helpful)
        cfg.webhooks.keys.sorted().forEach { suggestions += it }

        return suggestions.distinct()
    }

    // -------------------- parsing + validation --------------------

    private fun validateUsername(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()

        // Discord username limit for webhook overrides: 1-80 chars
        if (trimmed.isEmpty() || trimmed.length > 80) return null

        // block control chars + whitespace controls
        if (trimmed.any { it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }) return null

        return trimmed
    }

    /**
     * Raw message rules:
     * - If it starts with " or ', treat it as raw and require a matching ending quote.
     * - If not, treat it as message-id token.
     */
    private fun parseRawMessage(joined: String): String? {
        val first = joined.firstOrNull() ?: return null
        if (first != '"' && first != '\'') return null

        if (joined.length < 2 || joined.last() != first) return null
        return joined.substring(1, joined.length - 1)
    }

    private fun isSafeKey(s: String): Boolean {
        // allow dot for namespacing: send.message.example.v1
        return s.matches(Regex("^[a-z0-9._-]{1,64}$"))
    }

    private fun deny(source: CommandSource, perm: String) {
        source.sendMessage(Component.text("Missing permission: $perm"))
    }

    private fun keyFor(source: CommandSource): String =
        (source as? Player)?.uniqueId?.toString() ?: "CONSOLE"

    private fun sourceDebugName(source: CommandSource): String =
        (source as? Player)?.username ?: source.toString()

    private fun sanitizeArgs(args: Array<String>): String {
        // never dump full raw messages to logs
        val out = ArrayList<String>(args.size)
        var lastWasFlag = false
        for ((i, arg) in args.withIndex()) {
            val lowerPrev = args.getOrNull(i - 1)?.lowercase(Locale.ROOT)
            val keepFull = lowerPrev in setOf("from", "to")
            val safe = when {
                keepFull -> arg
                arg.length > 50 -> arg.take(50) + "…"
                arg.startsWith("\"") || arg.startsWith("'") -> "<raw>"
                lastWasFlag -> arg
                else -> arg
            }
            out += safe
            lastWasFlag = arg.lowercase(Locale.ROOT) in setOf("from", "to")
        }
        return out.joinToString(" ")
    }

    // -------------------- tiny rate limiter --------------------

    private class RateLimiter(
        private val playerMaxPerMinute: Int,
        private val consoleMaxPerMinute: Int
    ) {
        private val windows = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

        fun tryAcquire(key: String): Boolean {
            val now = System.currentTimeMillis()
            val window = windows.computeIfAbsent(key) { ConcurrentLinkedQueue() }

            while (true) {
                val head = window.peek() ?: break
                if (now - head > 60_000) window.poll() else break
            }

            val limit = if (key == "CONSOLE") consoleMaxPerMinute else playerMaxPerMinute
            return if (window.size < limit) {
                window.offer(now)
                true
            } else {
                false
            }
        }
    }
}
