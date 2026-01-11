package com.indishere.velocity_to_discord_webhook_plugin


import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path


/**
 * Converts your message YAML format into a JSON payload for Discord Webhooks.
 *
 * Message file structure:
 * - plugin.webhook: optional webhook name override (name from config.yml, not URL)
 * - discord-webhook: the Discord payload (mirrors Discord docs)
 *
 * This parser is intentionally strict-ish to avoid YAML bombs and weird types.
 */


object YamlToJson {

    data class BuildResult(
        val webhookNameOverride: String?,
        val json: String
    )

    private val mapper = ObjectMapper()

    private fun newSafeYaml(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(loaderOptions))
    }

    fun buildFromRawContent(content: String, usernameOverride: String?): BuildResult {
        val payload = linkedMapOf<String, Any?>(
            "content" to content
        )
        if (!usernameOverride.isNullOrBlank()) {
            payload["username"] = usernameOverride
        }

        val json = mapper.writeValueAsString(payload)
        require(json.length <= 50_000) { "Payload too large." }
        return BuildResult(webhookNameOverride = null, json = json)
    }

    fun buildFromMessageFile(messageFile: Path, usernameOverride: String?): BuildResult {
        val maxBytes = 1_048_576L
        val size = Files.size(messageFile)
        require(size <= maxBytes) { "Message file too large (${size} bytes). Limit: $maxBytes" }

        val yaml = newSafeYaml()

        val rootAny: Any? = Files.newInputStream(messageFile).use { yaml.load(it) }
        val root = rootAny as? Map<*, *> ?: throw IllegalArgumentException("YAML root must be a map.")

        val pluginSection = root["plugin"] as? Map<*, *>
        val webhookOverride = (pluginSection?.get("webhook") as? String)?.trim()?.lowercase()

        // Support both keys so you don't brick older test files.
        val payloadAny = root["discord-webhook"] ?: root["message"]
        val payloadMap = payloadAny as? Map<*, *> ?: throw IllegalArgumentException("Missing 'discord-webhook' map in message YAML.")

        val payload = toJsonSafeMap(payloadMap)

        // Enforce "ONLY 1 embed" rule
        val embeds = payload["embeds"]
        if (embeds is List<*> && embeds.size > 1) {
            throw IllegalArgumentException("Only 1 embed is supported right now.")
        }

        val content = payload["content"]
        if (content is String && content.length > 2000) {
            throw IllegalArgumentException("content is too long (Discord limit is 2000 characters).")
        }

        if (!usernameOverride.isNullOrBlank()) {
            payload["username"] = usernameOverride
        }

        val json = mapper.writeValueAsString(payload)
        require(json.length <= 50_000) { "Payload too large." }

        return BuildResult(webhookNameOverride = webhookOverride, json = json)
    }

    /**
     * Convert SnakeYAML output into a Map<String, Any?> containing only JSON-safe types:
     * - Map<String, *>, List<*>, String, Number, Boolean, null
     */
    private fun toJsonSafeMap(input: Map<*, *>): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in input.entries) {
            val key = (k as? String)?.trim()
                ?: throw IllegalArgumentException("Non-string key in YAML payload: $k")
            out[key] = toJsonSafeValue(v)
        }
        return out
    }

    private fun toJsonSafeValue(v: Any?): Any? = when (v) {
        null -> null
        is String -> v
        is Number -> v
        is Boolean -> v
        is Map<*, *> -> toJsonSafeMap(v)
        is List<*> -> v.map { toJsonSafeValue(it) }
        else -> throw IllegalArgumentException("Unsupported value type in YAML payload: ${v::class.java.name}")
    }
}
