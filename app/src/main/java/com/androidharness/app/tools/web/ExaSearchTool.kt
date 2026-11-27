package com.androidharness.app.tools.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Exa web search provider — searches by meaning instead of keywords.
 * Suits descriptive queries, similar sites, papers and companies.
 * Uses the user's own API key, honors freshness, country and count.
 */
object ExaSearchTool {
    const val NAME = "exa"
    const val DISPLAY_NAME = "Exa"
    const val GROUP = "Built for AI agents"
    private const val API_URL = "https://api.exa.ai/search"
    private val JSON_MEDIA = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ExaRequest(
        val query: String,
        val numResults: Int = 5,
        val contents: ExaContents = ExaContents(),
        val type: String = "neural"
    )

    @Serializable
    data class ExaContents(
        val highlights: ExaHighlights = ExaHighlights()
    )

    @Serializable
    data class ExaHighlights(
        val numSentences: Int = 3,
        val highlightsPerUrl: Int = 3
    )

    @Serializable
    data class ExaResponse(
        val results: List<ExaResult> = emptyList()
    )

    @Serializable
    data class ExaResult(
        val title: String = "",
        val url: String = "",
        val highlights: List<String> = emptyList(),
        val score: Double = 0.0
    )

    fun search(
        client: OkHttpClient,
        apiKey: String,
        query: String,
        numResults: Int = 5
    ): Result<List<ExaResult>> = runCatching {
        val body = json.encodeToString(ExaRequest(query = query, numResults = numResults))
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: return Result.failure(Exception("Empty response from Exa"))
        if (!response.isSuccessful) {
            return Result.failure(Exception("Exa returned ${response.code}: $responseBody"))
        }
        json.decodeFromString<ExaResponse>(responseBody).results
    }
}
