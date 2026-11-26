package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Decides which provider failures are worth retrying and how long to wait.
 * Transient conditions only, rate limits, server overload, network drops.
 * Permanent errors (bad request, auth, not found) surface to the user at once.
 */
internal object RetryPolicy {
    const val MAX_RETRIES = 3
    internal const val BASE_DELAY_MS = 1_000L
    internal const val JITTER_FRACTION = 0.3

    private val TRANSIENT_MESSAGE = Regex(
        "overloaded|rate.?limit|timeout|timed out|temporarily|try again|" +
            "connection reset|connection refused|ECONNRESET|socket closed",
        RegexOption.IGNORE_CASE,
    )

    /** HTTP codes worth another attempt: request timeout, rate limit, server errors. */
    fun isRetryableCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /**
     * Classifies a failure from its (optional) thrown cause and (optional)
     * in-stream message. [ApiException] is checked before [IOException]
     * because it subclasses it, an HTTP 400 must not retry even though it
     * arrives as an IOException.
     */
    fun isRetryable(cause: Throwable?, message: String?): Boolean {
        var c = cause
        while (c != null) {
            if (c is ApiException) return isRetryableCode(c.code)
            if (c is IOException) return true
            c = c.cause
        }
        return message != null && TRANSIENT_MESSAGE.containsMatchIn(message)
    }

    /** Exponential backoff 1s / 2s / 4s with +/-30% jitter. [attempt] is 1-based. */
    fun delayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 4)
        val base = BASE_DELAY_MS shl exp
        val jitter = (base * JITTER_FRACTION * (Math.random() * 2 - 1)).toLong()
        return base + jitter
    }

    /**
     * Parses retry delay from HTTP response headers.
     * Priority order:
     *   1. retry-after-ms (most precise, milliseconds)
     *   2. Retry-After (delay-seconds)
     *   3. x-ratelimit-reset (future epoch timestamp → remaining ms)
     *   4. Retry-After (HTTP-date) → remaining ms from now
     *   5. null (caller falls back to exponential backoff)
     *
     * Returns null if no header present or all parsing fails.
     * Never returns negative; past timestamps yield 0.
     * [nowMs] is the response receipt time (injected for testability).
     */
    fun parseRetryAfter(headers: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Long? {
        // 1. retry-after-ms (highest priority, most precise)
        headers["retry-after-ms"]?.trim()?.toLongOrNull()?.let { ms ->
            if (ms >= 0) return ms
        }

        // 2. Retry-After as delay-seconds
        headers["retry-after"]?.trim()?.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return seconds * 1000L
        }

        // 3 & 4. Retry-After as HTTP-date OR x-ratelimit-reset as epoch
        // Try HTTP-date first (Retry-After header can be a date)
        headers["retry-after"]?.trim()?.let { value ->
            parseHttpDate(value, nowMs)?.let { delay ->
                return delay.coerceAtLeast(0)
            }
        }

        // x-ratelimit-reset: could be epoch seconds or epoch millis
        headers["x-ratelimit-reset"]?.trim()?.let { value ->
            val epoch = value.toLongOrNull()
            if (epoch != null) {
                // Heuristic: if > 1e12, treat as millis; else seconds
                val resetMs = if (epoch > 1_000_000_000_000L) epoch else epoch * 1000L
                val remaining = resetMs - nowMs
                if (remaining >= 0) return remaining
                return 0L // past → 0 delay
            }
        }

        return null
    }

    /**
     * Header-aware delay: uses parsed retry-after if available,
     * otherwise falls back to exponential backoff with jitter.
     */
    fun delayMs(attempt: Int, headers: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Long {
        val parsed = parseRetryAfter(headers, nowMs)
        return parsed ?: delayMs(attempt)
    }

    /**
     * Parses an HTTP-date (RFC 9110) into a delay in ms from [nowMs].
     * Supports IMF-fixdate, RFC 850, and asctime formats.
     * Returns null if unparseable.
     */
    internal fun parseHttpDate(value: String, nowMs: Long): Long? {
        val v = value.trim()
        // IMF-fixdate and RFC 850 (both include zone)
        val zonedFormats = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME, // IMF-fixdate: Sun, 06 Nov 1994 08:49:37 GMT
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss z"), // RFC 850: Sunday, 06-Nov-94 08:49:37 GMT
        )
        for (fmt in zonedFormats) {
            try {
                val zdt = ZonedDateTime.parse(v, fmt)
                return (zdt.toInstant().toEpochMilli() - nowMs).coerceAtLeast(0)
            } catch (_: DateTimeParseException) {
                // try next format
            }
        }
        // asctime (no zone → assume GMT): Sun Nov  6 08:49:37 1994
        val asctimeFormats = listOf(
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy"),
            DateTimeFormatter.ofPattern("EEE MMM  d HH:mm:ss yyyy"),
        )
        for (fmt in asctimeFormats) {
            try {
                val ldt = java.time.LocalDateTime.parse(v, fmt)
                val zdt = ldt.atZone(java.time.ZoneOffset.UTC)
                return (zdt.toInstant().toEpochMilli() - nowMs).coerceAtLeast(0)
            } catch (_: DateTimeParseException) {
                // try next format
            }
        }
        return null
    }
}
