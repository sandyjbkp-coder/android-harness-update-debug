package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamRetrierTest {

    private val noopEmit: suspend (AgentEvent.Retrying) -> Unit = {}

    @Test
    fun `clean stream returns null and forwards all events`() = runTest {
        val seen = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = {
                flowOf(
                    StreamEvent.TextDelta("hi"),
                    StreamEvent.Done("stop"),
                )
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = { seen += it },
            retryReason = { it },
            emitEvent = noopEmit,
        )
        assertEquals(null, result)
        assertEquals(2, seen.size)
        assertTrue(seen[0] is StreamEvent.TextDelta)
        assertEquals("stop", (seen[1] as StreamEvent.Done).finishReason)
    }

    @Test
    fun `transient in-stream failure retries and succeeds`() = runTest {
        var attempt = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flowOf(StreamEvent.Failure("server overloaded"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals(1, slept.size)
        assertTrue(slept[0] in 700..1300) // backoff jitter bounds
    }

    @Test
    fun `retry resets buffers via onAttemptStart`() = runTest {
        var attempt = 0
        var resets = 0
        var lastText = ""
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flowOf(StreamEvent.Failure("timed out"))
                    else flowOf(StreamEvent.TextDelta("second try"))
                }
            },
            onAttemptStart = {
                resets++
                lastText = ""
            },
            hasOutput = { lastText.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.TextDelta) lastText = it.text },
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(null, result)
        assertEquals(2, resets)
        assertEquals("second try", lastText)
    }

    @Test
    fun `stalled stream is treated as transient failure`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                if (builds == 1) flow<StreamEvent> { kotlinx.coroutines.awaitCancellation() }
                else flowOf(StreamEvent.TextDelta("recovered"))
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
            stallTimeoutMs = 150,
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals(StreamRetrier.STALLED_STREAM_MESSAGE, retries[0].reason)
    }

    @Test
    fun `partial output before failure prevents retry`() = runTest {
        var attempt = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val deltasSeen = mutableListOf<String>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flow {
                        emit(StreamEvent.TextDelta("partial"))
                        emit(StreamEvent.Failure("boom"))
                    } else flowOf(StreamEvent.TextDelta("never"))
                }
            },
            onAttemptStart = {},
            hasOutput = { deltasSeen.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.TextDelta) deltasSeen += it.text },
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
        )
        assertEquals("boom", result)
        assertTrue(retries.isEmpty())
        assertEquals(listOf("partial"), deltasSeen)
    }

    @Test
    fun `429 with Retry-After uses server delay`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited", mapOf("retry-after" to "30"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, slept.size)
        assertEquals(30_000L, slept[0])
        assertEquals(30_000L, retries[0].delayMs)
    }

    @Test
    fun `retry-after-ms has priority over retry-after`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited", mapOf(
                        "retry-after-ms" to "500",
                        "retry-after" to "30",
                    ))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(500L, slept[0])
    }

    @Test
    fun `x-ratelimit-reset used when appropriate`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        val resetEpoch = (now / 1000L) + 10 // 10 seconds future
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited", mapOf("x-ratelimit-reset" to resetEpoch.toString()))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertTrue("slept=${slept[0]}", slept[0] in 9000..11000)
    }

    @Test
    fun `404 model_not_found is terminal and not retried`() = runTest {
        var attempt = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                attempt++
                throw ApiException(404, "model not found")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
        )
        assertTrue(result?.contains("404") == true || result?.contains("not found") == true)
        assertTrue(retries.isEmpty())
        assertEquals(1, attempt) // only one attempt
    }

    @Test
    fun `no retry after terminal error`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                attempt++
                throw ApiException(401, "unauthorized")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(1, attempt)
    }

    @Test
    fun `no events emitted after terminal failure`() = runTest {
        val seen = mutableListOf<StreamEvent>()
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                flow {
                    emit(StreamEvent.TextDelta("partial"))
                    throw ApiException(403, "forbidden")
                }
            },
            onAttemptStart = {},
            hasOutput = { seen.isNotEmpty() },
            handleEvent = { seen += it },
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
        )
        // hasOutput is true → no retry → terminal
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `429 without Retry-After uses backoff`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited")
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertTrue("slept=${slept[0]}", slept[0] in 700..1300)
    }

    @Test
    fun `429 with invalid Retry-After uses backoff`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited", mapOf("retry-after" to "not-a-number"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertTrue("slept=${slept[0]}", slept[0] in 700..1300)
    }

    @Test
    fun `retry-after-ms zero uses zero delay`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited", mapOf("retry-after-ms" to "0"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(0L, slept[0])
    }

    @Test
    fun `negative reset value uses zero delay`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(429, "rate limited", mapOf("x-ratelimit-reset" to "-100"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(0L, slept[0])
    }

    @Test
    fun `500 retryable error retries`() = runTest {
        var attempt = 0
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(500, "server error")
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, slept.size)
    }

    @Test
    fun `timeout retryable error retries`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw IOException("connection timed out")
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(null, result)
    }

    @Test
    fun `ioexception retryable error retries`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw IOException("socket closed")
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(null, result)
    }

    @Test
    fun `401 non-retryable`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                attempt++
                throw ApiException(401, "unauthorized")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertTrue(result?.contains("401") == true)
        assertEquals(1, attempt)
    }

    @Test
    fun `403 non-retryable`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                attempt++
                throw ApiException(403, "forbidden")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertTrue(result?.contains("403") == true)
        assertEquals(1, attempt)
    }

    @Test
    fun `retry limit exhaustion`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                attempt++
                throw IOException("connection reset")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        // MAX_RETRIES=3, so 1 initial + 3 retries = 4 attempts
        assertEquals(4, attempt)
        assertTrue(result?.contains("connection reset") == true)
    }

    @Test
    fun `stream partial output before failure no retry`() = runTest {
        val seen = mutableListOf<StreamEvent>()
        val retries = mutableListOf<AgentEvent.Retrying>()
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                attempt++
                flow {
                    emit(StreamEvent.TextDelta("partial data"))
                    throw IOException("network drop")
                }
            },
            onAttemptStart = {},
            hasOutput = { seen.isNotEmpty() },
            handleEvent = { seen += it },
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
        )
        assertTrue(retries.isEmpty())
        assertEquals(1, attempt)
    }

    @Test
    fun `503 retryable error retries`() = runTest {
        var attempt = 0
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) throw ApiException(503, "service unavailable")
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(null, result)
    }
}
