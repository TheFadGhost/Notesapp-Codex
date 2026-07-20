# Notesapp Codex

Personal AI-powered notes, diary and calendar for Android â€” local-first, with a custom paper-and-ink design system and your own OpenRouter key for the AI bits.

![version](https://img.shields.io/badge/version-v4.0.0-8a5a44?style=flat-square)
![platform](https://img.shields.io/badge/platform-Android%2012%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![kotlin](https://img.shields.io/badge/Kotlin-2.1-7f52ff?style=flat-square&logo=kotlin&logoColor=white)
![compose](https://img.shields.io/badge/Jetpack%20Compose-custom%20UI-4285f4?style=flat-square&logo=jetpackcompose&logoColor=white)

Notesapp Codex is the actively developed continuation of Notesapp: a single-user Android app for notes, a diary and a calendar, with optional AI clean-up and reminder extraction through OpenRouter, voice-to-text capture, and a hand-tuned "Aura" design system instead of stock Material widgets. It's distributed as a sideloaded APK, not through the Play Store.

## Features

**Notes**
- Live-markdown editor (headings, bold/italic, checklists, smart lists) with a keyboard-docked formatting toolbar
- Undo/redo, draft crash-recovery
- Full-text search with highlights
- Tags with color, rename, merge and tag-filter panel; pin/archive
- 30-day trash with undo everywhere

**AI (bring your own OpenRouter key)**
- Clean-up: streams a de-filler'd, structured rewrite of any ramble into a before/after sheet
- Extract actions: pulls events/reminders/todos out of note text as confirm/deny cards with edit + free-text revision
- Ask Folio: a fifth tab that searches notes + memory, streams grounded answers, and links personal claims back to their sources
- Offline queueing, per-call cost tracking, model picker (default `deepseek/deepseek-v4-flash`) with recommended OpenRouter text and transcription models

**Voice**
- Record long voice rambles in a foreground service, including with the app backgrounded (auto-split 5-minute AAC segments)
- Transcribe via OpenRouter STT (default `openai/gpt-4o-mini-transcribe`)
- Folio rewrites each ramble into a note, then shows confirm cards before adding reminders/events
- Tap-toggle or hold-to-talk recording; durable processing/review survives process death
- Permission-gated compact draggable overlay with Pause/Resume, Stop, Discard and Open controls
- Every completed ramble starts with a removable `Rambler` tag; deleting it from a note never affects the note itself
- Existing-note transcripts land with a circular audio chip and popover player, with optional clean-up

**Calendar & reminders**
- Custom springy month/week/agenda views
- Exact alarms that survive reboots, with Done/Snooze notification actions
- Event alerts with selectable lead times; duplicate reminder/event delivery is atomically suppressed
- Extracted reminders link back to their source note
- Natural-language quick-add ("gym tomorrow 7am") parsed locally
- Battery-killer warnings for reliable delivery
- Optional event end times and actionable exact-alarm/notification permission checks

**Diary**
- Day-per-entry with mood, streaks and a heat-map
- "On this day", rotating prompts
- Transcript-only microphone inserts at the current selection without creating a stray note or retaining audio
- Optional biometric gate, daily nudge

**Design & themes**
- Light / Dark / Pure Black (AMOLED) / Grey, each with 8 accents
- Circular-reveal theme switching, spring physics throughout, haptics vocabulary
- Editorial typography (Fraunces + Hanken Grotesk), warm paper-layer depth system
- Press feedback on every control, reduce-motion support, 120 Hz-friendly

**Capture fast**
- Quick-settings tile, app-icon shortcuts, share/selected-text into a note
- Bottom-right contextual FAB with an anchored capture panel
- Share notes and diary entries as locally generated PDFs

**Privacy**
- Everything stored on-device; the only network traffic is your own OpenRouter calls
- Strictly validated, checksummed manual ZIP backup/restore; corrupt or unexpected payloads are blocked before restore
- API key kept in Android Keystore, never written to backups or logs

## Screenshots

| Notes | Capture panel | Diary |
|:---:|:---:|:---:|
| <img src="docs/screenshots/notes.png" width="240"> | <img src="docs/screenshots/capture-panel.png" width="240"> | <img src="docs/screenshots/diary.png" width="240"> |

| Calendar | Settings |
|:---:|:---:|
| <img src="docs/screenshots/calendar.png" width="240"> | <img src="docs/screenshots/settings.png" width="240"> |

## Install

1. On Android 12 or newer, download the signed `Notesapp-Codex-v4.0.0.apk` from the [private Releases page](https://github.com/TheFadGhost/Notesapp-Codex-Private/releases/latest).
2. Open it on your phone; allow "install unknown apps" for your browser/files app when prompted.
3. Android may show a Play Protect warning â€” this is normal for sideloaded apps not distributed through the Play Store; choose "Install anyway".
4. For optional AI and transcription features, open **Settings â†’ AI** and paste your own [OpenRouter API key](https://openrouter.ai/keys). Notes, diary, calendar, search and local reminders work without a key; there is no bundled key or backend.

## Build from source

Requires JDK 17 and Android SDK 36.

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Release builds are signed using a `keystore.properties` file kept outside the repo. Point Gradle at it with the `NOTESAPP_KEYSTORE_PROPS` environment variable (see `app/build.gradle.kts`); CI builds are unsigned by design.
Unsigned CI outputs are validation-only and are never published as installable APK artifacts.

## Tech stack

Kotlin 2.1 Â· Jetpack Compose (custom design system, no Material widgets) Â· Room + FTS4 Â· Hilt Â· Ktor Â· WorkManager Â· minSdk 31 / targetSdk 36

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release notes. Spec and roadmap live in [PLAN.md](PLAN.md).

## License

[MIT](LICENSE)
