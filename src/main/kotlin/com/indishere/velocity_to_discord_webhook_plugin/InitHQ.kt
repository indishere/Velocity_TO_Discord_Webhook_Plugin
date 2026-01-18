/* Initialization HeadQuarters, Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin


import com.google.inject.Inject
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.div

import org.slf4j.Logger
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor


class InitHQ @Inject constructor(
    val server: ProxyServer,
    val logger: Logger,
    @DataDirectory val dataDirectory: Path
) {
    private val configRef = AtomicReference(PluginConfig.defaults())
    private val maxConfigBytes = 1_048_576L // 1MB cap to prevent runaway config sizes
    private val discordWebhook = DiscordWebhook(PluginInfo.userAgent)
    @Volatile private var commandRuntime: CommandRuntime? = null

    @Volatile
    private var pluginFolderIsGood: Boolean = false

    @Volatile
    private var shuttingDown: Boolean = false

    @Subscribe
    fun onProxyInitialization(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        server.scheduler.buildTask(this, Runnable {
            pluginFolderCheckup()

            if (pluginFolderIsGood && reloadConfig()) {
                registerCommands()
            } else {
                logger.error("Plugin startup aborted: plugin folder or config setup failed. Command registration skipped.")
            }
        }).schedule()
    }

    @Subscribe
    fun onProxyShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        shuttingDown = true
        logger.info("Shutting down Velocity Discord Webhook plugin. Cancelling in-flight webhook requests...")

        val pendingBefore = discordWebhook.pendingCount()
        if (pendingBefore > 0) {
            logger.info("Waiting for {} pending webhook(s) to finish (max 10s)...", pendingBefore)
        }

        try {
            discordWebhook.awaitPending(timeout = 10, unit = TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Shutdown wait ended with error (continuing shutdown anyway).", e)
        }

        commandRuntime?.shutdown()
        discordWebhook.shutdown()
    }

    fun isPluginFolderGood(): Boolean = pluginFolderIsGood

    fun isShuttingDown(): Boolean = shuttingDown

    fun getConfig(): PluginConfig = configRef.get()

    fun reloadConfig(): Boolean {
        if (!pluginFolderIsGood) {
            logger.error("Refusing to reload config: plugin folder not initialized.")
            return false
        }

        val configFile = dataDirectory / "config.yml"
        if (Files.notExists(configFile)) {
            logger.error("Missing config file on disk: {}", configFile)
            return false
        }

        return try {
            val newConfig = loadConfigFile(configFile)
            configRef.set(newConfig)
            logger.info("Config reloaded successfully.")
            true
        } catch (e: Exception) {
            logger.error("Config reload failed. Keeping previous config.", e)
            false
        }
    }

    private fun loadConfigFile(configFile: Path): PluginConfig {
        val size = Files.size(configFile)
        require(size <= maxConfigBytes) { "config.yml too large (${size} bytes). Limit: $maxConfigBytes" }

        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 20
            codePointLimit = 50_000
        }

        val yaml = Yaml(SafeConstructor(loaderOptions))

        val root: Any? = Files.newInputStream(configFile).use { input -> yaml.load(input) }
        val mapRoot = (root as? Map<*, *>) ?: emptyMap<Any?, Any?>()

        fun mapAt(vararg keys: String): Map<*, *>? {
            var cur: Any? = mapRoot
            for (k in keys) {
                cur = (cur as? Map<*, *>)?.get(k)
            }
            return cur as? Map<*, *>
        }

        val pluginMap = mapAt("plugin")
        val discordMap = mapAt("discord")

        val loggingMode = (pluginMap?.get("logging-mode") as? String)
            ?.trim()
            ?.uppercase()
            ?.let { LoggingMode.fromString(it) }
            ?: LoggingMode.PRODUCTION

        val webhooksRaw = (discordMap?.get("webhooks") as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        val messagesRaw = (discordMap?.get("messages") as? Map<*, *>) ?: emptyMap<Any?, Any?>()

        val webhooks = webhooksRaw.entries
            .mapNotNull { (k, v) ->
                val name = (k as? String)?.trim()?.lowercase()
                val url = (v as? String)?.trim()
                if (name.isNullOrBlank() || url.isNullOrBlank()) return@mapNotNull null
                name to url
            }
            .toMap()

        val messages = messagesRaw.entries
            .mapNotNull { (k, v) ->
                val alias = (k as? String)?.trim()?.lowercase()
                val fileBase = (v as? String)?.trim()?.lowercase()
                if (alias.isNullOrBlank() || fileBase.isNullOrBlank()) return@mapNotNull null
                alias to fileBase
            }
            .toMap()

        webhooks.forEach { (name, url) ->
            if (!discordWebhook.looksLikeDiscordWebhookUrl(url)) {
                logger.warn("Webhook '{}' does not look like a Discord webhook URL. (Will be blocked on send)", name)
            }
        }

        return PluginConfig(
            loggingMode = loggingMode,
            webhooks = webhooks,
            messages = messages
        )
    }

    private fun copyBundledResourcesIntoPluginDir() {
        val resourceRoot = "resources"

        val url = javaClass.classLoader.getResource(resourceRoot)
            ?: javaClass.classLoader.getResource("$resourceRoot/")
            ?: return

        val uri = url.toURI()

        fun copyTree(fromRoot: Path) {
            Files.walk(fromRoot).use { stream ->
                stream.forEach { p ->
                    if (p == fromRoot) return@forEach

                    val rel = fromRoot.relativize(p).toString()
                    val out = dataDirectory.resolve(rel)

                    if (Files.isDirectory(p)) {
                        Files.createDirectories(out)
                    } else {
                        if (Files.exists(out)) return@forEach
                        Files.createDirectories(out.parent)
                        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        if (uri.scheme == "jar") {
            var fsCreated: Boolean
            val fs = try {
                fsCreated = true
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            } catch (_: FileSystemAlreadyExistsException) {
                fsCreated = false
                FileSystems.getFileSystem(uri)
            }

            try {
                val jarRoot = fs.getPath("/$resourceRoot")
                copyTree(jarRoot)
            } finally {
                if (fsCreated) {
                    fs.close()
                }
            }
        } else {
            copyTree(Paths.get(uri))
        }
    }

    fun pluginFolderCheckup() {
        try {
            Files.createDirectories(dataDirectory)

            val messagesDir = dataDirectory / "messages"
            Files.createDirectories(messagesDir)

            copyBundledResourcesIntoPluginDir()

            val configFile = dataDirectory / "config.yml"
            if (Files.notExists(configFile)) {
                error("Missing resource: config.yml")
            }

            val exampleMessage = messagesDir / "example.yml"
            if (Files.notExists(exampleMessage)) {
                error("Missing resource: messages/example.yml")
            }

            pluginFolderIsGood = true
            logger.info("Plugin data folder is ready at {}", dataDirectory)

        } catch (e: Exception) {
            pluginFolderIsGood = false
            logger.error("Plugin folder setup failed", e)
        }
    }

    fun registerCommands() {
        val runtime = CommandRuntime(
            server = server,
            logger = logger,
            plugin = this,
            dataDirectory = dataDirectory,
            webhookClient = discordWebhook,
            getConfig = { getConfig() },
            isPluginFolderGood = { isPluginFolderGood() },
            isShuttingDown = { isShuttingDown() },
            reloadConfig = { reloadConfig() }
        )
        commandRuntime = runtime

        val handler = RegisterCommands(
            logger = logger,
            getConfig = { getConfig() },
            runtime = runtime
        )

        server.commandManager.register(
            server.commandManager.metaBuilder("vdiscord").build(),
            handler
        )

        logger.info("Registered /vdiscord")
    }
}

enum class LoggingMode {
    INFO, PRODUCTION, FINE, DEBUG;

    companion object {
        fun fromString(s: String): LoggingMode =
            entries.firstOrNull { it.name.equals(s, true) } ?: PRODUCTION
    }
}

data class PluginConfig(
    val loggingMode: LoggingMode,
    val webhooks: Map<String, String>,
    val messages: Map<String, String>
) {
    fun pickDefaultOrGlobalWebhookName(): String? =
        webhooks.keys.firstOrNull { it.equals("default", true) }
            ?: webhooks.keys.firstOrNull { it.equals("global", true) }

    fun resolveWebhookUrlByName(name: String): String? =
        webhooks[name.lowercase()]

    companion object {
        fun defaults(): PluginConfig =
            PluginConfig(
                loggingMode = LoggingMode.PRODUCTION,
                webhooks = emptyMap(),
                messages = emptyMap()
            )
    }
}
