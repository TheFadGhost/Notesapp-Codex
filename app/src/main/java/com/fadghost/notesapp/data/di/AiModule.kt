package com.fadghost.notesapp.data.di

import android.util.Log
import com.fadghost.notesapp.BuildConfig
import com.fadghost.notesapp.data.ai.KeystoreCrypto
import com.fadghost.notesapp.data.ai.net.OpenRouterClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * AI infrastructure wiring (PLAN.md §5). Provides the shared Ktor/OkHttp client,
 * a lenient Json, the Keystore crypto helper and the [OpenRouterClient].
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        // MUST stay true: OpenRouter validates request params strictly. Default-valued
        // flags like usage.include=true / reasoning.exclude=true would otherwise be
        // dropped from the wire, yielding `"usage":{}` — which OpenRouter 400-rejects
        // ("usage.include: Invalid input: expected boolean, received undefined") — and
        // silently no-ops reasoning exclusion. Keep every request default on the wire.
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectTimeout(20, TimeUnit.SECONDS)
                // Long read window so a slow streamed completion is not cut mid-flight.
                readTimeout(90, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(ContentNegotiation) { json(json) }
        // Debug logging is deliberately headers-only. Request/response bodies can contain
        // private notes, diary text, transcripts, or image data and must never reach logcat.
        // The Authorization header is separately redacted as defence in depth.
        if (BuildConfig.DEBUG) {
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) { Log.d("OpenRouterHTTP", message) }
                }
                level = LogLevel.HEADERS
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }
        }
    }

    @Provides
    @Singleton
    fun provideKeystoreCrypto(): KeystoreCrypto = KeystoreCrypto()

    @Provides
    @Singleton
    fun provideOpenRouterClient(http: HttpClient, json: Json): OpenRouterClient =
        OpenRouterClient(http, json)
}
