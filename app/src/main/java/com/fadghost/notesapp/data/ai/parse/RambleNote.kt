package com.fadghost.notesapp.data.ai.parse

/**
 * Pure helpers for turning a rewritten voice ramble into a note (feature A). Kept free of any
 * Android/Compose types so the title/body split is unit-testable. REWRITE_LEGIBLE_V1 may open the
 * document with a single "# Title" line (its rule 2); the app shows a note's title and body
 * separately, so we lift that heading out as the title and keep the rest as the body.
 */
object RambleNote {

    private val H1 = Regex("^#\\s+(.+)$")

    /**
     * The note title for a rewritten ramble, or null if the document has no leading "# " heading.
     * Only a top-level `# ` heading counts — `## Section` and `#NoSpace` do not. Null lets the
     * caller fall back to a date-stamped default ("Voice note · 13 Jul").
     */
    fun titleFrom(markdown: String): String? {
        val firstLine = markdown.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val m = H1.find(firstLine) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The body with a leading "# Title" line removed (kept as the note title instead). When there
     * is no leading heading the whole thing is the body. Trims surrounding blank lines.
     */
    fun bodyWithoutTitle(markdown: String): String {
        titleFrom(markdown) ?: return markdown.trim()
        val lines = markdown.lines()
        val titleIdx = lines.indexOfFirst { it.trim().isNotEmpty() }
        if (titleIdx < 0) return markdown.trim()
        return lines.drop(titleIdx + 1).dropWhile { it.isBlank() }.joinToString("\n").trim()
    }
}
