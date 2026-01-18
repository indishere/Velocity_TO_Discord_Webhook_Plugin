package com.indishere.velocity_to_discord_webhook_plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserAndUrlTest {

    @Test
    fun `parses escaped quotes in flags`() {
        val parser = CommandParser()
        val cmd = parser.parse(arrayOf("send", "from", "\"John\\\"Doe\"", "\"Hello world\"")) as CommandParser.ParsedCommand.Send

        assertEquals("John\"Doe", cmd.from)
        assertEquals("RAW:Hello world", cmd.messageToken)
    }

    @Test
    fun `rejects unclosed quotes`() {
        val parser = CommandParser()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(arrayOf("send", "from", "\"John Doe", "message"))
        }
    }

    @Test
    fun `validates discord webhook url strictly`() {
        val webhook = DiscordWebhook("test-agent")
        assertTrue(webhook.looksLikeDiscordWebhookUrl("https://discord.com/api/webhooks/123/abc"))
        assertFalse(webhook.looksLikeDiscordWebhookUrl("https://discord.com/api/webhooks/../../admin"))
        assertFalse(webhook.looksLikeDiscordWebhookUrl("https://evil.example/api/webhooks/123/abc"))
    }
}
