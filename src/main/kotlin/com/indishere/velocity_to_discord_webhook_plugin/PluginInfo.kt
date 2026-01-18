package com.indishere.velocity_to_discord_webhook_plugin

import com.google.gson.Gson
import java.nio.charset.StandardCharsets

object PluginInfo {
    private const val DEFAULT_VERSION = "unknown"
    private const val RESOURCE_NAME = "velocity-plugin.json"

    val version: String by lazy { loadVersion() }
    val userAgent: String by lazy { "VelocityDiscordWebhookPlugin/$version" }

    private fun loadVersion(): String {
        val stream = PluginInfo::class.java.classLoader.getResourceAsStream(RESOURCE_NAME)
            ?: return DEFAULT_VERSION

        return try {
            val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val json = Gson().fromJson(text, Map::class.java)
            val value = json["version"] as? String
            value?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VERSION
        } catch (_: Exception) {
            DEFAULT_VERSION
        }
    }
}
