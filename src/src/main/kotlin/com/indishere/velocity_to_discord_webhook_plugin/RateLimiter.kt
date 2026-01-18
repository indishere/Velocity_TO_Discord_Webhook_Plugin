/* Rate Limiter @ Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin


import org.slf4j.Logger
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong


/**
 * Config-backed rate limiter that reads limits from config.yml
 * and applies them per-user.
 */


class ConfigBackedRateLimiter(
    private val dataDirectory: Path,
    private val logger: Logger,
    private val defaultPlayersPerMinute: Int,
    private val defaultConsolePerMinute: Int
) {
    @Volatile private var lastMtime: Long = -1L
    private val current = java.util.concurrent.atomic.AtomicReference(
        RateLimiter(defaultPlayersPerMinute, defaultConsolePerMinute)
    )

    fun current(): RateLimiter {
        refreshIfChanged()
        return current.get()
    }

    fun forceRefresh() {
        lastMtime = -1L
        refreshIfChanged()
    }

    fun currentLimits(): Pair<Int, Int> = current().snapshotLimits()

    private fun refreshIfChanged() {
        synchronized(this) {
            val configPath = dataDirectory.resolve("config.yml")
            if (!Files.exists(configPath)) return

            val mtime = try {
                Files.getLastModifiedTime(configPath).toMillis()
            } catch (_: Exception) {
                return
            }

            if (mtime == lastMtime) return
            lastMtime = mtime

            val limits = tryReadLimits(configPath) ?: return

            val players = sanitizeLimit(limits.playersPerMinute, defaultPlayersPerMinute)
            val console = sanitizeLimit(limits.consolePerMinute, defaultConsolePerMinute)

            val old = current.get().snapshotLimits()
            if (old.first != players || old.second != console) {
                current.set(RateLimiter(players, console))
                logger.info("RateLimiter updated from config.yml: players={} /min, console={} /min", players, console)
            }
        }
    }

    private fun sanitizeLimit(value: Int?, fallback: Int): Int {
        val v = value ?: fallback
        return if (v <= 0) Int.MAX_VALUE else v
    }

    private data class Limits(val playersPerMinute: Int?, val consolePerMinute: Int?)

    private fun tryReadLimits(configPath: Path): Limits? {
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 20
            codePointLimit = 50_000
        }
        val yaml = Yaml(SafeConstructor(loaderOptions))

        return try {
            val rootAny = Files.newInputStream(configPath).use { yaml.load<Any?>(it) }
            val root = rootAny as? Map<*, *> ?: return null

            val pluginSection = root["plugin"] as? Map<*, *>
            val rl = pluginSection?.get("ratelimiter") as? Map<*, *>

            val players = (rl?.get("players") as? Number)?.toInt()
            val console = (rl?.get("console") as? Number)?.toInt()

            Limits(playersPerMinute = players, consolePerMinute = console)
        } catch (e: Exception) {
            logger.warn("Failed to read plugin.ratelimiter from config.yml (keeping previous limiter): {}", e.message)
            null
        }
    }
}

/**
 * Sliding window rate limiter.
 */
class RateLimiter(
    private val playerMaxPerMinute: Int,
    private val consoleMaxPerMinute: Int
) {
    private val windows = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val lastCleanup = AtomicLong(0L)

    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        cleanupStale(now)

        val limit = if (key == "CONSOLE") consoleMaxPerMinute else playerMaxPerMinute
        if (limit == Int.MAX_VALUE) {
            windows.remove(key)
            return true
        }

        val window = windows.computeIfAbsent(key) { ConcurrentLinkedQueue() }

        while (true) {
            val head = window.peek() ?: break
            if (now - head > 60_000) window.poll() else break
        }

        val allowed = if (window.size < limit) {
            window.offer(now)
        } else {
            false
        }

        if (window.isEmpty()) {
            windows.remove(key, window)
        }

        return allowed
    }

    private fun cleanupStale(now: Long) {
        val last = lastCleanup.get()
        if (now - last < 300_000) return
        if (!lastCleanup.compareAndSet(last, now)) return

        val expireBefore = now - 120_000
        val toRemove = windows.entries
            .filter { (_, queue) ->
                val head = queue.peek()
                head == null || head < expireBefore || queue.isEmpty()
            }
            .map { it.key }

        toRemove.forEach { windows.remove(it) }
    }

    fun snapshotLimits(): Pair<Int, Int> {
        return playerMaxPerMinute to consoleMaxPerMinute
    }
}
