package com.fadghost.notesapp.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Prompt text + the Extract JSON schema (PLAN.md §5). Cleanup preserves every
 * fact and the user's language; Extract is constrained to a strict shape and
 * given the current instant so relative dates ("tomorrow 7am") resolve.
 */
object AiPrompts {

    /**
     * P7 IMAGE_INDEX_V1 (V3-PROMPTS.md §1.8) — verbatim. Background OCR/alt-text for one
     * image attachment so search can find images by their text/description. Runs on the
     * vision model at temp 0.1; the response is `{"ocr_text","description","tags"}`.
     */
    const val IMAGE_INDEX_V1 = """You index one image from a personal notes app for search.

STRICT RULES:
1. ocr_text: transcribe ALL legible text verbatim, reading order, max 1200 chars.
   Empty string if none.
2. description: one factual sentence, max 120 chars, what the image shows. No guessing
   about people's identities, no evaluations.
3. tags: max 5 lowercase single words (objects/scene/document-type)."""

    /**
     * P1 MEMORY_EXTRACT_V1 (V3-PROMPTS.md §1.2) — VERBATIM. Note → atomic memory entries.
     * Sent as the system message; the app supplies note text + full index.md + today's date
     * in the user message (see [memoryExtractUser]). Params: temp 0.1, top_p 0.9,
     * max_tokens 4096, strict json_schema ([MEMORY_EXTRACT_SCHEMA]), reasoning excluded.
     * DO NOT reword — any change means a new version suffix.
     */
    const val MEMORY_EXTRACT_V1 = """You are the memory librarian for a personal notes app. You convert a note into atomic
memory entries for a long-term markdown vault.

STRICT RULES — follow every one, identically every time:
1. Extract only DURABLE information: facts about people/projects/preferences/routines/
   goals/decisions, dated commitments, reference info. SKIP: moods of the moment,
   one-off chatter, anything true only today, secrets/passwords/API keys/card numbers.
2. Each entry is ATOMIC: one fact-cluster, body max 120 words, plain markdown, no headings.
3. Resolve every relative date to an absolute ISO date using the provided today-date.
4. DEDUPLICATE against the provided index: if an entry substantially overlaps an existing
   slug, return op "update" with that exact slug and a body that MERGES old hook + new
   info. Never create a near-duplicate slug.
5. slug: kebab-case [a-z0-9-], max 40 chars, descriptive, stable (never encode dates
   into slugs unless the entry IS about a dated event).
6. hook: max 90 chars, telegraphic, the most retrieval-useful summary of the body.
7. links: only slugs that exist in the provided index or in this same batch. Max 6.
8. Max 10 entries per note. If nothing durable exists, return an empty list with
   skipped_reason. Never pad. Never invent facts not in the note.
9. Write bodies in the language of the note. Keys/slugs/types in English."""

    /**
     * P4 REWRITE_LEGIBLE_V1 (V3-PROMPTS.md §1.5) — VERBATIM. Ramble/transcript → clean note,
     * streamed into the existing before/after sheet. Distinct from Clean-up (light tidy):
     * Rewrite is a full restructure. Params: temp 0.4, max_tokens 8192, streamed, reasoning
     * excluded. The app appends today's date as a separate context line so relative dates
     * resolve (prompt rule 4). DO NOT reword — any change means a new version suffix.
     */
    const val REWRITE_LEGIBLE_V1 = """You rewrite a rambling note (often a voice transcript) into a clean, legible document.

STRICT RULES:
1. PRESERVE every fact, name, number, date, and intention. You reorganize; you never
   add ideas, opinions, or filler, and never drop content silently.
2. Structure: optional single "# Title" line if one is obvious; short paragraphs;
   "-" bullets for enumerations; "- [ ]" checkboxes for action items (collect them
   under a final "## To do" section if 2+ exist).
3. Remove fillers, repetitions, false starts, transcription stumbles.
4. Resolve relative dates to absolute dates using the provided today-date, keeping the
   original wording in parentheses, e.g. "Friday (2026-07-17)".
5. Same language as the note. Output ONLY the rewritten markdown — no commentary,
   no code fences, no "Here is"."""

    /**
     * Strict json_schema for P1 (V3-PROMPTS.md §1.2): entries[] of
     * {op, slug, title, type, tags[], links[], hook, body} + nullable skipped_reason.
     * `strict:true` → every property required + additionalProperties:false.
     */
    val MEMORY_EXTRACT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("entries") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("op") {
                            put("type", "string")
                            putJsonArray("enum") { add("create"); add("update") }
                        }
                        putJsonObject("slug") { put("type", "string") }
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("type") {
                            put("type", "string")
                            putJsonArray("enum") { com.fadghost.notesapp.data.memory.MemoryFormat.TYPES.forEach { add(it) } }
                        }
                        putJsonObject("tags") {
                            put("type", "array"); putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("links") {
                            put("type", "array"); putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("hook") { put("type", "string") }
                        putJsonObject("body") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add("op"); add("slug"); add("title"); add("type")
                        add("tags"); add("links"); add("hook"); add("body")
                    }
                }
            }
            putJsonObject("skipped_reason") {
                putJsonArray("type") { add("string"); add("null") }
            }
        }
        putJsonArray("required") { add("entries"); add("skipped_reason") }
    }

    /** The user message for P1: today's date + the whole current index + the note text (§1.2). */
    fun memoryExtractUser(noteText: String, indexMd: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            append(todayContext(now, zone)).append("\n\n")
            append("CURRENT INDEX (slug | type | hook):\n")
            append(indexMd.trim().ifBlank { "(empty — no entries yet)" }).append("\n\n")
            append("NOTE:\n").append(noteText.trim())
        }

    /** Global rule 4 date-context line: `Today is 2026-07-12 (Sunday).` */
    fun todayContext(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "Today is ${date} ($weekday)."
    }

    /** ISO date-only `yyyy-MM-dd` for entry created/updated stamps. */
    fun todayIso(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    /** Clean-up system prompt (PLAN.md §5): grammar, filler, structure, keep all facts + language. */
    const val CLEANUP_SYSTEM = """You are a careful note editor. Rewrite the user's note so it is clean and well structured, following ALL of these rules:
- Fix grammar, spelling and punctuation.
- Remove filler words and verbal tics (um, uh, like, you know, basically, sort of).
- Organise the content with Markdown: short headings (##), bullet lists and checklists where natural.
- Preserve EVERY fact, name, number, date and intent. Never invent, drop or summarise away information.
- Keep the user's original language (do not translate).
- Output ONLY the rewritten note in Markdown. No preamble, no commentary, no code fences."""

    /** When map-reducing a long note, the reduce step stitches cleaned chunks. */
    const val CLEANUP_REDUCE_SYSTEM = """You are joining several already-cleaned sections of one note into a single coherent Markdown document. Merge them smoothly, remove duplicate headings, keep every fact, and keep the user's language. Output ONLY the merged Markdown."""

    /** Extract system prompt; [nowIso] anchors relative dates. */
    fun extractSystem(nowIso: String): String = """You extract actionable items from a note.
The current date-time is $nowIso (use it to resolve relative dates like "tomorrow" or "next Friday").
Return a JSON object of the form {"items": [...]} where each item has:
- "type": one of "event", "reminder", "todo"
- "title": a short imperative title (required, non-empty)
- "datetime": ISO-8601 (e.g. 2026-07-12T09:00:00) when there is a clear time; omit for a plain todo
- "notes": optional extra detail
Only include items genuinely implied by the note. If there are none, return {"items": []}.
Output ONLY the JSON object — no prose, no code fences."""

    /** Revision prompt used by a card's "Other" free-text instruction. */
    fun reviseSystem(nowIso: String): String = """You revise a single extracted action based on the user's instruction.
The current date-time is $nowIso.
Return ONLY a JSON object for the one revised item: {"type":..., "title":..., "datetime":..., "notes":...}. No prose, no code fences."""

    /**
     * RAMBLE_EXTRACT_V1 — VERBATIM. Voice-ramble transcript → actions, reusing the same
     * `{"items":[…]}` shape + [EXTRACT_SCHEMA] as the note Extract flow. The key difference
     * from [extractSystem]: it is told to emit a **date-only** string (no clock time) when the
     * user names a day but no time, so the deterministic app-side default (08:00, via
     * `ExtractionValidator(dateOnlyDefaultTime = LocalTime.of(8,0))`) is what applies — the
     * owner's "remind me at 8" rule. Today's date is appended as a separate context line by the
     * caller (like Rewrite). temp 0.0, reasoning excluded. DO NOT reword — any change = new suffix.
     */
    const val RAMBLE_EXTRACT_V1 = """You extract actionable items from a spoken, rambling voice transcript.

STRICT RULES — follow every one, identically every time:
1. Extract EVENTS (something happening at a set time), REMINDERS (a nudge the user asked for),
   and TODOs (a task with no fixed time). Only items the user genuinely intends — never invent
   items, and never turn a musing ("maybe I should…") into a commitment unless clearly decided.
2. Output a JSON object {"items":[...]} where each item has:
   - "type": one of "event", "reminder", "todo".
   - "title": a short imperative title (required, non-empty), in the user's language.
   - "datetime": per rule 3; omit entirely for a plain todo with no day.
   - "notes": optional extra detail the user gave.
3. DATES/TIMES — resolve relative expressions ("tomorrow", "next Friday") to an absolute date
   using the provided current date-time, THEN:
   - If the user gave a specific clock time ("at 6pm", "half nine", "at about 6"), output a full
     ISO-8601 datetime WITH that time, e.g. "2026-07-17T18:00:00".
   - If the user named a DAY but NO clock time ("tomorrow", "on Friday"), output a DATE ONLY
     "YYYY-MM-DD" with NO time component. Do NOT invent a time.
4. Deduplicate — do not emit the same action twice just because the user repeated themselves.
5. Output ONLY the JSON object — no prose, no code fences, no commentary."""

    /** The strict-ish JSON schema handed to `response_format`. */
    val EXTRACT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type") {
                            put("type", "string")
                            putJsonArray("enum") { add("event"); add("reminder"); add("todo") }
                        }
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("datetime") { put("type", "string") }
                        putJsonObject("notes") { put("type", "string") }
                    }
                    putJsonArray("required") { add("type"); add("title") }
                }
            }
        }
        putJsonArray("required") { add("items") }
    }

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun nowIso(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ISO.format(Instant.ofEpochMilli(now).atZone(zone))
}
