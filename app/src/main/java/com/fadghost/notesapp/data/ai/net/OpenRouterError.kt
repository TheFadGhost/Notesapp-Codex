package com.fadghost.notesapp.data.ai.net

/**
 * Typed OpenRouter failures (PLAN.md §5 — "clear errors: bad key / no credit /
 * rate limit"). Every network path maps HTTP/exception outcomes onto one of
 * these so the UI can show a friendly, actionable message. The raw API key is
 * NEVER placed in any message here (redaction is the caller's contract, but we
 * also never echo request headers).
 */
sealed class OpenRouterError(message: String) : Exception(message) {

    /** 401 — key missing, malformed, or rejected. */
    data object InvalidKey : OpenRouterError("Invalid or missing API key")

    /** 402 — account/credit exhausted. */
    data object NoCredit : OpenRouterError("Out of credit on your OpenRouter account")

    /** 429 — rate limited after exhausting retries. [retryAfterSeconds] if the server hinted one. */
    data class RateLimited(val retryAfterSeconds: Long? = null) :
        OpenRouterError("Rate limited — try again shortly")

    /** 404/400 for an unknown or unsupported model id. */
    data class ModelUnavailable(val model: String) :
        OpenRouterError("Model \"$model\" is unavailable")

    /** Connectivity / timeout / DNS — the request never got a usable response. */
    data class Network(val detail: String? = null) :
        OpenRouterError("Network error" + (detail?.let { ": $it" } ?: ""))

    /** Server returned a body we could not parse (bad JSON, empty stream, etc.). */
    data class Parse(val detail: String? = null) :
        OpenRouterError("Could not read the model response" + (detail?.let { ": $it" } ?: ""))

    /** Any other non-success status. */
    data class Unknown(val status: Int, val detail: String? = null) :
        OpenRouterError("Unexpected error (HTTP $status)" + (detail?.let { ": $it" } ?: ""))

    /**
     * Local guard, not an API response: the user's monthly AI budget cap is reached
     * (IDEAS #26). Raised BEFORE any network call so a capped month costs nothing.
     */
    data class BudgetReached(val capUsd: Double) :
        OpenRouterError("Monthly AI budget reached — raise or clear it in Settings → AI")
}
