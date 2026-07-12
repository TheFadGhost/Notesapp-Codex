package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.net.ChatMessage
import com.fadghost.notesapp.data.ai.net.ChatRequest
import com.fadghost.notesapp.data.ai.net.OpenRouterClient
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.net.ReasoningRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OpenRouterClientTest {

    // Mirror the production Json (AiModule.provideJson): encodeDefaults=true is what keeps
    // default-valued request flags (usage.include, reasoning.exclude) on the wire.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true; encodeDefaults = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData
    ): OpenRouterClient {
        val engine = MockEngine { req -> handler(req) }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }
        return OpenRouterClient(http, json, OpenRouterClient.Config(maxRetries = 2, baseBackoffMs = 1))
    }

    private fun req(model: String = "deepseek/deepseek-v4-flash") = ChatRequest(
        model = model,
        messages = listOf(ChatMessage.user("hi"))
    )

    @Test fun streamingYieldsDeltasThenCompletedUsage() = runTest {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],")
            append("\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,\"cost\":0.0004}}\n\n")
            append("data: [DONE]\n\n")
        }
        val c = client { respond(ByteReadChannel(sse), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
        val events = c.streamCleanup("key", req()).toList()
        val deltas = events.filterIsInstance<OpenRouterClient.Stream.Delta>().map { it.text }
        assertEquals(listOf("Hello", " world"), deltas)
        val completed = events.filterIsInstance<OpenRouterClient.Stream.Completed>().single()
        assertEquals(0.0004, completed.usage!!.cost!!, 1e-9)
        assertEquals(12, completed.usage!!.totalTokens)
    }

    @Test fun streamingRequestBodyKeepsUsageAndReasoningFlags() = runTest {
        // Regression for Bug 1: encodeDefaults=false dropped `include`/`exclude`, so the
        // wire carried `"usage":{}` — which OpenRouter 400-rejects. Both flags must ship.
        var seenBody: String? = null
        val c = client { req ->
            seenBody = (req.body as io.ktor.http.content.TextContent).text
            respond(ByteReadChannel("data: [DONE]\n\n"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        c.streamCleanup("key", req().copy(reasoning = ReasoningRequest(exclude = true))).toList()
        assertTrue("usage.include must be on the wire: $seenBody", seenBody!!.contains("\"usage\":{\"include\":true}"))
        assertTrue("reasoning.exclude must be on the wire: $seenBody", seenBody!!.contains("\"exclude\":true"))
    }

    @Test fun badParam400SurfacesRealMessageNotModelUnavailable() = runTest {
        // A 400 that is NOT about the model must keep its real message (Unknown), not be
        // mis-mapped to ModelUnavailable (Bug 1's mapError conflation).
        val body = """{"error":{"message":"usage.include: Invalid input: expected boolean, received undefined","code":400}}"""
        val c = client { respond(body, HttpStatusCode.BadRequest, jsonHeaders) }
        val err = runCatching { c.complete("k", req()) }.exceptionOrNull()
        assertTrue("expected Unknown, got $err", err is OpenRouterError.Unknown)
        assertTrue((err as OpenRouterError.Unknown).detail!!.contains("usage.include"))
    }

    @Test fun invalidKeyMapsFrom401() = runTest {
        val c = client { respond("nope", HttpStatusCode.Unauthorized, jsonHeaders) }
        val err = runCatching { c.complete("bad", req()) }.exceptionOrNull()
        assertTrue(err is OpenRouterError.InvalidKey)
    }

    @Test fun noCreditMapsFrom402() = runTest {
        val c = client { respond("no credit", HttpStatusCode.PaymentRequired, jsonHeaders) }
        val err = runCatching { c.complete("k", req()) }.exceptionOrNull()
        assertTrue(err is OpenRouterError.NoCredit)
    }

    @Test fun modelUnavailableMapsFrom404() = runTest {
        val c = client { respond("no model", HttpStatusCode.NotFound, jsonHeaders) }
        val err = runCatching { c.complete("k", req("ghost/model")) }.exceptionOrNull()
        assertTrue(err is OpenRouterError.ModelUnavailable)
        assertEquals("ghost/model", (err as OpenRouterError.ModelUnavailable).model)
    }

    @Test fun rateLimitRetriesThenSucceeds() = runTest {
        val calls = AtomicInteger(0)
        val body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"total_tokens":3}}"""
        val c = client {
            if (calls.getAndIncrement() == 0) respond("slow down", HttpStatusCode.TooManyRequests, jsonHeaders)
            else respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val res = c.complete("k", req())
        assertEquals("ok", res.content)
        assertEquals(2, calls.get()) // one 429 + one success
    }

    @Test fun rateLimitExhaustsRetriesAndThrows() = runTest {
        val c = client { respond("slow", HttpStatusCode.TooManyRequests, jsonHeaders) }
        val err = runCatching { c.complete("k", req()) }.exceptionOrNull()
        assertTrue(err is OpenRouterError.RateLimited)
    }

    @Test fun structuredCompletionReturnsContentAndUsage() = runTest {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"items\":[]}"}}],
            "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6,"cost":0.0001}}"""
        val c = client { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val res = c.complete("k", req())
        assertEquals("{\"items\":[]}", res.content)
        assertEquals(0.0001, res.usage!!.cost!!, 1e-9)
    }

    @Test fun listModelsParsesData() = runTest {
        val body = """{"data":[
            {"id":"deepseek/deepseek-v4-flash","name":"DeepSeek V4 Flash","context_length":64000,
             "pricing":{"prompt":"0.0000001","completion":"0.0000002"}},
            {"id":"qwen/qwen3-asr-flash-2026-02-10","name":"Qwen3 ASR","architecture":{"input_modalities":["audio"]}}
        ]}"""
        val c = client { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val models = c.listModels("k")
        assertEquals(2, models.size)
        assertEquals("deepseek/deepseek-v4-flash", models[0].id)
        assertEquals(64000, models[0].contextLength)
    }

    @Test fun reasoningParamRejectionRetriesWithoutIt() = runTest {
        // A provider that 400s the first (reasoning-carrying) request but accepts the retry
        // once the param is stripped (item 8).
        val calls = AtomicInteger(0)
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"total_tokens":3}}"""
        val c = client {
            if (calls.getAndIncrement() == 0) respond("bad param", HttpStatusCode.BadRequest, jsonHeaders)
            else respond(ok, HttpStatusCode.OK, jsonHeaders)
        }
        val res = c.complete("k", req().copy(reasoning = ReasoningRequest(exclude = true)))
        assertEquals("ok", res.content)
        assertEquals(2, calls.get()) // one rejection + one stripped retry
    }

    @Test fun emptyContentIsTreatedAsRetryableThenSoftFails() = runTest {
        // Reasoning variant that returns null content (finish_reason "length"): must never
        // surface "null" — it retries, then fails soft as a Network error (item 8).
        val calls = AtomicInteger(0)
        val empty = """{"choices":[{"message":{"role":"assistant","content":null}}],"usage":{"total_tokens":1}}"""
        val c = client { calls.getAndIncrement(); respond(empty, HttpStatusCode.OK, jsonHeaders) }
        val err = runCatching { c.complete("k", req()) }.exceptionOrNull()
        assertTrue(err is OpenRouterError.Network)
        assertTrue("should have retried the empty completion", calls.get() >= 2)
    }

    @Test fun testConnectionFailureIsCaptured() = runTest {
        val c = client { respond("nope", HttpStatusCode.Unauthorized, jsonHeaders) }
        val result = c.testConnection("bad")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OpenRouterError.InvalidKey)
    }

    // --- describeImage (M-A P7 — vision image indexing) -------------------------

    @Test fun describeImageSendsImagePartVerbatimPromptAndParses() = runTest {
        var seenBody: String? = null
        val body = """{"choices":[{"message":{"role":"assistant","content":
            "{\"ocr_text\":\"MILK 2.50\",\"description\":\"a supermarket receipt\",\"tags\":[\"receipt\"]}"}}],
            "usage":{"total_tokens":9,"cost":0.0002}}"""
        val c = client { req ->
            seenBody = (req.body as io.ktor.http.content.TextContent).text
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val res = c.describeImage(
            apiKey = "k",
            model = "google/gemini-2.5-flash",
            dataUrl = "data:image/png;base64,QUJD",
            systemPrompt = com.fadghost.notesapp.data.ai.AiPrompts.IMAGE_INDEX_V1,
            userText = "Index this image.",
            temperature = 0.1
        )
        assertTrue("parses assistant JSON content: ${res.content}", res.content.contains("MILK 2.50"))
        val b = seenBody!!
        assertTrue("image_url part on the wire: $b", b.contains("\"type\":\"image_url\""))
        assertTrue("data url on the wire: $b", b.contains("data:image/png;base64,QUJD"))
        assertTrue("verbatim P7 prompt on the wire: $b", b.contains("transcribe ALL legible text verbatim"))
        assertTrue("temperature 0.1 on the wire: $b", b.contains("\"temperature\":0.1"))
    }

    // --- listTranscriptionModels (item 9 — live STT model discovery) ------------

    @Test fun listTranscriptionModelsQueriesOutputModalitiesFilter() = runTest {
        var seenQuery: String? = null
        val body = """{"data":[
            {"id":"openai/gpt-4o-mini-transcribe","name":"OpenAI: GPT-4o Mini Transcribe"},
            {"id":"openai/whisper-1","name":"OpenAI: Whisper 1"}
        ]}"""
        val c = client { req ->
            seenQuery = req.url.parameters["output_modalities"]
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val models = c.listTranscriptionModels("k")
        assertEquals("transcription", seenQuery)
        assertEquals(2, models.size)
        assertEquals("openai/gpt-4o-mini-transcribe", models[0].id)
    }

    @Test fun listTranscriptionModelsMapsInvalidKeyFrom401() = runTest {
        val c = client { respond("nope", HttpStatusCode.Unauthorized, jsonHeaders) }
        val err = runCatching { c.listTranscriptionModels("bad") }.exceptionOrNull()
        assertTrue(err is OpenRouterError.InvalidKey)
    }
}
