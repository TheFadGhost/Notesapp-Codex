package com.fadghost.notesapp.data.memory

import java.util.Locale

/**
 * Vault format rules and small pure helpers (V3-PROMPTS.md §1.1): slug/type/tag limits,
 * `index.md` (de)serialisation, and body-length clamping. Kept Android-free so it can be
 * unit-tested on the JVM.
 */
object MemoryFormat {

    /** slug: kebab-case [a-z0-9-], max 40 chars (§1.1 / P1 rule 5). */
    const val SLUG_MAX = 40
    const val HOOK_MAX = 90
    const val TAGS_MAX = 5
    const val LINKS_MAX = 6

    /** body max 120 words (§1.1 / P1 rule 2). */
    const val BODY_MAX_WORDS = 120

    const val SOURCE_MANUAL = "manual"
    const val SOURCE_CHAT = "chat"

    /** The eight entry types (§1.1). Unknown input falls back to [TYPE_FALLBACK]. */
    val TYPES = listOf(
        "fact", "person", "project", "preference", "routine", "goal", "event", "reference"
    )
    const val TYPE_FALLBACK = "fact"

    fun sanitizeType(raw: String): String {
        val t = raw.trim().lowercase(Locale.ROOT)
        return if (t in TYPES) t else TYPE_FALLBACK
    }

    /**
     * Force a string into a valid slug: lowercase, non-alphanumerics to `-`, collapse and
     * trim `-`, clamp to [SLUG_MAX]. Empty result stays empty (caller decides the fallback).
     */
    fun sanitizeSlug(raw: String): String {
        val lowered = raw.trim().lowercase(Locale.ROOT)
        val sb = StringBuilder(lowered.length)
        var lastDash = false
        for (c in lowered) {
            if (c in 'a'..'z' || c in '0'..'9') {
                sb.append(c); lastDash = false
            } else if (!lastDash && sb.isNotEmpty()) {
                sb.append('-'); lastDash = true
            }
        }
        return sb.toString().trim('-').take(SLUG_MAX).trim('-')
    }

    /** Clamp a body to [BODY_MAX_WORDS] words (token economy — §1.1). */
    fun clampBody(body: String): String {
        val words = body.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size <= BODY_MAX_WORDS) body.trim()
        else words.take(BODY_MAX_WORDS).joinToString(" ")
    }

    fun clampHook(hook: String): String = hook.trim().replace('\n', ' ').take(HOOK_MAX)

    /**
     * Build the whole `index.md` from the entries (the index is a derived cache — always
     * regenerated from the entry files, never hand-authoritative). Sorted by slug for a
     * stable, diff-friendly, deterministic file.
     */
    fun renderIndex(entries: List<MemoryEntryModel>): String = buildString {
        append("# Memory index\n\n")
        for (e in entries.sortedBy { it.slug }) {
            append(e.indexLine()).append('\n')
        }
    }

    /** Parse an `index.md` line `- slug | type | hook` into a Triple, or null if malformed. */
    fun parseIndexLine(line: String): Triple<String, String, String>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("- ")) return null
        val parts = trimmed.removePrefix("- ").split('|')
        if (parts.size < 3) return null
        val slug = sanitizeSlug(parts[0].trim())
        if (slug.isBlank()) return null
        return Triple(slug, sanitizeType(parts[1].trim()), parts.drop(2).joinToString("|").trim())
    }
}
