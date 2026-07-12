package com.fadghost.notesapp.data.ai.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * OpenRouter `/chat/completions` + `/models` wire types (PLAN.md §5). Only the
 * fields the app uses are modelled; unknown keys are ignored by the Json config
 * in [OpenRouterClient]. Kept free of Android/UI types so they can be exercised
 * against Ktor MockEngine in unit tests with no network.
 */

@Serializable
data class ChatMessage(
    val role: String,
    // Nullable: reasoning variants can return a message with null `content` (the budget
    // went to a hidden reasoning field). Modelling it as non-null made deserialization
    // throw before the null could be handled (item 8). Requests always set it.
    val content: String? = null
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    /**
     * Reasoning control. Newer "flash" models (e.g. `deepseek-v4-flash-20260423`) are
     * reasoning variants that spend the token budget on a hidden `reasoning` field and
     * return null `content` with finish_reason "length" when `max_tokens` is small. We
     * always pass `{"exclude": true}` so the budget goes to visible content; if a
     * provider rejects the param the client retries once with this set to null.
     */
    val reasoning: ReasoningRequest? = null,
    /** Ask OpenRouter to include token usage on the final SSE frame too. */
    val usage: UsageRequest? = null
)

/** Reasoning toggle: `exclude=true` keeps chain-of-thought out of the token budget/output. */
@Serializable
data class ReasoningRequest(val exclude: Boolean = true)

/** Opt into usage accounting on streamed responses. */
@Serializable
data class UsageRequest(val include: Boolean = true)

@Serializable
data class ResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonSchema? = null
) {
    companion object {
        /** Structured-output wrapper for [Extract] (PLAN.md §5 — `response_format` json_schema). */
        fun jsonSchema(name: String, schema: JsonObject, strict: Boolean = true) = ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchema(name = name, strict = strict, schema = schema)
        )
    }
}

@Serializable
data class JsonSchema(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonObject
)

// --- Non-streaming response -------------------------------------------------

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null
) {
    val firstContent: String? get() = choices.firstOrNull()?.message?.content
}

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

// --- Streaming response -----------------------------------------------------

@Serializable
data class ChatStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null
) {
    val firstDelta: String? get() = choices.firstOrNull()?.delta?.content
    val finished: Boolean get() = choices.firstOrNull()?.finishReason != null
}

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null
)

// --- Usage / cost -----------------------------------------------------------

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** OpenRouter's authoritative USD cost for the call (PLAN.md §5 — post-hoc usage). */
    val cost: Double? = null
)

@Serializable
data class ApiError(
    val message: String? = null,
    val code: JsonElement? = null,
    val type: String? = null
)

// --- /models ----------------------------------------------------------------

@Serializable
data class ModelsResponse(val data: List<OpenRouterModel> = emptyList())

@Serializable
data class OpenRouterModel(
    val id: String,
    val name: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    val pricing: ModelPricing? = null,
    val architecture: ModelArchitecture? = null
) {
    val displayName: String get() = name ?: id
}

@Serializable
data class ModelPricing(
    val prompt: String? = null,
    val completion: String? = null
)

@Serializable
data class ModelArchitecture(
    val modality: String? = null,
    @SerialName("input_modalities") val inputModalities: List<String> = emptyList()
)
