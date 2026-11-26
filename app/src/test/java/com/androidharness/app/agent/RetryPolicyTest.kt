package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    // --- HTTP code classification ---

    @Test
    fun `http retryable codes`() {
        assertTrue(RetryPolicy.isRetryableCode(408))
        assertTrue(RetryPolicy.isRetryableCode(429))
        assertTrue(RetryPolicy.isRetryableCode(500))
        assertTrue(RetryPolicy.isRetryableCode(502))
        assertTrue(RetryPolicy.isRetryableCode(503))
        assertTrue(RetryPolicy.isRetryableCode(599))
        assertFalse(RetryPolicy.isRetryableCode(400))
        assertFalse(RetryPolicy.isRetryableCode(401))
        assertFalse(RetryPolicy.isRetryableCode(403))
        assertFalse(RetryPolicy.isRetryableCode(404))
        assertFalse(RetryPolicy.isRetryableCode(422))
    }

    @Test
    fun `api exception classifies by code`() {
        assertTrue(RetryPolicy.isRetryable(ApiException(429, "rate limit"), null))
        assertFalse(RetryPolicy.isRetryable(ApiException(400, "bad request"), null))
    }

    @Test
    fun `message-only http-4xx text does not match transient regex`() {
        assertFalse(RetryPolicy.isRetryable(null, "HTTP 400: invalid request"))
        assertFalse(RetryPolicy.isRetryable(null, "HTTP 401: unauthorized"))
    }

    @Test
    fun `plain io exception is retryable`() {
        assertTrue(RetryPolicy.isRetryable(IOException("Connection reset by peer"), null))
        assertTrue(RetryPolicy.isRetryable(null, "Connection reset by peer"))
    }

    @Test
    fun `wrapped io exception is retryable`() {
        val wrapped = RuntimeException("stream failed", IOException("socket closed"))
        assertTrue(RetryPolicy.isRetryable(wrapped, "stream failed"))
    }

    @Test
    fun `wrapped api exception classifies by code not wrapper`() {
        val wrapped = RuntimeException("request failed", ApiException(500, "boom"))
        assertTrue(RetryPolicy.isRetryable(wrapped, null))
        val wrapped400 = RuntimeException("request failed", ApiException(400, "nope"))
        assertFalse(RetryPolicy.isRetryable(wrapped400, null))
    }

    @Test
    fun `transient in-stream messages are retryable`() {
        assertTrue(RetryPolicy.isRetryable(null, "The server is overloaded"))
        assertTrue(RetryPolicy.isRetryable(null, "Rate limit exceeded"))
        assertTrue(RetryPolicy.isRetryable(null, "Request timed out"))
    }

    @Test
    fun `permanent in-stream messages are not retryable`() {
        assertFalse(RetryPolicy.isRetryable(null, "Invalid API key provided"))
        assertFalse(RetryPolicy.isRetryable(null, "model not found"))
        assertFalse(RetryPolicy.isRetryable(null, null))
    }

    // --- Backoff ---

    @Test
    fun `backoff grows exponentially within bounds`() {
        // Jitter is +/-30% of base, so bounds are [base*0.7, base*1.3].
        // attempt 1: base=1000ms → [700, 1300]
        // attempt 2: base=2000ms → [1400, 2600]
        // attempt 3: base=4000ms → [2800, 5200]
        val d1 = RetryPolicy.delayMs(1)
        val d2 = RetryPolicy.delayMs(2)
        val d3 = RetryPolicy.delayMs(3)
        assertTrue("d1=$d1 should be in [700,1300]", d1 in 700..1300)
        assertTrue("d2=$d2 should be in [1400,2600]", d2 in 1400..2600)
        assertTrue("d3=$d3 should be in [2800,5200]", d3 in 2800..5200)
        assertEquals(3, RetryPolicy.MAX_RETRIES)
    }

    // --- Header parsing: retry-after-ms ---

    @Test
    fun `retry-after-ms parsed and used`() {
        val headers = mapOf("retry-after-ms" to "5000")
        assertEquals(5000L, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    @Test
    fun `retry-after-ms zero returns zero`() {
        val headers = mapOf("retry-after-ms" to "0")
        assertEquals(0L, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    @Test
    fun `retry-after-ms negative falls back`() {
        val headers = mapOf("retry-after-ms" to "-100")
        // Negative → skip, no other headers → null
        assertEquals(null, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    @Test
    fun `retry-after-ms invalid falls back`() {
        val headers = mapOf("retry-after-ms" to "not-a-number")
        assertEquals(null, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    // --- Header parsing: Retry-After seconds ---

    @Test
    fun `retry-after seconds parsed`() {
        val headers = mapOf("retry-after" to "30")
        assertEquals(30_000L, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    @Test
    fun `retry-after negative falls back`() {
        val headers = mapOf("retry-after" to "-5")
        assertEquals(null, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    @Test
    fun `retry-after invalid falls back`() {
        val headers = mapOf("retry-after" to "abc")
        // Not a number, try HTTP-date parse → fails → null
        assertEquals(null, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    // --- Header parsing: x-ratelimit-reset ---

    @Test
    fun `x-ratelimit-reset future epoch seconds`() {
        val now = 1_000_000L
        val reset = (now / 1000L) + 60 // 60 seconds in the future
        val headers = mapOf("x-ratelimit-reset" to reset.toString())
        val result = RetryPolicy.parseRetryAfter(headers, now)
        assertEquals(60_000L, result)
    }

    @Test
    fun `x-ratelimit-reset past epoch returns zero`() {
        val now = 1_000_000L
        val reset = (now / 1000L) - 60 // 60 seconds in the past
        val headers = mapOf("x-ratelimit-reset" to reset.toString())
        val result = RetryPolicy.parseRetryAfter(headers, now)
        assertEquals(0L, result)
    }

    @Test
    fun `x-ratelimit-reset negative returns zero`() {
        val headers = mapOf("x-ratelimit-reset" to "-100")
        val result = RetryPolicy.parseRetryAfter(headers, 1_000_000L)
        // Negative epoch → treated as past → 0
        assertEquals(0L, result)
    }

    @Test
    fun `x-ratelimit-reset millis epoch`() {
        val now = 1_000_000_000L
        val resetMs = now + 30_000L // 30s in the future (millis)
        val headers = mapOf("x-ratelimit-reset" to resetMs.toString())
        val result = RetryPolicy.parseRetryAfter(headers, now)
        assertEquals(30_000L, result)
    }

    // --- Header parsing: missing ---

    @Test
    fun `missing headers returns null`() {
        assertEquals(null, RetryPolicy.parseRetryAfter(emptyMap(), 1000L))
    }

    // --- Header parsing: priority ---

    @Test
    fun `retry-after-ms takes priority over retry-after`() {
        val headers = mapOf(
            "retry-after-ms" to "1000",
            "retry-after" to "30",
        )
        assertEquals(1000L, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    @Test
    fun `retry-after seconds takes priority over x-ratelimit-reset`() {
        val now = 1_000_000L
        val headers = mapOf(
            "retry-after" to "10",
            "x-ratelimit-reset" to ((now / 1000L) + 60).toString(),
        )
        assertEquals(10_000L, RetryPolicy.parseRetryAfter(headers, now))
    }

    // --- HTTP-date parsing ---

    @Test
    fun `http-date imf-fixdate parsed`() {
        // Sun, 06 Nov 1994 08:49:37 GMT → fixed future time
        val now = 0L
        val future = 1_000_000_000_000L // arbitrary future
        val headers = mapOf("retry-after" to "Sun, 06 Nov 1994 08:49:37 GMT")
        // This date is in the past → should return 0 (not negative)
        val result = RetryPolicy.parseRetryAfter(headers, future)
        assertEquals(0L, result)
    }

    @Test
    fun `http-date rfc850 parsed`() {
        val future = 1_000_000_000_000L
        val headers = mapOf("retry-after" to "Sunday, 06-Nov-94 08:49:37 GMT")
        val result = RetryPolicy.parseRetryAfter(headers, future)
        assertEquals(0L, result)
    }

    @Test
    fun `http-date asctime parsed`() {
        val future = 1_000_000_000_000L
        val headers = mapOf("retry-after" to "Sun Nov  6 08:49:37 1994")
        val result = RetryPolicy.parseRetryAfter(headers, future)
        assertEquals(0L, result)
    }

    @Test
    fun `http-date future returns positive delay`() {
        // Use a date far in the future
        val now = System.currentTimeMillis()
        val futureDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120)
        val formatted = futureDate.format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val headers = mapOf("retry-after" to formatted)
        val result = RetryPolicy.parseRetryAfter(headers, now)
        // Should be approximately 120s (allow some tolerance)
        assertTrue("result=$result should be > 0", result != null && result > 0)
        assertTrue("result=$result should be <= 120000", result!! <= 120_000L)
    }

    @Test
    fun `http-date invalid returns null`() {
        val headers = mapOf("retry-after" to "not a date at all")
        assertEquals(null, RetryPolicy.parseRetryAfter(headers, 1000L))
    }

    // --- delayMs with headers ---

    @Test
    fun `delayMs with headers uses parsed value`() {
        val headers = mapOf("retry-after-ms" to "2500")
        assertEquals(2500L, RetryPolicy.delayMs(1, headers, 1000L))
    }

    @Test
    fun `delayMs with invalid headers falls back to backoff`() {
        val headers = mapOf("retry-after" to "garbage")
        val result = RetryPolicy.delayMs(1, headers, 1000L)
        // Should fall back to backoff range [700, 1300]
        assertTrue("result=$result", result in 700..1300)
    }

    @Test
    fun `delayMs with empty headers falls back to backoff`() {
        val result = RetryPolicy.delayMs(1, emptyMap(), 1000L)
        assertTrue("result=$result", result in 700..1300)
    }
}
