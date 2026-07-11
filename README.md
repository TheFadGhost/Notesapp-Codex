# Notesapp

Personal AI-powered notes, diary and calendar for Android. Local-first, custom "Aura" UI (no stock Android components), OpenRouter AI integration.

## Features

- **Notes** — live-markdown editor (headings, bold/italic, checklists, smart lists), keyboard-docked formatting toolbar, undo/redo, draft crash-recovery, full-text search with highlights, tags & folders (colors, merge), pin/archive, 30-day trash with undo everywhere.
- **AI (bring your own OpenRouter key)** — ✨ Clean-up: streams a de-filler'd, structured rewrite of any ramble into a before/after sheet. 📅 Extract actions: pulls events/reminders/todos out of note text as confirm/deny cards with edit + free-text revision. Offline queueing, per-call cost tracking, model picker (default `deepseek/deepseek-v4-flash`).
- **Voice rambles** — record (auto-split 5-min AAC segments), transcribe via OpenRouter STT (default `qwen/qwen3-asr-flash-2026-02-10`), transcript lands in the note with a circular audio chip → popover player. Optional auto clean-up.
- **Calendar & reminders** — custom springy month/week/agenda views, exact alarms that survive reboots, Done/Snooze notification actions, battery-killer warnings, natural-language quick-add ("gym tomorrow 7am") parsed locally.
- **Diary** — day-per-entry with mood, streaks + heat-map, "on this day", rotating prompts, optional biometric gate, daily nudge.
- **Themes & feel** — Light / Dark / Pure Black (AMOLED) / Grey + 8 accents, circular-reveal theme switching, spring physics everywhere, haptics vocabulary, reduce-motion support, 120 Hz-friendly.
- **Capture fast** — quick-settings tile, app-icon shortcuts, share/selected-text into a note, translucent "+" capture sheet.
- **Private** — everything stored on-device; only network traffic is your own OpenRouter calls. Checksummed ZIP backup/restore (manual + scheduled). API key kept in Android Keystore, never in backups or logs.

## Install

1. Download the latest `app-release.apk` from [Releases](../../releases).
2. Open it on your phone; allow "install unknown apps" for your browser/files app when prompted.
3. Android may show a Play Protect warning (normal for sideloaded apps) — choose "Install anyway".
4. Open **Settings → AI** in the app and paste your [OpenRouter API key](https://openrouter.ai/keys).

## Build from source

Requires JDK 17 and the Android SDK (API 36).

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Release signing reads a `keystore.properties` outside the repo (see `app/build.gradle.kts`); CI builds are unsigned by design.

## Stack

Kotlin 2.1 · Jetpack Compose (custom design system, no Material widgets) · Room + FTS5 · Hilt · Ktor · WorkManager · minSdk 31 / targetSdk 36

Spec and roadmap: [PLAN.md](PLAN.md)
