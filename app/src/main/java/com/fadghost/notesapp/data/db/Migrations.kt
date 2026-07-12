package com.fadghost.notesapp.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2 (M0 -> M1): no Room *entity* changed, but the FTS5 index switched from
 * an external-content table with sync triggers to a Kotlin-managed regular table
 * so we can index markdown-stripped text (PLAN.md §6). Nothing else moves, so the
 * generated schema hash is unchanged; only the virtual table is rebuilt.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        NotesFts.migrateFromExternalContent(db)
    }
}

/**
 * v2 -> v3 (M1 -> M2): adds the AI layer's two new tables — per-call cost rows
 * (PLAN.md §5/§7) and the cached OpenRouter model list (PLAN.md §5). Purely
 * additive; no existing table or the FTS index is touched. Column definitions
 * mirror the [com.fadghost.notesapp.data.ai.cost.AiCallCost] and
 * [com.fadghost.notesapp.data.ai.model.CachedModel] entities exactly so Room's
 * post-migration schema validation passes.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `AiCallCost` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`feature` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`promptTokens` INTEGER NOT NULL, " +
                "`completionTokens` INTEGER NOT NULL, " +
                "`totalTokens` INTEGER NOT NULL, " +
                "`costUsd` REAL NOT NULL, " +
                "`noteId` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_AiCallCost_createdAt` ON `AiCallCost` (`createdAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `CachedModel` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`contextLength` INTEGER NOT NULL, " +
                "`promptPrice` TEXT, " +
                "`completionPrice` TEXT, " +
                "`inputModalities` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v3 -> v4 (M2 -> M3): reminders gain a simple repeat cycle so the calendar can
 * reschedule the next occurrence on fire/completion (PLAN.md §8). The [Event]
 * table already carries `recurrence`; this adds the mirror column to [Reminder].
 * Purely additive with a non-null default matching the entity's
 * `Recurrence.NONE`, so Room's post-migration validation passes.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Reminder` ADD COLUMN `recurrence` TEXT NOT NULL DEFAULT 'NONE'")
    }
}

/**
 * v4 -> v5 (M3 -> M4): adds the voice-note [com.fadghost.notesapp.data.db.entity.AudioAttachment]
 * table (PLAN.md §5/§2.3 — keep audio attached, one row per ramble). Purely additive;
 * a FK to `Note` with ON DELETE CASCADE drops rows when a note is hard-deleted, and the
 * trash-purge orphan sweep removes the files. Column set / index mirror the entity
 * exactly so Room's post-migration validation passes.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `AudioAttachment` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`noteId` INTEGER NOT NULL, " +
                "`filePath` TEXT NOT NULL, " +
                "`segmentPaths` TEXT NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`transcriptStart` INTEGER NOT NULL, " +
                "`transcriptEnd` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `Note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_AudioAttachment_noteId` ON `AudioAttachment` (`noteId`)")
    }
}

/**
 * v5 -> v6: rebuild the search index as FTS4. Every earlier version declared
 * `USING fts5`, which crashes at CREATE on Android's framework SQLite (the
 * module isn't compiled in) — the cause of the v1.0.0 first-launch crash.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        NotesFts.migrateToFts4(db)
    }
}

/**
 * v6 -> v7 (M-A): adds the [com.fadghost.notesapp.data.db.entity.Attachment] table —
 * one row per ingested image/file, referenced inline by the `[[att:<id>]]` body token.
 * Purely additive; a FK to `Note` with ON DELETE CASCADE drops rows when a note is
 * hard-deleted, and the trash-purge orphan pass removes the files. `annotatedOfId`
 * links an annotated copy to its original; `ocrText`/`description` are filled later by
 * the silent image-index job. Column set / indices mirror the entity exactly so Room's
 * post-migration validation passes.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`noteId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`path` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`mime` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`annotatedOfId` INTEGER, " +
                "`ocrText` TEXT, " +
                "`description` TEXT, " +
                "FOREIGN KEY(`noteId`) REFERENCES `Note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_noteId` ON `attachments` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_annotatedOfId` ON `attachments` (`annotatedOfId`)")
    }
}

/**
 * v7 -> v8 (M-B): the Folio memory vault's Room MIRROR (V3-PROMPTS.md §1.1). Files under
 * `filesDir/memory/` are the source of truth; these tables are a queryable projection,
 * rebuilt from the files on checksum mismatch at app start. Adds:
 *  - [com.fadghost.notesapp.data.db.entity.MemoryEntry] `memory_entries` (slug PK + the
 *    mirrored fields: title/type/tags CSV/body/source/created/updated),
 *  - [com.fadghost.notesapp.data.db.entity.MemoryLink] `memory_links` edge table for the
 *    graph (composite PK, both columns indexed),
 *  - the raw `memory_fts` FTS4 table (NOT a Room entity, so it is absent from the exported
 *    schema — created here for existing installs, exactly as [NotesFts] does for note_fts).
 * Purely additive; nothing existing is touched. Column sets / indices mirror the entities
 * exactly so Room's post-migration validation against `schemas/8.json` passes.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_entries` (" +
                "`slug` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`tags` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`created` TEXT NOT NULL, " +
                "`updated` TEXT NOT NULL, " +
                "PRIMARY KEY(`slug`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entries_type` ON `memory_entries` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entries_updated` ON `memory_entries` (`updated`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_links` (" +
                "`fromSlug` TEXT NOT NULL, " +
                "`toSlug` TEXT NOT NULL, " +
                "PRIMARY KEY(`fromSlug`, `toSlug`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_links_fromSlug` ON `memory_links` (`fromSlug`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_links_toSlug` ON `memory_links` (`toSlug`)")

        // Raw FTS4 index (framework SQLite has no fts5). Not a Room entity → untracked by
        // schema validation, like note_fts.
        MemoryFts.create(db)
    }
}
