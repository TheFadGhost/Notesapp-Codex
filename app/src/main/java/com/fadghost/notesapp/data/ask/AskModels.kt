package com.fadghost.notesapp.data.ask

enum class AskRole { USER, ASSISTANT }

data class AskTurn(val role: AskRole, val text: String)

enum class AskSourceKind { NOTE, MEMORY }

data class AskSource(
    /** Citation token without brackets, for example `note:42` or `gym-schedule`. */
    val citation: String,
    val label: String,
    val excerpt: String,
    val kind: AskSourceKind,
    val noteId: Long? = null,
    val memorySlug: String? = null
)

sealed interface AskStream {
    data class Context(val sources: List<AskSource>) : AskStream
    data class Delta(val text: String) : AskStream
    data object Completed : AskStream
}

/** Pure citation and context helpers shared by repository, UI and unit tests. */
object AskText {
    private val citation = Regex("""\[\[([a-zA-Z0-9][a-zA-Z0-9:_-]{0,79})\]\]""")

    fun citations(text: String): List<String> =
        citation.findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun citedSources(text: String, available: List<AskSource>): List<AskSource> {
        val byCitation = available.associateBy { it.citation }
        return citations(text).mapNotNull(byCitation::get)
    }

    /** Source tokens become chips in the UI, so keep the bubble copy free of raw `[[...]]`. */
    fun withoutCitationTokens(text: String): String = text
        .replace(citation, "")
        .replace(Regex("""[ \t]+([,.;:!?])"""), "$1")
        .replace(Regex("[ \t]{2,}"), " ")
        .trim()

    fun excerpt(text: String, maxChars: Int = 900): String {
        if (maxChars <= 0) return ""
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxChars) compact
        else compact.take((maxChars - 1).coerceAtLeast(0)).trimEnd() + "\u2026"
    }

    fun contextBlock(sources: List<AskSource>): String = if (sources.isEmpty()) {
        "MEMORY CONTEXT:\nnone matched"
    } else buildString {
        append("MEMORY CONTEXT:\n")
        sources.forEach { source ->
            append("[[").append(source.citation).append("]] ")
            append(source.label).append('\n')
            append(source.excerpt).append("\n\n")
        }
    }.trimEnd()
}

data class AskMarkers(
    val visibleText: String,
    val saveMemoryFact: String? = null,
    val extractActions: Boolean = false
)

/**
 * Only consumes control markers from the trailing lines of a completed answer. This avoids
 * interpreting an example or quoted marker in the middle of ordinary prose as an app command.
 */
object AskMarkerParser {
    private const val SAVE_PREFIX = "SAVE_MEMORY:"
    private const val EXTRACT = "EXTRACT_ACTIONS"

    fun parse(text: String): AskMarkers {
        val lines = text.lines().toMutableList()
        fun trimTrailingBlankLines() {
            while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        }

        var saveFact: String? = null
        var extract = false
        trimTrailingBlankLines()
        while (lines.isNotEmpty()) {
            val marker = lines.last().trim()
            when {
                marker == EXTRACT -> {
                    extract = true
                    lines.removeAt(lines.lastIndex)
                }
                marker.startsWith(SAVE_PREFIX) -> {
                    marker.removePrefix(SAVE_PREFIX).trim().takeIf { it.isNotBlank() }
                        ?.let { saveFact = it.take(500) }
                    lines.removeAt(lines.lastIndex)
                }
                else -> break
            }
            trimTrailingBlankLines()
        }
        return AskMarkers(
            visibleText = lines.joinToString("\n").trimEnd(),
            saveMemoryFact = saveFact,
            extractActions = extract
        )
    }
}
