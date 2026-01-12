package com.indishere.velocity_to_discord_webhook_plugin


import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


object DiscordWebhook {

    data class SendResult(
        val ok: Boolean,
        val status: Int,
        val body: String?
    )

    object Metrics {
        val webhooksSent = AtomicLong()
        val webhooksFailed = AtomicLong()
        val rateLimitHits = AtomicLong()
    }

    // Shared client. Java HttpClient isn't Closeable; the JVM will clean it up on process exit.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<SendResult>>()

    // Tiny circuit breaker per webhook URL (to avoid hammering Discord when it's down).
    private data class FailureState(
        val fails: AtomicInteger = AtomicInteger(0),
        @Volatile var cooldownUntilMs: Long = 0L
    )

    private val breaker = ConcurrentHashMap<String, FailureState>()

    fun looksLikeDiscordWebhookUrl(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("https://discord.com/api/webhooks/", ignoreCase = true) ||
            u.startsWith("https://discordapp.com/api/webhooks/", ignoreCase = true) ||
            u.startsWith("https://canary.discord.com/api/webhooks/", ignoreCase = true) ||
            u.startsWith("https://ptb.discord.com/api/webhooks/", ignoreCase = true)
    }

    private fun breakerAllows(url: String): Boolean {
        val state = breaker.computeIfAbsent(url) { FailureState() }
        val now = System.currentTimeMillis()
        return now >= state.cooldownUntilMs
    }

    private fun breakerOnResult(url: String, ok: Boolean) {
        val state = breaker.computeIfAbsent(url) { FailureState() }
        if (ok) {
            state.fails.set(0)
            state.cooldownUntilMs = 0L
            return
        }

        val fails = state.fails.incrementAndGet()
        if (fails >= 5) {
            // 60s cooldown after 5 consecutive failures
            state.cooldownUntilMs = System.currentTimeMillis() + 60_000L
        }
    }

    fun sendAsync(webhookUrl: String, jsonBody: String, logger: Logger): CompletableFuture<SendResult> {
        if (!looksLikeDiscordWebhookUrl(webhookUrl)) {
            return CompletableFuture.completedFuture(SendResult(ok = false, status = 400, body = "Blocked non-Discord webhook URL"))
        }

        if (!breakerAllows(webhookUrl)) {
            return CompletableFuture.completedFuture(SendResult(ok = false, status = 429, body = "Circuit breaker cooldown"))
        }

        val req = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("User-Agent", "VelocityDiscordWebhookPlugin/0.1")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .handle { resp, err ->
                if (err != null) {
                    logger.warn("Discord webhook request failed: {}", err.toString())
                    breakerOnResult(webhookUrl, ok = false)
                    return@handle SendResult(ok = false, status = -1, body = err.message)
                }

                val ok = resp.statusCode() == 200 || resp.statusCode() == 204
                breakerOnResult(webhookUrl, ok = ok)

                if (ok) {
                    logger.debug("Discord webhook sent OK. status={}", resp.statusCode())
                } else {
                    // Don't dump payload, but response body is fine (Discord usually returns JSON error)
                    logger.warn("Discord webhook failed. status={}, body={}", resp.statusCode(), resp.body())
                }

                SendResult(ok = ok, status = resp.statusCode(), body = resp.body())
            }

        pending.add(future)
        future.whenComplete { _, _ -> pending.remove(future) }

        return future
    }

    fun awaitPending(timeout: Long, unit: TimeUnit) {
        val snapshot = pending.toTypedArray()
        if (snapshot.isEmpty()) return

        CompletableFuture.allOf(*snapshot)
            .orTimeout(timeout, unit)
            .exceptionally { null }
            .join()
    }
}
