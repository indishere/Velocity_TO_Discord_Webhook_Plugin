package com.indishere.velocity_to_discord_webhook_plugin


import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.div


/**
 * Velocity loads plugins in two stages: construction (this constructor) and initialization.
 * Don't touch most of the Velocity API in the constructor; wait for ProxyInitializeEvent.
 */


class Init_HQ @Inject constructor(
    val server: ProxyServer,
    val logger: Logger,
    @DataDirectory val dataDirectory: Path
) {
    // Atomic, so readers never see partially-updated config during reload.
    private val configRef = AtomicReference(PluginConfig.defaults())

    @Volatile
    private var pluginFolderIsGood: Boolean = false

    @Volatile
    private var shuttingDown: Boolean = false

    @Subscribe
    fun onProxyInitialization(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        // SAFE ZONE STARTS HERE
        pluginFolderCheckup()

        if (pluginFolderIsGood) {
            reloadConfig() // loads from disk (dataDirectory/config.yml)
            registerCommands()
        } else {
            logger.error("Plugin folder setup failed. Command registration skipped.")
        }
    }

    @Subscribe
    fun onProxyShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        shuttingDown = true
        logger.info("Shutting down Velocity Discord Webhook plugin. Waiting for in-flight webhook requests...")

        // Best-effort: wait a short time so in-flight requests can finish.
        try {
            DiscordWebhook.awaitPending(timeout = 3, unit = TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Shutdown wait ended with error (continuing shutdown anyway).", e)
        }
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
            val parsed = loadConfigFile(configFile)
            configRef.set(parsed)

            // Don't log webhook URLs. Ever. Not even accidentally.
            logger.info(
                "Config loaded. loggingMode={}, webhooks={}, messages={}, hasDefaultOrGlobal={}",
                parsed.loggingMode,
                parsed.webhooks.keys.sorted(),
                parsed.messages.keys.sorted(),
                parsed.hasDefaultOrGlobalWebhook()
            )

            true
        } catch (e: Exception) {
            logger.error("Config reload failed. Keeping previous config.", e)
            false
        }
    }

    private fun loadConfigFile(configFile: Path): PluginConfig {
        val maxBytes = 1_048_576L // 1MB
        val size = Files.size(configFile)
        require(size <= maxBytes) { "config.yml too large (${size} bytes). Limit: $maxBytes" }

        val loaderOptions = LoaderOptions().apply {
            // Safety defaults and DoS protections (YAML bombs).
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            // SnakeYAML uses code points as a limit (3MB default in recent versions); keep it strict.
            codePointLimit = 1_000_000
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

        // Validation warnings (no secrets printed)
        webhooks.forEach { (name, url) ->
            if (!DiscordWebhook.looksLikeDiscordWebhookUrl(url)) {
                logger.warn("Webhook '{}' does not look like a Discord webhook URL. (Will be blocked on send)", name)
            }
        }

        return PluginConfig(
            loggingMode = loggingMode,
            webhooks = webhooks,
            messages = messages
        )
    }

    fun pluginFolderCheckup() {
        try {
            Files.createDirectories(dataDirectory)

            val messagesDir = dataDirectory / "messages"
            Files.createDirectories(messagesDir)

            val configFile = dataDirectory / "config.yml"
            if (Files.notExists(configFile)) {
                javaClass.classLoader
                    .getResourceAsStream("config.yml")
                    ?.use { input -> Files.copy(input, configFile) }
                    ?: error("Missing resource: config.yml")
            }

            val exampleMessage = messagesDir / "example.yml"
            if (Files.notExists(exampleMessage)) {
                javaClass.classLoader
                    .getResourceAsStream("messages/example.yml")
                    ?.use { input -> Files.copy(input, exampleMessage) }
                    ?: error("Missing resource: messages/example.yml")
            }

            pluginFolderIsGood = true
            logger.info("Plugin data folder is ready at {}", dataDirectory)

        } catch (e: Exception) {
            pluginFolderIsGood = false
            logger.error("Plugin folder setup failed", e)
        }
    }

    fun registerCommands() {
        val handler = CommandHandler(
            server = server,
            logger = logger,
            plugin = this,
            dataDirectory = dataDirectory,
            getConfig = { getConfig() },
            isPluginFolderGood = { isPluginFolderGood() },
            isShuttingDown = { isShuttingDown() },
            reloadConfig = { reloadConfig() }
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
    fun hasDefaultOrGlobalWebhook(): Boolean =
        webhooks.keys.any { it.equals("default", true) || it.equals("global", true) }

    fun pickDefaultOrGlobalWebhookName(): String? =
        webhooks.keys.firstOrNull { it.equals("default", true) }
            ?: webhooks.keys.firstOrNull { it.equals("global", true) }

    fun resolveWebhookUrlByName(name: String): String? =
        webhooks[name.lowercase()]

    fun resolveMessageFileBase(token: String): String =
        (messages[token.lowercase()] ?: token).trim().lowercase()

    companion object {
        fun defaults(): PluginConfig =
            PluginConfig(
                loggingMode = LoggingMode.PRODUCTION,
                webhooks = emptyMap(),
                messages = emptyMap()
            )
    }
}
