package com.fadghost.notesapp.data.memory

/**
 * Domain model for one memory-vault entry (V3-PROMPTS.md §1.1). This is the in-memory
 * shape of an `entries/<slug>.md` file: the documented front-matter block plus the body.
 *
 * Files are the source of truth; [MemoryEntryModel] is the parsed form the repository
 * mirrors into Room. One deliberate superset over the spec front-matter: a `hook:` field
 * is persisted alongside the documented ones so `index.md` is ALWAYS regenerable from the
 * entry files alone — that makes the checksum rebuild bulletproof (the index is a derived
 * cache, never hand-authoritative). Every other field is exactly per §1.1.
 */
data class MemoryEntryModel(
    val slug: String,
    val title: String,
    val type: String,
    val tags: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    /** ≤ 90 char telegraphic retrieval summary — the index line's payload (§1.1). */
    val hook: String,
    val source: String,
    /** ISO date `yyyy-MM-dd` (absolute dates only, §1.1). */
    val created: String,
    val updated: String,
    val body: String
) {
    /** The `entries/<slug>.md` file name. */
    val fileName: String get() = "$slug.md"

    /** One strict, machine-parsed `index.md` line: `- slug | type | hook` (§1.1). */
    fun indexLine(): String = "- $slug | $type | ${hook.take(MemoryFormat.HOOK_MAX)}"

    /** Render the full markdown file: documented front-matter + body. */
    fun toMarkdown(): String = buildString {
        append("---\n")
        append("slug: ").append(slug).append('\n')
        append("title: ").append(title).append('\n')
        append("type: ").append(type).append('\n')
        append("tags: ").append(renderList(tags)).append('\n')
        append("links: ").append(renderList(links)).append('\n')
        append("hook: ").append(hook).append('\n')
        append("source: ").append(source).append('\n')
        append("created: ").append(created).append('\n')
        append("updated: ").append(updated).append('\n')
        append("---\n\n")
        append(body.trim())
        append('\n')
    }

    private fun renderList(items: List<String>): String =
        "[" + items.joinToString(", ") + "]"

    companion object {
        /**
         * Parse an `entries/<slug>.md` file back into a model. Tolerant: an unknown or
         * missing `type` falls back to `fact`; a missing `hook` falls back to the first
         * line of the body (so hand-authored / older files still round-trip). Returns null
         * only when there is no front-matter block at all.
         */
        fun parse(markdown: String): MemoryEntryModel? {
            val text = markdown.replace("\r\n", "\n")
            if (!text.startsWith("---")) return null
            val end = text.indexOf("\n---", 3)
            if (end < 0) return null
            val header = text.substring(text.indexOf('\n') + 1, end)
            val body = text.substring(end + 4).trim()

            val fields = HashMap<String, String>()
            for (raw in header.split('\n')) {
                val line = raw.trim()
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
            val slug = MemoryFormat.sanitizeSlug(fields["slug"].orEmpty())
            if (slug.isBlank()) return null
            val hook = fields["hook"]?.takeIf { it.isNotBlank() }
                ?: body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MemoryFormat.HOOK_MAX)
            return MemoryEntryModel(
                slug = slug,
                title = fields["title"].orEmpty().ifBlank { slug },
                type = MemoryFormat.sanitizeType(fields["type"].orEmpty()),
                tags = parseList(fields["tags"]).map { it.lowercase() }.take(MemoryFormat.TAGS_MAX),
                links = parseList(fields["links"]).map { MemoryFormat.sanitizeSlug(it) }
                    .filter { it.isNotBlank() }.take(MemoryFormat.LINKS_MAX),
                hook = hook.take(MemoryFormat.HOOK_MAX),
                source = fields["source"].orEmpty().ifBlank { MemoryFormat.SOURCE_MANUAL },
                created = fields["created"].orEmpty(),
                updated = fields["updated"].orEmpty(),
                body = body
            )
        }

        /** Parse a `[a, b, c]` (or bare `a, b`) inline list into trimmed, non-blank items. */
        private fun parseList(raw: String?): List<String> {
            val v = raw?.trim()?.removeSurrounding("[", "]")?.trim().orEmpty()
            if (v.isBlank()) return emptyList()
            return v.split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }
        }
    }
}
