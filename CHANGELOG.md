# Changelog

All notable changes to Notesapp are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses simple date-tagged releases rather than strict SemVer
(it's a sideloaded personal app, not a library).

## [v3.4.0] - 2026-07-20 - Quality of Life

A quality-of-life sweep across the whole app: search inside a note, keep AI spend under a cap,
recover from a bad model in one tap, listen to voice notes faster, and trust backups more.

### Added
- **Find in note**: a search icon in the editor opens a find bar with live match highlighting,
  match count, and next/previous stepping that scrolls each hit into view.
- **Copy text from images**: the fullscreen image viewer shows a "Copy text" pill whenever the
  image has recognised text, putting the already-extracted OCR text on the clipboard.
- **Retry with a different model**: AI error cards (Clean up, Rewrite, Extract, Add to memory)
  now offer one-tap chips that switch to a recommended model and retry immediately.
- **Monthly AI budget cap**: an optional USD cap in Settings → AI. Spend is checked against
  OpenRouter's own recorded costs before any request leaves the device; a nearing-budget warning
  and a clear paused state explain what's happening. Applies to text, Ask, and transcription.
- **Streak grace**: one missed diary day no longer breaks the current streak — the run bridges a
  single gap (shown honestly), while the all-time longest streak stays strict.
- **Voice player speed and skip**: 1×/1.25×/1.5×/2× playback and ±10s skip buttons in the
  voice-note player.
- **Text size setting**: Small / Default / Large / Larger in Settings → Appearance, multiplying
  the system font scale across the whole app.
- **Last-backup nudge**: the Backup card shows when the last export happened and gently nudges
  once it's stale (14+ days) or has never been done.
- **Preferences in backups**: theme, accent, reduce-motion, text size, chosen AI models,
  transcript auto-clean, budget cap, and model favourites now travel inside the backup ZIP and
  are applied on Replace restore. Never any API key or secret.

### Changed
- Older backups without embedded preferences continue to restore exactly as before.

## [v3.3.0] - 2026-07-15 - Deliberate Motion & Reliable Capture

This release tightens the app's interaction model: gestures distinguish intent, recording can
continue behind other apps with real controls, diary speech can be cleaned without overwriting the
raw meaning, and calendar reminders are explicit about the permissions they need to fire.

### Added
- Permission-gated **floating recording controls** over other apps: a compact draggable squircle
  opens Pause/Resume, Stop, Discard and Open controls. Dragging uses Android touch slop and cannot
  leak through as a click; position is kept between recordings.
- Foreground microphone session shared by the recording sheet, notification and floating controls.
- Diary speech-to-text for today and past entries, followed by an explicit **Make it clean** action
  that turns the selected transcript into sentences while preserving surrounding diary text.
- Local **PDF sharing** for notes and diary entries through Android's share sheet.
- Optional event end times, with a spring toggle and duration-preserving start-time edits.
- Press-and-hold help labels for icon-only bottom controls; labels disappear on release.
- Long-press attachment dragging to reposition photo/file chips inside a note.

### Changed
- The navbar is now clearly translucent instead of overly transparent; its traveling-circle spring
  is unchanged. Invisible pull zones beside the bar make the capture menu easier to drag open.
- Buttons, toggles, FABs and entering panels use the shared spring vocabulary and still honor
  Reduce motion.
- The editor reserves a right-side rail for voice chips and keeps the active typing area above the
  keyboard. The attachment source panel is smaller.
- AI memory tags are explicit semantic topics only; capitalization no longer creates hashtag-like
  labels, and tag pills no longer display a leading `#`.

### Fixed
- Exact/inexact alarm scheduling is wired through create, edit, undo, delete, snooze, recurrence,
  reboot and app-update paths. Missing permissions are shown as actionable setup instead of being
  silently ignored.
- Drag attempts on recording controls no longer register as clicks.
- Calendar create/edit controls no longer clip at the bottom and the sheet can spring in or drag
  down to dismiss.
- Note context-menu rows, voice/attachment popovers and the AI menu have corrected hit areas,
  bounds and entrance motion.

## [v3.2.0] - 2026-07-13 - Tag-first refinement & floating voice controls

### Added
- Optional system-overlay controls for an active Voice Ramble. Grant Android's overlay permission, then tap the floating control to pause/resume or long-press it to finish.
- A removable `Rambler` tag is attached automatically to newly completed ramble notes. If the tag is deleted, the next ramble safely recreates it.
- The requested OpenRouter text and speech-to-text models appear in a **Recommended** group at the top of the Settings model pickers; live-discovered models remain available underneath.
- Collapsible Agenda section; completed reminders leave the forward agenda while remaining visible on their selected day.

### Changed
- Note organization is now tag-only. Existing folder data is preserved for backward-compatible storage and backup, but folders are no longer offered in the editor, filters, or note actions.
- The capture FAB stays visible while scrolling. Screen content consistently clears the floating navigation pill instead of falling behind a fade.
- Event/reminder sheets lift above the navigation pill; their grab handle now has a usable drag-dismiss animation.

## [v3.1.0] - 2026-07-13 - Ramble, Ask & Reliability

### Added
- Background-safe voice ramble recording with durable transcription, rewrite, and confirm-before-create action cards.
- Transcript-only diary microphone that inserts at the current selection without creating a stray note.
- A fifth **Ask Folio** tab with note/memory retrieval, streamed grounded answers, and source links.
- Unified Organize panels for editor assignment and tags-first note-list filtering.
- Event notification lead times and reminder source-note deep links.

### Changed
- Backup format v2 includes notes, Trash, folders, tags, diary entries, events, reminders, attachments, and memory, with strict bounded validation before restore.
- App and repository identity is now **Notesapp Codex**; database schema is v9.
- Date-only ramble actions default to 08:00 in the captured timezone.

### Fixed
- One-shot reminders and recurring event alerts can no longer notify twice after a restart.
- Multiple voice captures no longer overwrite the same audio segment or queued transcription.
- Debug HTTP logs never contain note, diary, transcript, image, or response bodies.
- Linux CI can execute the Gradle wrapper.

### Deferred
- The optional system-overlay recording bubble and spoken “stop recording” remain intentionally scoped to v3.2.

## [v3.0.0] - 2026-07-12 - Attachments & Memory

Adds image/file attachments and the first half of the Folio AI memory system, on top of a
round of real bug fixes to the 2.0.0 AI and layout code. The upgrade path from 2.0.0
(database 6 → 7 → 8) is verified on-device.

### Added
- **Attachments** — add images and files to any note via the photo picker, file picker,
  the system share sheet, drag-and-drop, or paste. Files are stored on-device per note.
- Inline **attachment chips**: a compact pill with the filename in a dedicated link-blue
  (contrast-checked on every theme). Tap for a preview popover — Expand, Annotate, Share, Remove.
- Fullscreen **image viewer** with pinch/double-tap zoom, pan, and drag-down to dismiss.
- **Annotation editor** — pencil (3 widths), highlighter, eraser, and draggable text, with
  finger + stylus support and undo/redo. Saves as a new copy; the original is preserved.
- **Image search** — pictures are indexed in the background (OCR + a short description) so
  full-text search finds them by the text inside them. Runs silently; needs your OpenRouter key.
- **Folio memory (foundation)** — a private, on-device memory vault (Obsidian-style markdown
  files with a fast search mirror). A new editor AI menu: **Clean up · Rewrite · Extract ·
  Add to memory**. "Add to memory" pulls durable facts out of a note as confirm cards before
  saving anything; "Rewrite" restructures a ramble into a clean, legible note.
- **Speech-to-text model picker** — choose any OpenRouter transcription model from a
  live-discovered list, type a custom model id, and a **Test** button that tells you if a
  model actually works before you rely on it mid-recording.
- Attachments and the memory vault are included in backup and restore.

### Changed
- Default speech-to-text model is now `openai/gpt-4o-mini-transcribe` (the previous default
  was retired upstream); existing installs migrate automatically.

### Fixed
- **AI actions failing with "That model is unavailable"** — request bodies were silently
  dropping required fields (`usage`/`reasoning`), so Clean-up and Extract were rejected by the
  server even with a valid key. Fixed; both now stream real results.
- AI errors now report the **real reason** instead of a blanket "model unavailable".
- **Voice transcription** — the multipart upload wasn't RFC-7578 compliant and was rejected by
  the server; real voice-to-text now works.
- Model-picker rows (text + speech-to-text) that did nothing when tapped now open.
- Nav bar overlapping content and stealing taps on Calendar & Diary; content no longer bleeds
  under the floating nav bar.
- Layout no longer breaks on very narrow / split-screen widths (verified down to 122dp).
- "Delete forever" in Trash now asks for confirmation.
- Grey theme is now visibly distinct from Dark; the theme picker is fully reachable.
- Autosave no longer wipes a note's attachments; the "+" button translucency and spacing.

## [v2.0.0] - 2026-07-12 - The Redesign

Major redesign, designed by a 4-lens design council and hardened by full code + UX audits.

### Added
- Traveling bubble nav indicator: a circle that glides between tabs and settles with a springy sideways sway
- Bottom-right contextual floating action button — capture panel on Notes, straight to today's entry on Diary, new event on Calendar
- Compact capture panel anchored to the + button (replaces the full-width sheet; can no longer fling too high or show a clipped edge)
- Editorial typography: Fraunces serif display + Hanken Grotesk body, bundled variable fonts
- Warm depth system: tinted paper-layer shadows, hairline outlines
- Press feedback on every control
- One-time coach tip explaining the editor AI buttons
- Distinct icons for the four capture actions
- Undo for editor and calendar deletes; danger confirms on replace-import, delete-forever, tag merge
- Form validation: no blank-title or past-dated reminders
- Backup progress indicators
- Per-tab state preservation (scroll positions survive tab switching)

### Changed
- Unified icon grammar across all glyphs
- Notification + battery setup prompts merged into one dismissible card
- Settings grouped into sections
- Calendar deletes, tag operations and backup errors got friendlier copy

### Fixed
- AI-extracted reminders now actually arm alarms (previously saved but never fired)
- Recurring reminders no longer burst-fire after downtime; snooze no longer shifts the schedule
- Dead space at the top of every screen (double status-bar inset)
- Search indexing no longer runs on the main thread while typing
- Voice transcription retries no longer double-bill or duplicate transcripts
- Content no longer hides behind the floating nav bar
- Back button closes panels/editor instead of exiting the app
- Status bar icons follow the in-app theme
- First frame matches your theme (no dark flash on light themes)

## [v1.0.1] - 2026-07-11

### Added
- Proper app icon: cream note + terracotta tick on deep charcoal (adaptive + themed icon support)
- Warm paper-and-ink palette across all four themes, terracotta default accent
- New earthy accent set: ochre, sage, olive, oxblood, slate, taupe, forest

### Changed
- Warmer dark themes (no blue-black); splash screen matches the new palette
- De-AI'd visual identity — dropped the generic periwinkle-blue look for something with more character

### Fixed
- First-launch crash on devices without SQLite FTS5 support — search index rebuilt on FTS4

## [v1.0.0] - 2026-07-11 - Initial Release

First full release, built in milestones.

### Added
- Notes with live-markdown editor, full-text search, tags & folders, pin/archive, 30-day trash with undo
- AI clean-up and extract-to-calendar via OpenRouter (DeepSeek V4 Flash default, bring your own key)
- Voice rambles with Qwen3 ASR Flash transcription and inline audio chips
- Custom calendar with exact-alarm reminders (reboot-safe, snooze, Done/Snooze notification actions)
- Diary with moods, streaks, heat-map, "on this day", optional biometric lock
- 4 themes (Light / Dark / Pure Black / Grey) with 8 accents and animated switching
- Quick-settings tile, app-icon shortcuts, share-to-note capture
- Checksummed ZIP backup/restore (manual + scheduled)
- Signing config and CI workflow

[v3.3.0]: https://github.com/TheFadGhost/Notesapp-Codex-Private/releases/tag/v3.3.0
[v3.2.0]: https://github.com/TheFadGhost/Notesapp-Codex-Private/releases/tag/v3.2.0
[v3.1.0]: https://github.com/TheFadGhost/Notesapp-Codex-Private/releases/tag/v3.1.0
[v3.0.0]: https://github.com/TheFadGhost/Notesapp/releases/tag/v3.0.0
[v2.0.0]: https://github.com/TheFadGhost/Notesapp/releases/tag/v2.0.0
[v1.0.1]: https://github.com/TheFadGhost/Notesapp/releases/tag/v1.0.1
[v1.0.0]: https://github.com/TheFadGhost/Notesapp/releases/tag/v1.0.0
