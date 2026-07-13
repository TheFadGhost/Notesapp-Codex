package com.fadghost.notesapp.data.ai.parse

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The three action kinds the extract flow proposes (PLAN.md §5). */
enum class ActionType {
    EVENT, REMINDER, TODO;

    companion object {
        fun fromWire(raw: String?): ActionType? = when (raw?.trim()?.lowercase()) {
            "event" -> EVENT
            "reminder" -> REMINDER
            "todo", "task" -> TODO
            else -> null
        }
    }
}

/** Raw item as it arrives in the model JSON (all fields optional/defensive). */
@Serializable
data class RawExtractItem(
    val type: String? = null,
    val title: String? = null,
    val datetime: String? = null,
    val notes: String? = null
)

@Serializable
data class RawExtractResult(val items: List<RawExtractItem> = emptyList())

/** A validated, clamped proposal ready to become a confirmation card. */
data class ProposedAction(
    val type: ActionType,
    val title: String,
    /** Epoch millis, or null for a dateless todo. */
    val datetimeMillis: Long?,
    val notes: String?
)

/** Outcome of parsing a model reply. */
sealed interface ExtractOutcome {
    data class Success(val items: List<ProposedAction>, val warnings: List<String>) : ExtractOutcome
    /** JSON could not be located/parsed — caller re-asks once, then shows [raw]. */
    data class ParseFailure(val raw: String) : ExtractOutcome
}

/**
 * Defensive parse + validate/clamp pipeline (PLAN.md §5). Locates JSON via
 * [JsonExtractor], deserialises leniently, then [ExtractionValidator] enforces:
 * max 10 items, non-empty titles, ISO-8601 datetimes clamped to ±5 years. Pure;
 * [now]/[zone] are injected so tests are deterministic.
 */
class ActionExtractionParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val validator: ExtractionValidator = ExtractionValidator()
) {
    fun parse(raw: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): ExtractOutcome {
        val jsonText = JsonExtractor.extract(raw) ?: return ExtractOutcome.ParseFailure(raw)
        val result = runCatching { json.decodeFromString<RawExtractResult>(jsonText) }.getOrNull()
            ?: return ExtractOutcome.ParseFailure(raw)
        return validator.validate(result.items, now, zone)
    }
}

/**
 * Validation/clamp rules for extracted actions (PLAN.md §5 — prompt-injection
 * guard: note content can't create arbitrary rows). Pure and unit-tested.
 */
class ExtractionValidator(
    private val maxItems: Int = 10,
    private val maxYears: Long = 5,
    /**
     * When a datetime carries a DATE but no clock time (e.g. "tomorrow" → "2026-07-14"),
     * this is the time-of-day it defaults to. Null keeps the legacy start-of-day (00:00)
     * behaviour used by the normal Extract flow. The **voice ramble** flow passes
     * `LocalTime.of(8, 0)` so "tomorrow I need to go gym" arms a reminder at 08:00 —
     * the owner's stated rule ("makes a thing in calendar to remind me at 8"). Explicit
     * times in the source are always honoured and never overridden.
     */
    private val dateOnlyDefaultTime: LocalTime? = null
) {
    fun validate(items: List<RawExtractItem>, now: Long, zone: ZoneId): ExtractOutcome.Success {
        val warnings = ArrayList<String>()
        val lowerBound = now - yearsMs(maxYears)
        val upperBound = now + yearsMs(maxYears)

        val out = ArrayList<ProposedAction>()
        for (item in items) {
            if (out.size >= maxItems) {
                warnings += "Kept the first $maxItems actions; the rest were dropped."
                break
            }
            val type = ActionType.fromWire(item.type)
            if (type == null) { warnings += "Skipped an item with unknown type \"${item.type}\"."; continue }
            val title = item.title?.trim().orEmpty()
            if (title.isBlank()) { warnings += "Skipped an item with no title."; continue }

            var millis = parseIso(item.datetime, zone)
            if (millis != null && (millis < lowerBound || millis > upperBound)) {
                millis = millis.coerceIn(lowerBound, upperBound)
                warnings += "Clamped an out-of-range date for \"$title\"."
            }
            if (millis == null && type != ActionType.TODO) {
                warnings += "\"$title\" had no usable date; kept as a dateless ${type.name.lowercase()}."
            }
            out += ProposedAction(
                type = type,
                title = title.take(200),
                datetimeMillis = millis,
                notes = item.notes?.trim()?.takeIf { it.isNotBlank() }?.take(2000)
            )
        }
        return ExtractOutcome.Success(out, warnings)
    }

    private fun yearsMs(years: Long): Long = years * 365L * 24 * 60 * 60 * 1000

    /** Parse a flexible ISO-8601 string to epoch millis; null if absent/unparseable. */
    fun parseIso(value: String?, zone: ZoneId): Long? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        // Instant (…Z) first, then offset, then local date-time, then date-only.
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
        }
        runCatching {
            val date = LocalDate.parse(s)
            // Date with no clock time: default to [dateOnlyDefaultTime] (08:00 for ramble)
            // or midnight for the legacy path. Explicit-time strings never reach here —
            // they matched an earlier branch above.
            val ldt = dateOnlyDefaultTime?.let { date.atTime(it) } ?: date.atStartOfDay()
            return ldt.atZone(zone).toInstant().toEpochMilli()
        }
        // Space-separated "yyyy-MM-dd HH:mm" fallback.
        runCatching {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            return LocalDateTime.parse(s, fmt).atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    companion object {
        /** UTC helper for tests. */
        val UTC: ZoneId = ZoneOffset.UTC
    }
}
