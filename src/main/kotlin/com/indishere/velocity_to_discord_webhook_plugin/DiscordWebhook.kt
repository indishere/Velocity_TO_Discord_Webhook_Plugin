/* Suffering with Java HTTP Client w/ Discord REST API @ Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin


import org.slf4j.Logger

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


class DiscordWebhook(private val userAgent: String) {

    data class SendResult(
        val ok: Boolean,
        val status: Int,
        val body: String?
    )

    class Metrics {
        val webhooksSent = AtomicLong()
        val webhooksFailed = AtomicLong()
        val rateLimitHits = AtomicLong()
    }

    val metrics = Metrics()

    @Volatile private var client: HttpClient? = null
    @Volatile private var clientExecutor: ScheduledExecutorService? = null
    @Volatile private var breakerCleanupTask: ScheduledFuture<*>? = null

    companion object {
        private const val FAILURE_DECAY_MS = 300_000L
        private const val BREAKER_TTL_MS = 600_000L
        private const val BREAKER_CLEANUP_INTERVAL_MS = 300_000L
        private const val BREAKER_FAIL_THRESHOLD = 5
        private const val MAX_PENDING = 100
    }

    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<SendResult>>()
    private val lastBreakerCleanup = AtomicLong(0L)

    private data class FailureState(
        val fails: AtomicInteger = AtomicInteger(0),
        @Volatile var cooldownUntilMs: Long = 0L,
        @Volatile var lastFailureTime: Long = 0L,
        @Volatile var lastUsedMs: Long = 0L
    )

    private val breaker = ConcurrentHashMap<String, FailureState>()

    fun looksLikeDiscordWebhookUrl(url: String): Boolean {
        if (url.length > 2048) return false
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return false

        val uri = try {
            URI(u)
        } catch (_: Exception) {
            return false
        }

        val host = uri.host?.lowercase() ?: return false
        val path = uri.path ?: return false

        val allowedHosts = setOf(
            "discord.com",
            "discordapp.com",
            "canary.discord.com",
            "ptb.discord.com"
        )

        if (host !in allowedHosts) return false
        if (!path.startsWith("/api/webhooks/")) return false

        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.any { it == ".." }) return false
        return segments.size >= 4
    }

    private fun httpExecutor(): ScheduledExecutorService {
        return clientExecutor ?: synchronized(this) {
            clientExecutor ?: ScheduledThreadPoolExecutor(
                4,
                ThreadFactory { r ->
                    Thread(r, "vdiscord-http-${System.identityHashCode(r)}").apply {
                        isDaemon = true
                    }
                }
            ).apply {
                removeOnCancelPolicy = true
            }.also {
                clientExecutor = it
                scheduleBreakerCleanup(it)
            }
        }
    }

    private fun getClient(): HttpClient {
        return client ?: synchronized(this) {
            client ?: HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(httpExecutor())
                .build()
                .also { client = it }
        }
    }

    private fun scheduleBreakerCleanup(executor: ScheduledExecutorService) {
        breakerCleanupTask?.cancel(false)
        breakerCleanupTask = executor.scheduleAtFixedRate(
            { cleanupBreaker(System.currentTimeMillis()) },
            BREAKER_CLEANUP_INTERVAL_MS,
            BREAKER_CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun maybeCleanupBreaker(now: Long) {
        val last = lastBreakerCleanup.get()
        if (now - last < BREAKER_CLEANUP_INTERVAL_MS) return
        if (!lastBreakerCleanup.compareAndSet(last, now)) return
        cleanupBreaker(now)
    }

    private fun breakerAllows(url: String): Boolean {
        val state = breaker.computeIfAbsent(url) { FailureState() }
        val now = System.currentTimeMillis()
        maybeCleanupBreaker(now)
        state.lastUsedMs = now
        return now >= state.cooldownUntilMs
    }

    private fun breakerOnResult(url: String, ok: Boolean) {
        val state = breaker.computeIfAbsent(url) { FailureState() }
        val now = System.currentTimeMillis()
        state.lastUsedMs = now

        if (ok) {
            state.fails.set(0)
            state.cooldownUntilMs = 0L
            return
        }

        if (now - state.lastFailureTime > FAILURE_DECAY_MS) {
            state.fails.set(0)
        }

        state.lastFailureTime = now
        val fails = state.fails.incrementAndGet()
        if (fails >= BREAKER_FAIL_THRESHOLD) {
            state.cooldownUntilMs = System.currentTimeMillis() + 60_000L
        }
    }

    private fun cleanupBreaker(now: Long) {
        val toRemove = breaker.entries
            .filter { (_, state) ->
                val idleLongEnough = (now - state.lastUsedMs) > BREAKER_TTL_MS
                val cooldownDone = now >= state.cooldownUntilMs
                state.fails.get() == 0 && cooldownDone && idleLongEnough
            }
            .map { it.key }

        toRemove.forEach { breaker.remove(it) }
    }

    fun cancelAll() {
        val snapshot = pending.toTypedArray()
        snapshot.forEach { it.cancel(true) }
        pending.clear()
    }

    fun pendingCount(): Int = pending.size

    fun shutdown() {
        cancelAll()
        synchronized(this) {
            client = null
            breakerCleanupTask?.cancel(true)
            breakerCleanupTask = null
            clientExecutor?.shutdownNow()
            clientExecutor = null
        }
        breaker.clear()
    }

    fun sendAsync(webhookUrl: String, jsonBody: String, logger: Logger): CompletableFuture<SendResult> {
        if (!looksLikeDiscordWebhookUrl(webhookUrl)) {
            return CompletableFuture.completedFuture(SendResult(ok = false, status = 400, body = "Blocked non-Discord webhook URL"))
        }

        if (!breakerAllows(webhookUrl)) {
            return CompletableFuture.completedFuture(SendResult(ok = false, status = 429, body = "Circuit breaker cooldown"))
        }

        if (pending.size >= MAX_PENDING) {
            return CompletableFuture.completedFuture(
                SendResult(
                    ok = false,
                    status = 429,
                    body = "Too many pending webhook requests"
                )
            )
        }

        val req = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", userAgent)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build()

        val future = getClient().sendAsync(req, HttpResponse.BodyHandlers.ofString())
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
