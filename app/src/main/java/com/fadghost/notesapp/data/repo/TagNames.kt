package com.fadghost.notesapp.data.repo

/** Canonical persisted tag spelling: trim edges and collapse internal whitespace. */
object TagNames {
    fun normalize(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")
}
