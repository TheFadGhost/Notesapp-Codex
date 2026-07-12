package com.fadghost.notesapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A directed edge in the memory graph (V3-PROMPTS.md §1.1, schema v8, M-B): `fromSlug`
 * links to `toSlug`. Backs the M-C graph view. Composite PK keeps edges unique; both
 * columns are indexed so forward and reverse neighbour lookups are cheap. No FK to
 * [MemoryEntry] on purpose — a link may name a slug in the same accept-batch that lands a
 * moment later, and files (not the mirror) are the integrity authority.
 */
@Entity(
    tableName = "memory_links",
    primaryKeys = ["fromSlug", "toSlug"],
    indices = [Index("fromSlug"), Index("toSlug")]
)
data class MemoryLink(
    val fromSlug: String,
    val toSlug: String
)
