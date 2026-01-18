/* YAML to JSON Converting Facility, Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin


import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

import com.google.gson.GsonBuilder
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor


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

    data class ParsedMessage(
        val webhookNameOverride: String?,
        val payload: MutableMap<String, Any?>
    )

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private const val MAX_JSON_BYTES = 50_000
    const val MAX_MESSAGE_FILE_BYTES: Long = 1_048_576L

    fun maxJsonBytes(): Int = MAX_JSON_BYTES

    private fun newSafeYaml(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 20
            codePointLimit = 50_000
        }
        return Yaml(SafeConstructor(loaderOptions))
    }

    fun buildFromMessageFile(messageFile: Path, usernameOverride: String?): BuildResult {
        val parsed = parseMessageFile(messageFile)
        return buildFromParsed(parsed, usernameOverride)
    }

    fun buildFromRawContent(content: String, usernameOverride: String?): BuildResult {
        val payload = linkedMapOf<String, Any?>(
            "content" to content
        )

        if (!usernameOverride.isNullOrBlank()) {
            payload["username"] = usernameOverride
        }

        enforceAllowedMentions(payload)
        validateUrls(payload)

        val json = gson.toJson(payload)
        requireJsonWithinLimit(json)

        return BuildResult(
            webhookNameOverride = null,
            json = json
        )
    }

    fun parseMessageFile(messageFile: Path): ParsedMessage {
        val size = Files.size(messageFile)
        require(size <= MAX_MESSAGE_FILE_BYTES) {
            "Message file too large ($size bytes). Limit: $MAX_MESSAGE_FILE_BYTES"
        }

        val yaml = newSafeYaml()

        val rootAny: Any? = Files.newInputStream(messageFile).use { yaml.load(it) }
        val root = rootAny as? Map<*, *>
            ?: throw IllegalArgumentException("YAML root must be a map.")

        val pluginSection = root["plugin"] as? Map<*, *>
        val webhookOverride =
            (pluginSection?.get("webhook") as? String)?.trim()?.lowercase()

        // Support both keys so older test files still work
        val payloadAny = root["discord-webhook"] ?: root["message"]
        val payloadMap = payloadAny as? Map<*, *>
            ?: throw IllegalArgumentException(
                "Missing 'discord-webhook' map in message YAML."
            )

        val payload = toJsonSafeMap(payloadMap)

        return ParsedMessage(
            webhookNameOverride = webhookOverride,
            payload = payload
        )
    }

    fun buildFromParsed(parsed: ParsedMessage, usernameOverride: String?): BuildResult {
        val payload = linkedMapOf<String, Any?>().apply {
            putAll(parsed.payload)
        }

        if (!usernameOverride.isNullOrBlank()) {
            payload["username"] = usernameOverride
        }

        enforceAllowedMentions(payload)
        validateDiscordLimits(payload)
        validateUrls(payload)

        val json = gson.toJson(payload)
        requireJsonWithinLimit(json)

        return BuildResult(
            webhookNameOverride = parsed.webhookNameOverride,
            json = json
        )
    }

    /**
     * Convert SnakeYAML output into a Map<String, Any?> containing only JSON-safe types:
     * - Map<String, *>, List<*>, String, Number, Boolean, null
     */
    private fun toJsonSafeMap(input: Map<*, *>): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in input.entries) {
            val key = (k as? String)?.trim()
                ?: throw IllegalArgumentException(
                    "Non-string key in YAML payload: $k"
                )
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
        else -> throw IllegalArgumentException(
            "Unsupported value type in YAML payload: ${v::class.java.name}"
        )
    }

    private fun enforceAllowedMentions(payload: MutableMap<String, Any?>) {
        if (!payload.containsKey("allowed_mentions")) {
            payload["allowed_mentions"] = mapOf("parse" to emptyList<String>())
        }
    }

    private fun validateDiscordLimits(payload: Map<String, Any?>) {
        (payload["content"] as? String)?.let {
            require(it.length <= 2000) { "content is too long (Discord limit is 2000 characters)." }
        }

        val embeds = payload["embeds"] as? List<*>
        if (embeds != null) {
            require(embeds.size <= 1) { "Only 1 embed is supported right now." }

            var totalEmbedChars = 0
            embeds.forEach { embedAny ->
                val embed = embedAny as? Map<*, *> ?: return@forEach

                (embed["title"] as? String)?.let {
                    require(it.length <= 256) { "Embed title too long (max 256)." }
                    totalEmbedChars += it.length
                }
                (embed["description"] as? String)?.let {
                    require(it.length <= 4096) { "Embed description too long (max 4096)." }
                    totalEmbedChars += it.length
                }
                (embed["fields"] as? List<*>)?.let { fields ->
                    require(fields.size <= 25) { "Too many fields (max 25)." }
                    fields.forEach { fieldAny ->
                        val field = fieldAny as? Map<*, *> ?: return@forEach
                        (field["name"] as? String)?.let { name ->
                            require(name.length <= 256) { "Field name too long (max 256)." }
                            totalEmbedChars += name.length
                        }
                        (field["value"] as? String)?.let { value ->
                            require(value.length <= 1024) { "Field value too long (max 1024)." }
                            totalEmbedChars += value.length
                        }
                    }
                }
                (embed["footer"] as? Map<*, *>)?.let { footer ->
                    (footer["text"] as? String)?.let { text ->
                        require(text.length <= 2048) { "Footer text too long (max 2048)." }
                        totalEmbedChars += text.length
                    }
                }
                (embed["author"] as? Map<*, *>)?.let { author ->
                    (author["name"] as? String)?.let { name ->
                        require(name.length <= 256) { "Author name too long (max 256)." }
                        totalEmbedChars += name.length
                    }
                }
            }

            require(totalEmbedChars <= 6000) { "Embed content too long (max 6000 characters across embeds)." }
        }
    }

    private fun requireJsonWithinLimit(json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Payload too large (${bytes.size} bytes)." }
    }

    private fun isValidUrl(value: String): Boolean {
        return try {
            val uri = java.net.URI(value)
            uri.scheme == "http" || uri.scheme == "https"
        } catch (_: Exception) {
            false
        }
    }

    private fun validateUrls(payload: Map<String, Any?>) {
        fun check(value: Any?, field: String) {
            if (value is String && !isValidUrl(value)) {
                throw IllegalArgumentException("Invalid URL in field '$field': $value")
            }
        }

        check(payload["avatar_url"], "avatar_url")

        val embeds = payload["embeds"] as? List<*> ?: return
        val embed = embeds.firstOrNull() as? Map<*, *> ?: return

        check(embed["url"], "embeds[0].url")
        check((embed["image"] as? Map<*, *>)?.get("url"), "embeds[0].image.url")
        check((embed["thumbnail"] as? Map<*, *>)?.get("url"), "embeds[0].thumbnail.url")
        check((embed["footer"] as? Map<*, *>)?.get("icon_url"), "embeds[0].footer.icon_url")
        check((embed["author"] as? Map<*, *>)?.get("icon_url"), "embeds[0].author.icon_url")
    }
}
