package com.fadghost.notesapp.data.ai.net

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.Serializable

/**
 * Wire types + multipart builder for the OpenRouter STT endpoint
 * (`POST /audio/transcriptions`, PLAN.md §5). Kept beside the chat DTOs and free of
 * Android/UI types so the multipart form (fields, filename, content-type) can be
 * asserted against Ktor MockEngine in a plain unit test.
 */
@Serializable
data class TranscriptionResponse(
    val text: String = "",
    /** Some STT models echo usage/cost; optional. */
    val usage: Usage? = null
)

/** Parsed transcription result surfaced to the pipeline. */
data class TranscriptionResult(val text: String, val usage: Usage?)

object TranscriptionForm {

    const val CONTENT_TYPE = "audio/m4a"

    /**
     * Build the `multipart/form-data` parts for a transcription request: the `model`
     * and `language` text fields plus the audio `file` part carrying [filename] and its
     * content type.
     *
     * RFC 7578 requires every part to use `Content-Disposition: form-data` with QUOTED
     * parameter values. OpenRouter's parser enforces this strictly: Ktor's
     * `ContentDisposition.Inline` / `.File` render `inline` / `file` (and leave
     * token-safe `name`/`filename` values unquoted), which the endpoint rejects with
     * HTTP 400 "Invalid multipart/form-data body"; an unquoted `filename` even drops the
     * whole `file` part ("Missing required file field"). Curl-verified: only fully
     * quoted `form-data` parts return 200. Headers are therefore written verbatim as
     * explicit [PartData] (not the `formData` DSL, which can emit a second
     * Content-Disposition and drop the filename), so tests can assert
     * fields/filename/content-type with no network.
     */
    fun parts(
        model: String,
        audioBytes: ByteArray,
        filename: String,
        language: String,
        contentType: String = CONTENT_TYPE
    ): List<PartData> {
        fun quote(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun formField(name: String, value: String): PartData.FormItem = PartData.FormItem(
            value = value,
            dispose = {},
            partHeaders = Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=${quote(name)}")
            }
        )
        val filePart = PartData.BinaryItem(
            provider = { ByteReadPacket(audioBytes) },
            dispose = {},
            partHeaders = Headers.build {
                append(
                    HttpHeaders.ContentDisposition,
                    "form-data; name=${quote("file")}; filename=${quote(filename)}"
                )
                append(HttpHeaders.ContentType, contentType)
            }
        )
        return listOf(formField("model", model), formField("language", language), filePart)
    }
}

/**
 * A ~3 KB silent WAV clip used to validate an STT model id against the live
 * `/audio/transcriptions` endpoint (Settings STT "Test" button — item 9) without a
 * real recording. A 200 response (even with empty transcribed text, since the probe
 * is silence) confirms the model id is currently accepted; a 400 surfaces as
 * [OpenRouterError.ModelUnavailable] naming the model. Plain PCM/WAV construction
 * (RIFF header + zeroed 16-bit samples) needs no Android codec, so this runs in a
 * plain JVM unit test the same as the rest of this file.
 */
object SilentAudioProbe {
    const val CONTENT_TYPE = "audio/wav"
    const val FILENAME = "probe.wav"

    private const val SAMPLE_RATE = 8000
    private const val DURATION_SECONDS = 0.2

    fun bytes(): ByteArray {
        val numSamples = (SAMPLE_RATE * DURATION_SECONDS).toInt()
        val dataSize = numSamples * 2
        val out = java.io.ByteArrayOutputStream(44 + dataSize)
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) { for (i in 0..3) out.write((v shr (8 * i)) and 0xFF) }
        fun le16(v: Int) { for (i in 0..1) out.write((v shr (8 * i)) and 0xFF) }
        str("RIFF"); le32(36 + dataSize); str("WAVE")
        str("fmt "); le32(16); le16(1); le16(1) // PCM, mono
        le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
        str("data"); le32(dataSize)
        repeat(dataSize) { out.write(0) }
        return out.toByteArray()
    }
}
