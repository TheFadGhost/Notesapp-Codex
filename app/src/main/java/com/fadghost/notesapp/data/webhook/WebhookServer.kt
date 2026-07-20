package com.fadghost.notesapp.data.webhook

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The embedded automation HTTP server. A single instance owns one CIO engine bound
 * to [host]:[PORT]; [WebhookServerController] creates/destroys it as the enable and
 * allow-LAN prefs change. Every request is bearer-authenticated against the current
 * token read fresh from [WebhookTokenStore] (so rotating the key takes effect at
 * once, with no restart) using a constant-time comparison.
 */
class WebhookServer(
    private val host: String,
    private val tokenStore: WebhookTokenStore,
    private val executor: WebhookExecutor
) {
    private val json = Json {
        // Omit null fields so an Ok entry has no "error" and a Fail entry has no "id".
        explicitNulls = false
        encodeDefaults = false
    }

    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = PORT, host = host) {
            routing {
                get("/v1/ping") {
                    if (!authorized(call)) {
                        respondUnauthorized(call); return@get
                    }
                    call.respondText(
                        json.encodeToString(PingResponse(ok = true, app = APP_NAME, version = APP_VERSION)),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                post("/v1/commands") {
                    if (!authorized(call)) {
                        respondUnauthorized(call); return@post
                    }
                    val body = call.receiveText()
                    val parsed = try {
                        WebhookCommands.parse(body)
                    } catch (e: WebhookParseException) {
                        call.respondText(
                            json.encodeToString(ErrorResponse(e.message ?: "Malformed request")),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@post
                    }
                    val results = executor.execute(parsed).map { it.toEntry() }
                    call.respondText(
                        json.encodeToString(CommandsResponse(results)),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        engine = null
    }

    /** Constant-time bearer check against the freshly-decrypted token. */
    private suspend fun authorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HEADER]?.trim() ?: return false
        if (!header.startsWith(BEARER_PREFIX)) return false
        val provided = header.removePrefix(BEARER_PREFIX).trim()
        val expected = tokenStore.get() ?: return false // no key => never authorised
        return constantTimeEquals(provided, expected)
    }

    private suspend fun respondUnauthorized(call: ApplicationCall) {
        call.respondText(
            """{"error":"Unauthorized"}""",
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
    }

    companion object {
        const val PORT = 8765
        const val APP_NAME = "Notesapp Codex"
        const val APP_VERSION = "4.0.0"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val LAN_HOST = "0.0.0.0"
        private const val HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

// --- Wire DTOs ------------------------------------------------------------------

@Serializable
private data class PingResponse(val ok: Boolean, val app: String, val version: String)

@Serializable
private data class CommandsResponse(val results: List<ResultEntry>)

@Serializable
private data class ResultEntry(
    val index: Int,
    val ok: Boolean,
    val id: Long? = null,
    val error: String? = null,
    val reminders: List<ReminderEntry>? = null
)

@Serializable
private data class ReminderEntry(
    val id: Long,
    val title: String,
    @SerialName("when") val whenAt: String,
    val done: Boolean
)

@Serializable
private data class ErrorResponse(val error: String)

private fun ExecResult.toEntry(): ResultEntry = when (this) {
    is ExecResult.Ok -> ResultEntry(
        index = index,
        ok = true,
        id = id,
        reminders = reminders?.map { ReminderEntry(it.id, it.title, it.whenAt, it.done) }
    )
    is ExecResult.Fail -> ResultEntry(index = index, ok = false, error = error)
}
