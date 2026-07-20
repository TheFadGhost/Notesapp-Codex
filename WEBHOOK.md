# Notesapp Codex — Automation Webhook

**Audience: an AI assistant or automation tool.** This document tells you how to push
commands into the Notesapp Codex Android app over HTTP: create notes, schedule
reminders (with real alarms), add calendar events, write diary entries, append to
existing notes, and read back upcoming reminders.

You will have been handed two things by the phone's owner:

1. **A base URL** (host + port), and
2. **A bearer key** (a long random string).

Treat the key as a secret. Do not print it, log it, or share it.

---

## Base URL

The app runs a small HTTP server **on the phone itself**, on port **8765**.

- Default: `http://127.0.0.1:8765` — reachable **only from the phone** (loopback). To
  use it from the phone (e.g. Termux, Tasker, an on-device agent), this is the URL.
- If the owner turned on **Allow local network**, it also binds `0.0.0.0`, so it is
  reachable from other devices on the same Wi-Fi at `http://<phone-lan-ip>:8765`. The
  owner must tell you the phone's LAN IP; it is not discoverable from this API.

All endpoints are under the version prefix **`/v1`**.

### Hard limitation: the app must be running

The server only runs **while the Notesapp Codex app process is alive** (the app is open
or recently backgrounded). There is no always-on background service. If the OS has
killed the app, requests will fail to connect until the owner reopens it. Design your
automation to tolerate connection failures and retry, and ask the owner to keep the app
open while you push a batch.

---

## Authentication

Every endpoint requires the bearer key. Send it as an HTTP header:

```
Authorization: Bearer YOUR-KEY-HERE
```

- No key, wrong key, or malformed header → **`401 Unauthorized`**
  (`{"error":"Unauthorized"}`). This applies to **every** endpoint, including `/v1/ping`.
- The key is compared in constant time. There is no rate limit, but there is also no
  retry-friendly error body beyond the 401 — fix the header and try again.
- If the owner regenerates the key, the old key stops working **immediately**. You will
  start getting 401s and must be given the new key.

---

## Endpoints

### `GET /v1/ping`

Liveness + identity check. Requires the key.

```bash
curl -s http://127.0.0.1:8765/v1/ping \
  -H "Authorization: Bearer YOUR-KEY-HERE"
```

Response `200`:

```json
{"ok":true,"app":"Notesapp Codex","version":"4.0.0"}
```

Use this first to confirm the app is running and your key works before sending a batch.

### `POST /v1/commands`

Run a batch of commands. Requires the key. Body is JSON:

```json
{ "commands": [ { "type": "...", ... }, ... ] }
```

- Content-Type is not enforced, but send `application/json`.
- Commands run **in order**. One bad command does **not** abort the batch — it produces
  a per-command error at its index instead.
- The response is always `200` when the envelope is valid JSON, with one result per
  command:

```json
{
  "results": [
    { "index": 0, "ok": true, "id": 123 },
    { "index": 1, "ok": false, "error": "create_reminder \"when\" is not a valid ISO-8601 date: \"soon\"" }
  ]
}
```

- `index` — the command's position in your `commands` array.
- `ok` — whether it succeeded.
- `id` — on success, the id of the created/updated row (note, reminder, event, or diary
  entry). Omitted on failure.
- `error` — on failure, a human-readable reason. Omitted on success.
- `list_reminders` returns a `reminders` array instead of an `id` (see below).

**Malformed JSON** (the body is not valid JSON, or has no `commands` array) →
**`400 Bad Request`** with `{"error":"...message..."}`. This is the only case that is
not a 200; an **unknown command `type`** is a per-command error, not a 400.

---

## Command schema

All timestamps are **ISO-8601**. Accepted forms, in priority order:

- Instant with `Z`: `2026-07-21T12:00:00Z`
- With offset: `2026-07-21T12:00:00+01:00`
- Local (no zone) — resolved in the **phone's** time zone: `2026-07-21T12:00`
- Date only (midnight, phone zone): `2026-07-21`
- Space-separated: `2026-07-21 12:00`

Prefer an explicit offset or `Z` when you know the absolute time. Use the clock-less
local form (`2026-07-21T12:00`) when you mean "12:00 in the owner's local time" — which
is usually what a human means by "post at noon".

### `create_note`

| Field   | Type       | Required | Notes                                |
|---------|------------|----------|--------------------------------------|
| `title` | string     | yes      | Non-blank.                           |
| `body`  | string     | no       | Markdown. Defaults to empty.         |
| `tags`  | string[]   | no       | Tag names; created/linked if needed. |

Returns `id` = the new note id.

```bash
curl -s http://127.0.0.1:8765/v1/commands \
  -H "Authorization: Bearer YOUR-KEY-HERE" \
  -H "Content-Type: application/json" \
  -d '{"commands":[{"type":"create_note","title":"Ideas","body":"# Hooks\n- one","tags":["tiktok","content"]}]}'
```

### `create_reminder`

| Field   | Type   | Required | Notes                                                        |
|---------|--------|----------|--------------------------------------------------------------|
| `title` | string | yes      | Non-blank.                                                   |
| `when`  | string | yes      | ISO-8601. When the alarm fires.                              |
| `url`   | string | no       | If present, stashed in a linked note so it is one tap away.  |
| `note`  | string | no       | Extra text; stored in the same linked note as `url`.         |

Schedules a **real exact alarm**, exactly like the app's Quick reminder. Returns `id` =
the new reminder id. If you pass `url` and/or `note`, the app also creates a linked note
(title = the reminder title, body = your note text and/or the url) so the owner can open
the link straight from the reminder.

### `create_event`

| Field   | Type   | Required | Notes                          |
|---------|--------|----------|--------------------------------|
| `title` | string | yes      | Non-blank.                     |
| `start` | string | yes      | ISO-8601 start.                |
| `end`   | string | no       | ISO-8601 end; must be ≥ start. |
| `note`  | string | no       | Event notes.                   |

Returns `id` = the new event id. Calendar events do not raise an alarm by default.

### `create_diary`

| Field  | Type   | Required | Notes                                                       |
|--------|--------|----------|-------------------------------------------------------------|
| `date` | string | no       | ISO date. Defaults to **today** (phone zone). One per day.  |
| `text` | string | yes      | Entry text.                                                 |

There is one diary entry per calendar day. If an entry already exists for `date`, your
`text` is **appended** to it (existing content is preserved). Returns `id` = the diary
entry id.

### `append_note`

| Field        | Type   | Required        | Notes                                             |
|--------------|--------|-----------------|---------------------------------------------------|
| `text`       | string | yes             | Text appended to the note body.                   |
| `noteId`     | number | one of these    | Exact note id (e.g. from a previous `create_note`).|
| `titleMatch` | string | one of these    | Case-insensitive substring; newest match is used. |

You must supply **exactly one** of `noteId` or `titleMatch` — supplying both or neither
is an error. If no note matches, the command fails with an error (the batch continues).
Returns `id` = the note that was appended to.

### `list_reminders`

| Field  | Type   | Required | Notes                          |
|--------|--------|----------|--------------------------------|
| `from` | string | no       | ISO-8601 lower bound on `when`.|
| `to`   | string | no       | ISO-8601 upper bound on `when`.|

Returns, instead of an `id`, a `reminders` array (sorted by time):

```json
{ "results": [
  { "index": 0, "ok": true, "reminders": [
      { "id": 12, "title": "Post TikTok video 1", "when": "2026-07-21T12:00:00+01:00", "done": false }
  ] }
] }
```

`when` is returned as ISO-8601 with the phone's current offset.

---

## Error semantics — summary

| Situation                                   | Result                                             |
|---------------------------------------------|----------------------------------------------------|
| Missing/invalid `Authorization`             | `401`, `{"error":"Unauthorized"}`                  |
| Body not valid JSON / no `commands` array   | `400`, `{"error":"..."}`                           |
| Unknown command `type`                      | `200`, per-command `{"ok":false,"error":"..."}`    |
| Missing required field / bad ISO date       | `200`, per-command `{"ok":false,"error":"..."}`    |
| `append_note` with both/neither selector    | `200`, per-command `{"ok":false,"error":"..."}`    |
| Valid command                               | `200`, per-command `{"ok":true,"id":N}`            |

---

## Worked example: 7 days of TikTok reminders at 12:00 and 19:00

The owner wants a reminder for each of the next 7 days at **12:00** and **19:00**, each
titled `Post TikTok video N` and each carrying a **different** video link. That is 14
`create_reminder` commands in a single batch. Times are given clock-less local, so they
mean noon and 7pm in the owner's own time zone.

```bash
curl -s http://127.0.0.1:8765/v1/commands \
  -H "Authorization: Bearer YOUR-KEY-HERE" \
  -H "Content-Type: application/json" \
  -d '{
  "commands": [
    {"type":"create_reminder","title":"Post TikTok video 1","when":"2026-07-21T12:00","url":"https://videos.example/1"},
    {"type":"create_reminder","title":"Post TikTok video 2","when":"2026-07-21T19:00","url":"https://videos.example/2"},
    {"type":"create_reminder","title":"Post TikTok video 3","when":"2026-07-22T12:00","url":"https://videos.example/3"},
    {"type":"create_reminder","title":"Post TikTok video 4","when":"2026-07-22T19:00","url":"https://videos.example/4"},
    {"type":"create_reminder","title":"Post TikTok video 5","when":"2026-07-23T12:00","url":"https://videos.example/5"},
    {"type":"create_reminder","title":"Post TikTok video 6","when":"2026-07-23T19:00","url":"https://videos.example/6"},
    {"type":"create_reminder","title":"Post TikTok video 7","when":"2026-07-24T12:00","url":"https://videos.example/7"},
    {"type":"create_reminder","title":"Post TikTok video 8","when":"2026-07-24T19:00","url":"https://videos.example/8"},
    {"type":"create_reminder","title":"Post TikTok video 9","when":"2026-07-25T12:00","url":"https://videos.example/9"},
    {"type":"create_reminder","title":"Post TikTok video 10","when":"2026-07-25T19:00","url":"https://videos.example/10"},
    {"type":"create_reminder","title":"Post TikTok video 11","when":"2026-07-26T12:00","url":"https://videos.example/11"},
    {"type":"create_reminder","title":"Post TikTok video 12","when":"2026-07-26T19:00","url":"https://videos.example/12"},
    {"type":"create_reminder","title":"Post TikTok video 13","when":"2026-07-27T12:00","url":"https://videos.example/13"},
    {"type":"create_reminder","title":"Post TikTok video 14","when":"2026-07-27T19:00","url":"https://videos.example/14"}
  ]
}'
```

Expected response — 14 successes, one per index:

```json
{ "results": [
  {"index":0,"ok":true,"id":101},
  {"index":1,"ok":true,"id":102},
  {"index":2,"ok":true,"id":103},
  {"index":3,"ok":true,"id":104},
  {"index":4,"ok":true,"id":105},
  {"index":5,"ok":true,"id":106},
  {"index":6,"ok":true,"id":107},
  {"index":7,"ok":true,"id":108},
  {"index":8,"ok":true,"id":109},
  {"index":9,"ok":true,"id":110},
  {"index":10,"ok":true,"id":111},
  {"index":11,"ok":true,"id":112},
  {"index":12,"ok":true,"id":113},
  {"index":13,"ok":true,"id":114}
] }
```

Afterwards you can read them back:

```bash
curl -s http://127.0.0.1:8765/v1/commands \
  -H "Authorization: Bearer YOUR-KEY-HERE" \
  -H "Content-Type: application/json" \
  -d '{"commands":[{"type":"list_reminders","from":"2026-07-21T00:00","to":"2026-07-28T00:00"}]}'
```
