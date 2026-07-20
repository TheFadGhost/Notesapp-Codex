package com.fadghost.notesapp.data.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure, Android-free parser for the automation webhook command envelope
 * (`POST /v1/commands`). Lives here so it is fully JVM-unit-testable without a
 * device: it depends only on kotlinx-serialization and java.time (minSdk 31 has
 * the full java.time). The executor ([WebhookExecutor]) turns the parsed,
 * already-validated commands into real repository writes.
 *
 * Parsing is two-layered on purpose:
 *  - A malformed *envelope* (not JSON, or missing the `commands` array) throws
 *    [WebhookParseException] so the HTTP layer can answer 400.
 *  - A malformed *individual command* (unknown type, missing field, bad date,
 *    ambiguous selector) becomes a [CommandParse.Err] at its own index, so one
 *    bad command never fails the whole batch (per-command error, never a 500).
 */
sealed interface WebhookCommand {
    /** Create a note; [tags] are ensured/linked by name. */
    data class CreateNote(
        val title: String,
        val body: String,
        val tags: List<String>
    ) : WebhookCommand

    /** Reminder with a real alarm at [triggerAt] (epoch millis). */
    data class CreateReminder(
        val title: String,
        val triggerAt: Long,
        val url: String?,
        val note: String?
    ) : WebhookCommand

    /** Calendar event; [startAt]/[endAt] are epoch millis. */
    data class CreateEvent(
        val title: String,
        val startAt: Long,
        val endAt: Long?,
        val note: String?
    ) : WebhookCommand

    /** Diary entry for [date] (normalised `yyyy-MM-dd`). */
    data class CreateDiary(
        val date: String,
        val text: String
    ) : WebhookCommand

    /** Append to a note found by [noteId] or [titleMatch] (exactly one non-null). */
    data class AppendNote(
        val noteId: Long?,
        val titleMatch: String?,
        val text: String
    ) : WebhookCommand

    /** Read back reminders in an optional [from]..[to] window (epoch millis). */
    data class ListReminders(
        val from: Long?,
        val to: Long?
    ) : WebhookCommand
}

/** Outcome of parsing a single command in the batch, position-preserving. */
sealed interface CommandParse {
    data class Ok(val command: WebhookCommand) : CommandParse
    data class Err(val message: String) : CommandParse
}

/** Thrown for a malformed envelope; the HTTP layer maps this to 400. */
class WebhookParseException(message: String) : Exception(message)

/**
 * Stateless parser. [zone] resolves clock-less local ISO strings (e.g.
 * `2026-07-21T12:00`) and date-only diary defaults; [today] is injected so the
 * default diary date is deterministic under test.
 */
object WebhookCommands {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        body: String,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): List<CommandParse> {
        val root = runCatching { json.parseToJsonElement(body) }
            .getOrElse { throw WebhookParseException("Body is not valid JSON") }

        val obj = (root as? JsonObject)
            ?: throw WebhookParseException("Body must be a JSON object with a \"commands\" array")

        val commandsEl = obj["commands"]
            ?: throw WebhookParseException("Missing \"commands\" array")
        val array = (commandsEl as? JsonArray)
            ?: throw WebhookParseException("\"commands\" must be an array")

        return array.map { element ->
            val command = element as? JsonObject
                ?: return@map CommandParse.Err("Command must be a JSON object")
            parseOne(command, zone, today)
        }
    }

    private fun parseOne(o: JsonObject, zone: ZoneId, today: LocalDate): CommandParse {
        val type = o.str("type")?.trim()?.lowercase()
            ?: return CommandParse.Err("Missing \"type\"")

        return when (type) {
            "create_note" -> parseCreateNote(o)
            "create_reminder" -> parseCreateReminder(o, zone)
            "create_event" -> parseCreateEvent(o, zone)
            "create_diary" -> parseCreateDiary(o, zone, today)
            "append_note" -> parseAppendNote(o)
            "list_reminders" -> parseListReminders(o, zone)
            else -> CommandParse.Err("Unknown command type: \"$type\"")
        }
    }

    private fun parseCreateNote(o: JsonObject): CommandParse {
        val title = o.str("title")?.trim()
        if (title.isNullOrEmpty()) return CommandParse.Err("create_note requires \"title\"")
        val body = o.str("body") ?: ""
        val tags = when (val t = o["tags"]) {
            null -> emptyList()
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
            else -> return CommandParse.Err("create_note \"tags\" must be an array of strings")
        }
        return CommandParse.Ok(WebhookCommand.CreateNote(title, body, tags))
    }

    private fun parseCreateReminder(o: JsonObject, zone: ZoneId): CommandParse {
        val title = o.str("title")?.trim()
        if (title.isNullOrEmpty()) return CommandParse.Err("create_reminder requires \"title\"")
        val whenRaw = o.str("when")
            ?: return CommandParse.Err("create_reminder requires \"when\" (ISO-8601)")
        val triggerAt = parseIsoToMillis(whenRaw, zone)
            ?: return CommandParse.Err("create_reminder \"when\" is not a valid ISO-8601 date: \"$whenRaw\"")
        val url = o.str("url")?.trim()?.ifEmpty { null }
        val note = o.str("note")?.ifEmpty { null }
        return CommandParse.Ok(WebhookCommand.CreateReminder(title, triggerAt, url, note))
    }

    private fun parseCreateEvent(o: JsonObject, zone: ZoneId): CommandParse {
        val title = o.str("title")?.trim()
        if (title.isNullOrEmpty()) return CommandParse.Err("create_event requires \"title\"")
        val startRaw = o.str("start")
            ?: return CommandParse.Err("create_event requires \"start\" (ISO-8601)")
        val startAt = parseIsoToMillis(startRaw, zone)
            ?: return CommandParse.Err("create_event \"start\" is not a valid ISO-8601 date: \"$startRaw\"")
        val endRaw = o.str("end")
        val endAt = if (endRaw == null) null else parseIsoToMillis(endRaw, zone)
            ?: return CommandParse.Err("create_event \"end\" is not a valid ISO-8601 date: \"$endRaw\"")
        if (endAt != null && endAt < startAt) {
            return CommandParse.Err("create_event \"end\" is before \"start\"")
        }
        val note = o.str("note")?.ifEmpty { null }
        return CommandParse.Ok(WebhookCommand.CreateEvent(title, startAt, endAt, note))
    }

    private fun parseCreateDiary(o: JsonObject, zone: ZoneId, today: LocalDate): CommandParse {
        val text = o.str("text")?.trim()
        if (text.isNullOrEmpty()) return CommandParse.Err("create_diary requires \"text\"")
        val dateRaw = o.str("date")
        val date = if (dateRaw == null) {
            today.toString()
        } else {
            parseIsoToDate(dateRaw, zone)
                ?: return CommandParse.Err("create_diary \"date\" is not a valid ISO date: \"$dateRaw\"")
        }
        return CommandParse.Ok(WebhookCommand.CreateDiary(date, text))
    }

    private fun parseAppendNote(o: JsonObject): CommandParse {
        val text = o.str("text")
        if (text.isNullOrEmpty()) return CommandParse.Err("append_note requires \"text\"")
        val noteId = (o["noteId"] as? JsonPrimitive)?.longOrNull
        val titleMatch = o.str("titleMatch")?.trim()?.ifEmpty { null }
        val hasId = noteId != null
        val hasTitle = titleMatch != null
        if (hasId == hasTitle) {
            return CommandParse.Err("append_note requires exactly one of \"noteId\" or \"titleMatch\"")
        }
        return CommandParse.Ok(WebhookCommand.AppendNote(noteId, titleMatch, text))
    }

    private fun parseListReminders(o: JsonObject, zone: ZoneId): CommandParse {
        val fromRaw = o.str("from")
        val toRaw = o.str("to")
        val from = if (fromRaw == null) null else parseIsoToMillis(fromRaw, zone)
            ?: return CommandParse.Err("list_reminders \"from\" is not a valid ISO-8601 date: \"$fromRaw\"")
        val to = if (toRaw == null) null else parseIsoToMillis(toRaw, zone)
            ?: return CommandParse.Err("list_reminders \"to\" is not a valid ISO-8601 date: \"$toRaw\"")
        if (from != null && to != null && to < from) {
            return CommandParse.Err("list_reminders \"to\" is before \"from\"")
        }
        return CommandParse.Ok(WebhookCommand.ListReminders(from, to))
    }

    // --- shared helpers ---------------------------------------------------------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * Flexible ISO-8601 → epoch millis, mirroring the app's existing
     * `ExtractionValidator.parseIso` priority order so webhook dates behave like
     * AI-extracted dates: instant (`…Z`), offset (`+01:00`), clock-less local,
     * date-only (midnight), then a `yyyy-MM-dd HH:mm` fallback. Null when nothing
     * parses.
     */
    internal fun parseIsoToMillis(value: String, zone: ZoneId): Long? {
        val s = value.trim()
        if (s.isEmpty()) return null
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli() }
        runCatching {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            return LocalDateTime.parse(s, fmt).atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    /** Normalise any ISO date/datetime to a `yyyy-MM-dd` diary key. */
    internal fun parseIsoToDate(value: String, zone: ZoneId): String? {
        val s = value.trim()
        if (s.isEmpty()) return null
        runCatching { return LocalDate.parse(s).toString() }
        runCatching { return OffsetDateTime.parse(s).toLocalDate().toString() }
        runCatching { return LocalDateTime.parse(s).toLocalDate().toString() }
        runCatching { return Instant.parse(s).atZone(zone).toLocalDate().toString() }
        return null
    }
}
