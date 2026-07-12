package com.fadghost.notesapp.data.ai.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenRouter API client (PLAN.md §5). Two call styles:
 *  - [streamCleanup] — streaming SSE chat completions, incremental deltas.
 *  - [extractStructured] — non-streaming with `response_format` json_schema.
 * Plus [listModels] / [testConnection] for the Settings picker.
 *
 * Per-request cancellation falls out of coroutine cancellation (cancel the
 * collecting job → the HTTP read unwinds). Retries use exponential backoff on
 * 429/5xx. All failures surface as typed [OpenRouterError]. The API key is only
 * ever passed as a bearer header and is never logged.
 */
class OpenRouterClient(
    private val http: HttpClient,
    private val json: Json,
    private val config: Config = Config()
) {
    data class Config(
        val baseUrl: String = "https://openrouter.ai/api/v1",
        /** PLAN.md §5 — HTTP-Referer / X-Title identify the app to OpenRouter. */
        val referer: String = "https://github.com/fadghost/notesapp",
        val title: String = "Notesapp",
        val maxRetries: Int = 3,
        val baseBackoffMs: Long = 500
    )

    data class StructuredResult(val content: String, val usage: Usage?)

    /** One incremental item from a streamed completion. */
    sealed interface Stream {
        data class Delta(val text: String) : Stream
        data class Completed(val usage: Usage?) : Stream
    }

    // --- Models -----------------------------------------------------------------

    /** GET /models. Used by the Settings picker and the Test-connection button. */
    suspend fun listModels(apiKey: String): List<OpenRouterModel> = withRetry {
        val resp = http.get("${config.baseUrl}/models") { authHeaders(apiKey) }
        if (!resp.status.isSuccess()) throw mapError(resp, model = null)
        runCatching { resp.body<ModelsResponse>().data }
            .getOrElse { throw OpenRouterError.Parse(it.message) }
    }

    /** Test-connection helper: returns the model count on success, typed error otherwise. */
    suspend fun testConnection(apiKey: String): Result<Int> =
        runCatching { listModels(apiKey).size }

    /**
     * GET /models?output_modalities=transcription (item 9) — the dedicated STT
     * endpoint's supported models. Confirmed live: these ids (whisper-1,
     * gpt-4o-mini-transcribe, gpt-4o-transcribe, plus others OpenRouter adds over
     * time) do NOT appear in the plain [listModels] result but DO appear here, and
     * are exactly what `/audio/transcriptions` accepts. Powers the STT picker's
     * live model list so a newly retired/renamed id doesn't need an app update.
     */
    suspend fun listTranscriptionModels(apiKey: String): List<OpenRouterModel> = withRetry {
        val resp = http.get("${config.baseUrl}/models") {
            authHeaders(apiKey)
            parameter("output_modalities", "transcription")
        }
        if (!resp.status.isSuccess()) throw mapError(resp, model = null)
        runCatching { resp.body<ModelsResponse>().data }
            .getOrElse { throw OpenRouterError.Parse(it.message) }
    }

    // --- Streaming chat (Clean-up) ----------------------------------------------

    /**
     * Stream a chat completion. Emits [Stream.Delta] per token chunk then a final
     * [Stream.Completed] with usage; throws [OpenRouterError] on failure. The
     * connection attempt is retried on 429/5xx; once bytes flow we do not retry
     * (a partial stream is surfaced to the caller instead).
     */
    fun streamCleanup(apiKey: String, request: ChatRequest): Flow<Stream> = flow {
        var body = request.copy(stream = true, usage = UsageRequest(include = true))
        var attempt = 0
        while (true) {
            try {
                http.preparePost("${config.baseUrl}/chat/completions") {
                    authHeaders(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.execute { resp ->
                    if (!resp.status.isSuccess()) {
                        val err = mapError(resp, model = request.model)
                        if (isRetryable(resp.status.value) && attempt < config.maxRetries) {
                            throw RetrySignal(err)
                        }
                        throw err
                    }
                    consumeSse(resp.bodyChannel()) { event -> emit(event) }
                }
                return@flow
            } catch (retry: RetrySignal) {
                attempt++
                delay(config.baseBackoffMs * (1L shl (attempt - 1)))
            } catch (c: CancellationException) {
                throw c
            } catch (e: OpenRouterError) {
                // Provider rejected the `reasoning` param → retry once without it (item 8).
                if (body.reasoning != null && isParamRejection(e)) {
                    body = body.copy(reasoning = null)
                    continue
                }
                throw e
            } catch (e: Exception) {
                throw OpenRouterError.Network(e.message)
            }
        }
    }

    private suspend fun consumeSse(channel: ByteReadChannel, emit: suspend (Stream) -> Unit) {
        val parser = SseParser()
        var usage: Usage? = null
        suspend fun handle(payload: String?): Boolean {
            val data = payload ?: return false
            if (data == SseParser.DONE) return true
            val chunk = runCatching { json.decodeFromString<ChatStreamChunk>(data) }.getOrNull() ?: return false
            chunk.usage?.let { usage = it }
            chunk.firstDelta?.takeIf { it.isNotEmpty() }?.let { emit(Stream.Delta(it)) }
            return false
        }
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (handle(parser.accept(line))) { emit(Stream.Completed(usage)); return }
        }
        handle(parser.flush())
        emit(Stream.Completed(usage))
    }

    // --- Non-streaming completion (Extract + Clean-up reduce) -------------------

    /** Non-streaming completion; returns raw assistant content + usage for parsing. */
    suspend fun complete(apiKey: String, request: ChatRequest): StructuredResult =
        withReasoningFallback(request) { req ->
            withRetry {
                val resp = http.preparePost("${config.baseUrl}/chat/completions") {
                    authHeaders(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(req.copy(stream = false))
                }.execute { it.toChatResponseOrThrow(req.model) }
                // Reasoning models can return null/blank content (finish_reason "length").
                // Treat that as a transient failure so withRetry re-asks instead of ever
                // surfacing "null" to the note (item 8).
                val content = resp.firstContent?.takeIf { it.isNotBlank() }
                    ?: throw EmptyCompletion()
                StructuredResult(content, resp.usage)
            }
        }

    // --- Vision (image indexing, M-A P7) ----------------------------------------

    /**
     * Describe/OCR a single image via multimodal `/chat/completions` (M-A P7). The user
     * message carries a data-URL image part; [systemPrompt] is the verbatim IMAGE_INDEX
     * prompt. Non-streaming, low temperature; returns the raw assistant content + usage.
     * Retries on 429/5xx; the key is only ever a bearer header.
     */
    suspend fun describeImage(
        apiKey: String,
        model: String,
        dataUrl: String,
        systemPrompt: String,
        userText: String,
        temperature: Double = 0.1
    ): StructuredResult = withRetry {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("temperature", temperature)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", userText)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl) }
                        }
                    }
                }
            }
        }
        val resp = http.preparePost("${config.baseUrl}/chat/completions") {
            authHeaders(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { it.toChatResponseOrThrow(model) }
        val content = resp.firstContent?.takeIf { it.isNotBlank() } ?: throw EmptyCompletion()
        StructuredResult(content, resp.usage)
    }

    // --- Transcription (STT, multipart upload) ----------------------------------

    /**
     * Transcribe an audio [file] via `POST /audio/transcriptions` (PLAN.md §5 —
     * multipart upload, `language=en`, configured STT model). Retries on 429/5xx with
     * backoff; failures surface as typed [OpenRouterError]; cancellation unwinds the
     * upload. The key is only ever a bearer header.
     */
    suspend fun transcribe(
        apiKey: String,
        file: java.io.File,
        model: String,
        language: String = "en"
    ): TranscriptionResult =
        transcribe(apiKey, file.readBytes(), file.name, model, language)

    /**
     * Byte-source overload (used by the offline queue worker, the Settings STT
     * "Test" probe, and unit tests). [contentType] defaults to the real recording
     * format ([TranscriptionForm.CONTENT_TYPE]); the test probe overrides it to
     * `audio/wav` to match [SilentAudioProbe]'s bytes.
     */
    suspend fun transcribe(
        apiKey: String,
        audioBytes: ByteArray,
        filename: String,
        model: String,
        language: String = "en",
        contentType: String = TranscriptionForm.CONTENT_TYPE
    ): TranscriptionResult = withRetry {
        val parts = TranscriptionForm.parts(model, audioBytes, filename, language, contentType)
        val resp = http.preparePost("${config.baseUrl}/audio/transcriptions") {
            authHeaders(apiKey)
            setBody(io.ktor.client.request.forms.MultiPartFormDataContent(parts))
        }.execute { r ->
            if (!r.status.isSuccess()) throw mapError(r, model)
            runCatching { r.body<TranscriptionResponse>() }
                .getOrElse { throw OpenRouterError.Parse(it.message) }
        }
        TranscriptionResult(resp.text, resp.usage)
    }

    // --- Plumbing ---------------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders(apiKey: String) {
        headers {
            append(HttpHeaders.Authorization, "Bearer $apiKey")
            append("HTTP-Referer", config.referer)
            append("X-Title", config.title)
        }
        header(HttpHeaders.Accept, "application/json")
    }

    private suspend fun HttpResponse.toChatResponseOrThrow(model: String): ChatResponse {
        if (!status.isSuccess()) throw mapError(this, model)
        return runCatching { body<ChatResponse>() }.getOrElse { throw OpenRouterError.Parse(it.message) }
    }

    /**
     * Run [block]; if the provider rejects the request specifically because of the
     * `reasoning` param (a 4xx while we sent one), retry ONCE with reasoning stripped
     * (item 8). Any other error, or a request that never carried reasoning, passes
     * straight through.
     */
    private suspend fun <T> withReasoningFallback(request: ChatRequest, block: suspend (ChatRequest) -> T): T =
        try {
            block(request)
        } catch (c: CancellationException) {
            throw c
        } catch (e: OpenRouterError) {
            if (request.reasoning != null && isParamRejection(e)) block(request.copy(reasoning = null))
            else throw e
        }

    /** A 4xx that plausibly means "I don't accept that request parameter". */
    private fun isParamRejection(e: OpenRouterError): Boolean =
        e is OpenRouterError.ModelUnavailable || (e is OpenRouterError.Unknown && e.status in 400..422)

    /** Retry wrapper for unary suspend calls on 429/5xx with exponential backoff. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (c: CancellationException) {
                throw c
            } catch (e: OpenRouterError) {
                val retryable = when (e) {
                    is OpenRouterError.RateLimited -> true
                    is OpenRouterError.Unknown -> e.status in 500..599
                    is OpenRouterError.Network -> true
                    else -> false
                }
                if (!retryable || attempt >= config.maxRetries) throw e
                attempt++
                delay(config.baseBackoffMs * (1L shl (attempt - 1)))
            } catch (e: Exception) {
                if (attempt >= config.maxRetries) throw OpenRouterError.Network(e.message)
                attempt++
                delay(config.baseBackoffMs * (1L shl (attempt - 1)))
            }
        }
    }

    private fun isRetryable(status: Int): Boolean = status == 429 || status in 500..599

    private suspend fun mapError(resp: HttpResponse, model: String?): OpenRouterError {
        val raw = runCatching { resp.bodyAsText() }.getOrNull()
        val apiMessage = raw?.let { apiErrorMessage(it) }
        val detail = apiMessage ?: raw?.take(300)
        return when (resp.status.value) {
            401, 403 -> OpenRouterError.InvalidKey
            402 -> OpenRouterError.NoCredit
            429 -> OpenRouterError.RateLimited(
                resp.headersSafe("Retry-After")?.toLongOrNull()
            )
            // Only a genuine "this model does not exist" maps to ModelUnavailable. Every
            // other 400/404 (bad param, malformed body, unsupported option) now surfaces
            // its real OpenRouter message so the user isn't wrongly told to switch models
            // — the Bug 1 mis-mapping that turned a `usage:{}` 400 into "unavailable".
            400, 404 -> if (model != null && looksLikeUnknownModel(apiMessage, resp.status.value))
                            OpenRouterError.ModelUnavailable(model)
                        else OpenRouterError.Unknown(resp.status.value, detail)
            in 500..599 -> OpenRouterError.Unknown(resp.status.value, detail)
            else -> OpenRouterError.Unknown(resp.status.value, detail)
        }
    }

    /** Extract `error.message` from an OpenRouter error body, if the body carries one. */
    private fun apiErrorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]
            ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Whether a 400/404 means the model id itself is unknown (vs. a valid model that
     * rejected some request param). A 404 on chat/completions is by definition an
     * unknown model/endpoint; a 400 only counts when the message names the model as
     * missing. Anything else keeps its real message via [OpenRouterError.Unknown].
     */
    private fun looksLikeUnknownModel(message: String?, status: Int): Boolean {
        if (status == 404) return true
        val m = message?.lowercase() ?: return false
        return "not a valid model" in m || "is not a valid model id" in m ||
            "no endpoints found" in m || "no allowed providers" in m ||
            ("model" in m && ("not found" in m || "does not exist" in m ||
                "no such" in m || "unknown model" in m))
    }

    private fun HttpResponse.headersSafe(name: String): String? = headers[name]

    private class RetrySignal(val error: OpenRouterError) : Exception()

    /** Null/blank assistant content — retryable inside [withRetry] (never rendered). */
    private class EmptyCompletion : Exception("empty completion")
}

/** Small shim so [OpenRouterClient] can read the streaming body channel. */
internal suspend fun HttpResponse.bodyChannel(): ByteReadChannel = body()
