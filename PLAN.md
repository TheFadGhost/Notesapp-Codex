# "Notesapp" — AI Notes / Diary / Calendar for Android
**Plan v2 — 2026-07-11** (v1 reviewed by two independent review passes + STT model research; all findings incorporated)

A personal, single-user Android app: notes + diary + calendar in one place, with OpenRouter-powered AI (summarize rambles, extract reminders/events), voice capture via speech-to-text, fully custom UI (no stock Android widgets), distributed as an APK through GitHub Releases.

---

## 1. Product pillars

1. **Capture anything fast** — text, voice ramble, checklist — friction near zero.
2. **AI makes it useful** — one tap turns a ramble into clean bullets; another tap extracts dates/reminders and proposes calendar entries with confirm/deny cards.
3. **Feels premium** — fluid/bouncy spring motion, translucent surfaces, smooth on 120 Hz displays, custom components everywhere.
4. **Private by default** — everything stored locally on-device; the only network traffic is your own OpenRouter API calls.
5. **Trustworthy daily driver** — reminders that actually fire, text that never gets lost, backups that verifiably restore.

## 2. Confirmed user decisions (2026-07-11)

1. **Calendar:** App-internal calendar ONLY. No system/Google Calendar sync — Calendar Provider integration dropped entirely.
2. **Backup:** Manual one-tap ZIP export + scheduled automatic local backups to a user-picked SAF folder (can be a Drive-synced folder).
3. **Voice notes:** Keep audio attached. UI: small circular audio chip at the start of the transcript line (right side), tap → popover player. Apply this micro-interaction pattern (small inline chips/popovers for attachments & metadata) consistently app-wide.
4. **Lock:** Biometric/PIN lock on the Diary tab only. (Note: this is a UI gate, not encryption at rest — documented honestly in §12.)

---

## 3. Tech stack (decisions locked)

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.x | |
| UI | Jetpack Compose (latest BOM) | Custom design system, no Material stock components in visible UI |
| Design system | Custom ("Aura") — nav bar, sheets, menus, pickers, dialogs, toggles, context menus | **Theme token architecture built in P0** — every component consumes tokens from day one; retrofitting is painful |
| Animation | Compose spring physics (`spring()`, `animate*`), shared-element transitions | Blur via `Modifier.blur` / `RenderEffect` (API 31+) — NOT `graphicsLayer` (that's transforms/alpha only) |
| Local DB | Room (SQLite) + FTS5 external-content table (sync triggers) for full-text search | Schema migrations + exported schema JSON in CI **from P0** |
| Preferences | DataStore | |
| DI | Hilt | |
| Networking | **Ktor client** (single choice — has native SSE support) + kotlinx.serialization | Streaming for Clean-up; non-streaming structured output for Extract |
| Background | **Boundary rule:** AlarmManager exact alarms = anything that must fire at a clock time (reminders). WorkManager = deferrable guaranteed work (auto-backup, AI retry queue). Journaling nudge = inexact alarm/WorkManager | Alarms rescheduled on `BOOT_COMPLETED` and after app update |
| Audio | `MediaRecorder` → **AAC/M4A mono ~16 kHz, modest bitrate**; never WAV; clip cap ~5 min per segment with auto-chunking | Keeps uploads far under the 25 MB STT ceiling |
| Security | API key: Keystore-wrapped AES-GCM blob (NOT EncryptedSharedPreferences — deprecated/unmaintained). Never logged, **never included in backups/exports** | Honest posture: sideloaded app, no Play Integrity; rooted-device extraction is out of scope |
| CI | GitHub Actions → **unsigned** release build; **signing done locally** for releases (see §13) | Keystore never lives in repo secrets |
| SDK | **minSdk 31** (Android 12 — guarantees RenderEffect blur, no fallback code paths), targetSdk 36 | Edge-to-edge is mandatory at this target level, not optional polish |

**Performance budget (replaces vibes):** frame time < 8 ms on the target device, measured with Macrobenchmark + JankStats. Compose renders at display refresh rate by default; there is no magic 120 Hz flag. Blur is the #1 jank risk — see §10.

---

## 4. App structure (4 tabs + capture button)

Custom **translucent floating nav bar** (frosted, rounded pill, floats above content), with a **translucent “+” capture button**. Tabs morph with springy icon animations; active tab gets a soft pill highlight.

1. **Notes** — grid/list, pinning, folders/tags, search.
2. **Diary** — one entry per day, streaks, mood, lookbacks, biometric gate.
3. **Calendar** — custom month/week/agenda views, app-created events + reminders only.
4. **Settings** — themes, OpenRouter key, model pickers, behavior toggles, backup.

“+” opens a **spring-up bottom sheet** (not radial — better thumb reach + accessibility): **New note / New diary entry / Voice ramble / Quick reminder**.

**Faster-than-the-app capture paths (must-have):**
- Quick-settings tile → blank note, keyboard up.
- App-icon long-press shortcuts (new note / voice ramble).
- `ACTION_SEND` + `PROCESS_TEXT` handlers: share or select text anywhere → becomes a note.
- Target: cold start to typing < 1 s.

## 5. AI features (OpenRouter)

**Settings → AI:** paste key (Keystore-encrypted), test-connection button, model pickers with model-list cache + favorites/recents (free-text ID field as escape hatch):
- **Text model default:** `deepseek/deepseek-v4-flash` — verify at build time that this exact ID exists on OpenRouter and supports structured output/tool calling; fall back gracefully if a user-picked model doesn't.
- **STT model default:** `qwen/qwen3-asr-flash-2026-02-10` (**Qwen3 ASR Flash**) — research verdict: ~1.3% WER benchmark (vs ~12% for whisper-large-v3-turbo), robust to rambling/noisy speech, silence filtering, ~$0.126/hr (≈42× cheaper than Whisper Turbo on OpenRouter). Runner-ups offered in picker: `nvidia/parakeet-tdt-0.6b-v3` (cheapest/fastest), `openai/whisper-large-v3-turbo`, `openai/gpt-4o-transcribe` (max accuracy).
- STT endpoint: `POST https://openrouter.ai/api/v1/audio/transcriptions`, multipart file upload (avoids base64 bloat), `language=en`.

**In-note AI toolbar (two primary buttons):**
1. **✨ Clean up / Summarize** — streams live (SSE). Fixes grammar, removes filler, structures into headings/bullets, keeps all facts. Result shown as a **before/after toggle** (NOT a line diff — diffing rewritten prose is useless) → Accept / Keep original / Regenerate. AI never mutates the note in place; original always recoverable.
2. **📅 Extract actions** — **non-streaming** call with `response_format`/tool-calling JSON schema + defensive parser (models wrap JSON in prose; parse-fail → re-ask once, then show raw). Returns `{type: event|reminder|todo, title, datetime, notes}` list, validated/clamped before anything is created (sane dates, max count). Each proposal = swipeable confirmation card: **Yes / No / Edit / Other** (free-text instruction). One **“Undo all”** snackbar after batch-accepting.

**AI resilience (must-have):**
- Fully usable with **no API key**: AI buttons show a friendly "Add your OpenRouter key to enable" tap-through — never dead buttons.
- Offline: requests queue (WorkManager) and auto-run when back online; visible "AI unavailable — note untouched" state.
- Mid-stream **cancel** button on every call; retry with backoff; clear errors (bad key / no credit / rate limit).
- Long notes: chunking strategy for inputs beyond model context.
- Prompt-injection guard: extracted actions are clamped/validated; note content can't trigger arbitrary behavior.
- Cost: **post-hoc usage tracking** from OpenRouter's per-response usage field (per-call cost chip + monthly running total in settings). No pre-call token estimates (can't tokenize accurately for arbitrary models).

**Voice ramble flow:** record (live waveform + timer) → multipart upload to STT → transcript inserted with the **circular audio chip** at line start → optional auto-run Clean-up. Settings toggle: keep transcript verbatim vs de-filler via DeepSeek. Audio kept per user decision; per-note delete.

## 6. Notes features

- Markdown-backed editor: headings, bold/italic, checklists, bullets, inline images (Photo Picker — no permission needed), audio chips.
- **Sticky formatting toolbar docked above the keyboard** (bold/checkbox/heading/undo/redo) — thumb-reachable; smart lists (Enter continues, empty Enter exits, swipe to indent).
- **Draft crash-recovery:** in-progress text persisted continuously to a scratch buffer; process death/crash → "restore unsaved note" on next open. `rememberSaveable`/`SavedStateHandle` state restoration throughout.
- Autosave (debounced) — no per-keystroke version snapshots.
- Pin, archive, soft-delete → 30-day trash; **universal 5-second Undo snackbar** on every destructive/AI action.
- Tags + folders with rename/merge + tag colors; smart chips filter bar; smart filters ("untagged", "has reminder").
- **Global search** across notes + diary + calendar + transcripts (FTS5, highlighted matches, search-as-you-type, type filters; markdown syntax stripped before indexing).
- Slash `/` command menu: `/todo`, `/date`, `/ai`.
- Paste-smart: URL → titled link.
- Note templates (meeting, list, etc.), duplicate note, convert note → event/diary entry.
- Share sheet in/out; pin-a-note-to-notification (persistent quick list).
- Home-screen widgets (Glance, later milestone): quick capture, today's agenda, diary streak.
- Attachment lifecycle: orphaned files cleaned when trash purges; storage usage visible in settings.

## 7. Diary features

- Day-per-entry, templates/prompts, mood + weather stamp.
- Streaks, entry heat-map, "On this day" resurfacing.
- Biometric/PIN gate on the tab; optional per-note lock later.
- Daily journaling reminder (inexact alarm — can slip a few minutes).
- AI week/month digest — deferred until real data exists (milestone 4+).

## 8. Calendar & reminders (app-internal only)

- Custom month view (springy month-swipe, event dots), week view, agenda list.
- Events + reminders: manual or AI-extracted. **Recurrence:** v1 supports simple repeat (daily/weekly/monthly); full RRULE + "edit this vs all occurrences" explicitly deferred.
- Timezone-explicit storage; DST-safe scheduling.
- **Exact alarms via `USE_EXACT_ALARM`** — auto-granted, no revocable-permission settings dance. Play policy restricts it, but this app is sideloaded via GitHub, so the calculus changes; take the simpler, more reliable path.
- Reboot/app-update alarm rescheduling (`BOOT_COMPLETED`).
- **OEM battery-killer detection:** warn when battery-restricted (Samsung/Xiaomi/Oppo) with one-tap deep-link to the exemption screen.
- Notifications: `POST_NOTIFICATIONS` runtime request (Android 13+ — without it nothing shows), channels (Reminders high / Nudges default / AI results low), snooze actions. Full-screen intent only if genuinely needed (`USE_FULL_SCREEN_INTENT` is gated on 14+ for non-calling apps) — default to high-priority notification instead.
- Natural-language quick-add parsed **locally** ("gym tomorrow 7am") — instant, offline, no AI round-trip.

## 9. Theming

- Token-based theme engine **from P0**; every custom component consumes color/elevation/blur/translucency tokens.
- Themes: **Light, Dark, Pure Black (AMOLED), Grey (graphite)** + accent picker (8–10 curated accents). Material You dynamic color: cut (fights the custom token system for little gain).
- Milestone 1 ships Light + Dark; AMOLED + Grey arrive with the token system already in place (cheap to add).
- Animated theme switch (circular reveal), optional follow-system.

## 10. Motion & UI polish

- Spring-based everything: shared-element list-card → editor, sheet drag with velocity fling, rubber-band overscroll, staggered menu entrances, predictive back.
- **Blur budget (the #1 jank risk):** blur small regions only (nav pill, menus, sheets) — never full-screen scrims over scrolling lists; use downscaled-snapshot blur where content behind is static; profile blur cost in **P0** on-device, not P6. If a surface can't hold frame budget, fall back to semi-opaque tint + gradient (still reads as translucent).
- Haptics vocabulary: light tick (select), medium (confirm), success pattern (AI done).
- **Reduce-motion toggle** honoring the system animation-scale setting (accessibility + battery).
- Edge-to-edge, transparent system bars.
- Designed empty states for first-run Notes/Diary/Calendar.

## 11. Accessibility & ergonomics (must-have — custom UI gets nothing for free)

- Explicit semantics/content descriptions on every custom component; TalkBack focus order tested.
- Respect system font scale; min 48 dp touch targets; primary actions in bottom third (one-handed reach).
- 3-screen onboarding: what it does / permissions explained / paste-key-later.

## 12. Data, privacy, backup

- 100% local (Room + app files dir). **Honest note:** diary biometric lock is a UI gate; DB is not encrypted at rest (SQLCipher + FTS tradeoffs — conscious decision, revisit if needed).
- Export: one-tap ZIP (Markdown + attachments + JSON metadata) to SAF; **manifest with checksums**; restore shows preview + **replace-vs-merge choice**, never blind overwrite.
- Scheduled auto-backup (WorkManager) to user-picked SAF folder + "last backup: N days ago" nudge.
- **API key never in backups, exports, or logs.**
- No analytics, no trackers.

## 13. Distribution — GitHub (hardened)

- GitHub Actions: build + unit/UI smoke tests + assemble **unsigned** release APK. All third-party actions **pinned to full commit SHAs**; workflow restricted to tag pushes.
- **Signing happens locally** on release (keystore never in repo secrets — leaked Android signing keys can't be rotated; the key is the app's identity forever). Keystore backed up offline in two places.
- Signed APK attached to GitHub Release (semver tags) with changelog.
- In-app updater: checks GitHub Releases API, shows changelog sheet, downloads APK, hands off to installer (`REQUEST_INSTALL_PACKAGES` permission; document the unknown-sources + Play Protect dialog path once during onboarding — sideload warnings on Android 15/16 are expected, set expectations). Post-update "what's new" sheet on first launch.
- Optional later: Obtainium-compatible releases for smoother updates.

## 14. Milestones (re-phased: something installable ships early)

Each milestone ends with an installable, signed, usable APK on GitHub Releases.

- **M0 — Foundation (ships as v0.1-alpha):** scaffold, Hilt, Room schema + migration strategy + CI schema export, **theme tokens (Light/Dark)**, custom nav shell + capture sheet, blur profiling on-device, CI pipeline + local signing flow proven end-to-end.
- **M1 — Notes core (v0.1):** editor + keyboard toolbar + draft recovery, list/grid, FTS search, tags, trash + undo, ZIP export/restore. **Daily-drivable for plain notes.**
- **M2 — AI layer (v0.2):** key flow + onboarding, Ktor OpenRouter client (SSE), Clean-up (before/after toggle), Extract actions + confirmation cards + undo-all, offline queue, cost tracking.
- **M3 — Reminders + Calendar (v0.3):** custom calendar views, exact alarms (`USE_EXACT_ALARM`), reboot rescheduling, OEM battery warnings, notifications + snooze, NL quick-add, simple recurrence.
- **M4 — Voice (v0.4):** recording UI (waveform), Qwen3 ASR Flash pipeline, audio chips + popover player, transcript settings.
- **M5 — Diary (v0.5):** entries, streaks, heat-map, biometric gate, prompts, journaling nudge.
- **M6 — Polish (v1.0):** AMOLED + Grey themes, motion/haptics pass, widgets (Glance), quick-settings tile + shortcuts, share/PROCESS_TEXT handlers, accessibility audit, Macrobenchmark/JankStats pass on 120 Hz device, auto-backup scheduler, in-app updater.

**Deferred beyond v1.0 (explicit cut line):** note-linking/backlinks, per-note edit history/versioning, full RRULE recurrence, per-note locks, AI diary digests, DB encryption at rest, system-calendar anything (dropped by user), Material You (cut).

## 15. Testing (the things that silently break)

CI-run: alarm-fires test, backup round-trip (export → wipe → restore → checksums match), FTS query/trigger sync test, AI JSON parse fuzz test (prose-wrapped/truncated JSON), Room migration tests, draft-recovery process-death test.
