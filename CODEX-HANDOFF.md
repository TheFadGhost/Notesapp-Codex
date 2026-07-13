# Notesapp Codex — Complete AI Handoff

Last updated: 2026-07-13

This document is a self-contained, secret-free handoff for continuing the **Notesapp Codex** Android application. It describes the repository split, implemented work, locked product decisions, important constraints, verification already completed, known caveats, and safe next steps.

## 1. Repository and paths

- Working repository: `C:\Users\Work\Documents\Notesapp\Notesapp Codex`
- Original repository: `C:\Users\Work\Documents\Notesapp`
- Intended public GitHub repository: `https://github.com/TheFadGhost/Notesapp-Codex`
- Original upstream repository: `https://github.com/TheFadGhost/Notesapp.git`
- Main branch: `main`
- Android package/application ID remains the existing project package so upgrades and migrations continue to work.
- Root project name and visible app label are **Notesapp Codex**.
- Current app version: `versionCode 5`, `versionName 3.1.0`.

The original repository locally excludes `/Notesapp Codex/`, so the new repository is independent and cannot accidentally be committed as a nested folder in the original project.

### Git history at handoff

- `e490175` — `feat: complete Notesapp Codex v3.1`
- `60ac853` — `chore: establish Notesapp Codex repository`
- `5b8dfb0` — `feat: add v3.1 ramble processing foundation`
- `932577b` — `release: v3.0.0 — attachments + Folio memory foundation`

The commit adding this handoff follows those commits.

## 2. Critical security rules

1. **Never copy or commit** `C:\Users\Work\Documents\Notesapp\HANDOFF.md`. It is a private, gitignored Claude handoff and contains a temporary testing credential.
2. This public handoff deliberately contains no API key or secret.
3. The temporary testing credential should be revoked at `https://openrouter.ai/keys` after use.
4. OpenRouter keys belong in the app's Settings → AI screen or another local secret store, never in source, Gradle files, documentation, test fixtures, logs, commits, issues, or pull requests.
5. Release signing configuration is local. `NOTESAPP_KEYSTORE_PROPS` points to `C:\Users\Work\.android-keys\notesapp-keystore.properties`. Never publish that file, its contents, or the keystore.
6. A repository scan before upload found no OpenRouter key pattern and no copied private `HANDOFF.md` in this repository.
7. Debug AI networking logs use headers only, not request/response bodies, so notes, diary text, transcripts, images, and model answers are not dumped to Logcat.

## 3. Product and technical constraints

These are deliberate decisions and should be preserved unless the owner explicitly changes them:

- UI uses the custom **Aura** design system. Do not introduce Material widgets or a Material visual language.
- Search uses Room **FTS4**, not FTS5.
- Database is now version 9. Future schema changes must include a migration, schema export, migration tests, and runtime-open verification.
- Primary phone target is an Oppo-sized 411dp layout. Compact 320dp behavior is also protected.
- Ramble behavior is confirm-cards-first: transcribe → rewrite/organize → create note → present extracted actions for confirmation.
- Background voice recording uses a foreground microphone service now.
- Floating overlay bubble and spoken voice-stop are intentionally deferred to v3.2.
- Folder operations remain non-destructive; organization and restore behavior must not silently delete user data.
- A date-only reminder such as “remind me on Friday” defaults to **08:00 in the captured local timezone**.
- An appointment becomes an event. An intended task with a date becomes a reminder. Dateless extracted actions stay in the note.
- Ask action extraction and memory saving always require confirmation before writing.

## 4. Implemented in Notesapp Codex v3.1

### 4.1 Voice ramble capture and durable pipeline

The ramble feature is implemented around an Activity-independent foreground microphone service:

- `app/src/main/java/.../service/RecordingService.kt`
  - foreground microphone service;
  - pause, resume, stop, and discard notification controls;
  - waveform and timer state;
  - five-minute segment rollover;
  - recovery from Activity loss and interruptions.
- `data/audio/RecordingServiceClient.kt`
- `data/audio/VoiceSessionModels.kt`
- `data/audio/VoiceSessionStore.kt`
- `data/audio/AudioStorage.kt`
- `data/audio/AudioRecorder.kt`
- `data/audio/AudioSegments.kt`

Recording sessions use collision-proof, session-scoped directories so segment names cannot overwrite another session. An `AtomicFile` journal stores phase, result, error, and review-card state for recovery.

`data/ai/work/RamblePipelineWorker.kt` implements an idempotent durable chain:

1. transcribe audio segments;
2. rewrite/organize the transcript;
3. commit the note;
4. attach the audio;
5. extract V2 action candidates;
6. persist review cards.

If rewriting fails with a non-transient error, the raw transcript is retained rather than losing the user's capture.

`data/ai/parse/RambleActionNormalizer.kt` applies the locked classification rules, including the 08:00 date-only default and captured timezone behavior. `AiPrompts.kt` and `AiRepository.kt` contain the current ramble extraction/revision prompts and repository flow.

UI integration is in:

- `ui/voice/RambleViewModel.kt`
- `ui/voice/RambleButton.kt`
- `ui/voice/RambleGestureStateMachine.kt`
- `ui/voice/RambleCaptureSheet.kt`
- `ui/voice/TranscriptInsertion.kt`

The control supports tap-to-toggle and hold/release recording with slip tolerance. AppShell opens the capture sheet, and action cards survive process/UI recreation until confirmed or dismissed.

The manifest includes the foreground-service and microphone permissions and registers `.service.RecordingService` as non-exported with `foregroundServiceType="microphone"` and `stopWithTask="false"`.

### 4.2 Diary voice transcriber

Implemented in:

- `ui/diary/DiaryVoiceViewModel.kt`
- `ui/diary/DiaryVoiceCaptureSheet.kt`
- `ui/diary/TranscriptInsertion.kt`
- integrations in `DiaryScreen.kt` and `DiaryViewModel.kt`

Diary voice capture uses the same durable service with `VoiceDestination.DIARY_TRANSCRIPT`, but intentionally does **not** create a Note or attachment. Audio remains transient, survives offline/interruption recovery, and is removed after insertion or discard. Transcribed text is inserted at the current `TextField` selection/caret.

Today and past-date editors have a 48dp microphone entry point. Per-date save jobs prevent switching dates from cancelling another date's pending save.

Both diary and ramble flows request microphone and notification permissions. Microphone permission is a hard requirement; notification denial produces a quiet warning rather than corrupting the session.

### 4.3 Ask tab

Implemented in:

- `data/ask/AskPrompts.kt`
- `data/ask/AskModels.kt`
- `data/ask/AskRepository.kt`
- `ui/ask/AskViewModel.kt`
- `ui/ask/AskScreen.kt`

Ask is the fifth navigation destination and uses the Aura sparkle icon. It searches Note FTS and Folio memory FTS, builds bounded source context, streams the answer, and records cost information.

For small memory collections (fewer than 40 entries) with a direct FTS match, the extra memory-routing request is skipped. Larger/ambiguous collections use the router. Model-produced source IDs are validated before citation chips are displayed. Note chips deep-link to the editor; memory chips open a memory overlay.

Trailing `SAVE_MEMORY` and `EXTRACT_ACTIONS` markers are removed from visible answers and converted into confirmation flows using the existing MemorySheet and ExtractSheet. Ask currently keeps only the live conversation; persistent chat history, graph/browser work, and other scope expansion were deliberately not added.

### 4.4 Database v9, reminders, events, and notifications

Room database version 9 adds:

- `Reminder.sourceNoteId`, with foreign key `ON DELETE SET NULL`;
- `Reminder.lastNotifiedTriggerAt`;
- `Event.notificationLeadMinutes`;
- `Event.lastNotifiedOccurrenceAt`.

Relevant files include `Migrations.kt`, `NotesDatabase.kt`, `DatabaseModule.kt`, DAOs/entities, and exported schema `app/schemas/.../9.json`. Production migrations come from a shared registry so application and test migration lists cannot drift.

Notification work includes:

- `EventAlarm.kt`
- `EventAlarmScheduler.kt`
- `EventAlarmReceiver.kt`
- `ReminderAlarmMath.kt`
- `EventNotificationMath.kt`
- `EventNotifier.kt`

Atomic claim/release operations prevent duplicate reminder and event notifications. Event lead options are Off, At time, 10 minutes, 30 minutes, 1 hour, and 1 day.

The manifest declares `USE_EXACT_ALARM` and limits legacy `SCHEDULE_EXACT_ALARM` to API 32. Boot/package-replacement/exact-alarm-permission rescheduling is handled by a non-exported `BootReceiver`.

Reminder notifications prefer opening a still-live source note and fall back to Calendar. Event notifications open the calendar sheet. AI/editor and ramble insertion paths pass an optional source note ID.

### 4.5 Backup and restore integrity

Backup v2 hardening is implemented in:

- `data/backup/BackupManager.kt`
- `data/backup/BackupModels.kt`
- `data/backup/BackupSerializer.kt`
- `data/backup/AttachmentRestoreFiles.kt`
- repository, DAO, ViewModel, and Settings integrations.

The format validates supported versions, manifest counts, declared sizes, checksums, path allowlists, duplicate/unexpected entries, and archive limits. Current bounds are 4,096 entries, 64 MiB per entry, and 128 MiB total extracted content. Unsafe or corrupt previews block restore.

Backup v2 includes live and trashed notes, folders, tags, diary entries, events, reminders, attachments, and Folio memory. Memory restore explicitly offers MERGE or REPLACE, uses a staged swap, regenerates its index, and propagates failures.

Strict replace/import paths were added to repositories and DAOs. Tag restore uses normalized lookup plus conflict-safe insertion so a tag such as `" Work "` cannot replace `"Work"` and cascade-delete assignments.

Known architectural limit: a full note-plus-memory restore cannot be one transaction across Room and filesystem state. Settings/preferences and Ask conversation history are not currently part of backups.

### 4.6 Organize experience

Editor organization uses `ui/editor/OrganizePanel.kt` plus Editor screen/ViewModel integration:

- one 48dp Organize entry point;
- tags first;
- single-select folder;
- multi-select tags;
- inline normalized creation;
- search when more than eight options exist;
- anchored, clamped, scrollable popup behavior.

Notes-list organization uses `ui/notes/OrganizeFilterPanel.kt`, FilterBar, and NotesScreen integration. Tags remain first; one Organize popup owns folders and special filters; folders are no longer duplicated in a parallel row; tag long-press management stays reachable; filter semantics remain single-choice.

### 4.7 Privacy, release metadata, and repository hygiene

- Debug OpenRouter logging was reduced from full bodies to headers.
- OpenRouter attribution uses `https://github.com/TheFadGhost/Notesapp-Codex` and title `Notesapp Codex`.
- `README.md` and `CHANGELOG.md` describe v3.1.0 and its privacy behavior.
- `.gitattributes` enforces appropriate line endings and marks `gradlew` executable for Linux CI.
- `.github/workflows/ci.yml` targets `main`.
- No Material dependency/widget expansion was introduced.
- FTS4 remains unchanged.

## 5. Verification completed before the owner's stop-testing instruction

The owner later explicitly instructed the AI to stop debugging/testing and only finish code/documentation/upload. Therefore, no further app tests should be run as part of this handoff unless the owner changes that instruction.

Before that instruction, the following evidence was recorded:

- debug Kotlin and debug unit-test Kotlin compilation passed;
- full JVM suite: 296 passed, 0 failed, 0 errors, 0 skipped at the recorded checkpoint;
- ramble-focused suite: 49/49 passed;
- schema/alarm-focused suite: 28/28 passed;
- backup/organize/diary-helper suite: 31/31 passed;
- API 36 emulator Room migration/runtime-open suite: 4/4 passed;
- navigation coverage was updated for five tabs;
- at 320dp the FAB yields space to preserve the tabs; at the 411dp target the FAB remains visible.

These results describe the checkpoint at which they were run, not a claim that every later documentation/repository-only change was retested.

### Not yet exercised

The real OpenRouter ramble path—transcribe → organize → persist → arm review cards—has **not** been conclusively smoke-tested on a physical device/emulator with a live temporary key in the final phase. If testing is authorized later, use a temporary/restricted key, inspect the end-to-end result, then revoke the key immediately. Do not put it in this repository.

## 6. Local Android toolchain available

Android Studio is installed. The local Android SDK is at:

`C:\Users\Work\AppData\Local\Android\Sdk`

Recorded installed components include command-line tools 20.0, platform-tools 37.0.0, emulator 36.6.11, API 36 platform/build tools, and an API 36 Google APIs x86_64 system image.

An AVD named `testphone` exists with API 36 Google APIs x86_64, 1080×2400 at 420dpi, approximating the 411dp product target. This information is for a future authorized test session; do not launch or test merely because this document mentions it.

## 7. How to continue safely

1. Work only in `C:\Users\Work\Documents\Notesapp\Notesapp Codex`.
2. Read this document, `README.md`, `CHANGELOG.md`, and the relevant implementation files before changing code.
3. Preserve the locked decisions in section 3.
4. Never copy the original private `HANDOFF.md` or any credential into this repository.
5. For schema changes: increment the Room version, add a production migration, export the schema, and—when testing is authorized—add migration/runtime-open coverage.
6. Keep Aura controls and FTS4 unless the owner explicitly approves a product/architecture change.
7. Keep commits narrow and review `git status` plus a secret-pattern scan before every public push.
8. Do not rewrite or force-push shared history unless explicitly asked.
9. Treat overlay recording controls and spoken stop as future v3.2 work, not missing v3.1 code.

## 8. GitHub setup

The intended remote layout is:

```text
origin    https://github.com/TheFadGhost/Notesapp-Codex.git
upstream  https://github.com/TheFadGhost/Notesapp.git
```

`origin` is the independent Codex continuation. `upstream` is reference-only unless the owner explicitly requests syncing.

If a future AI must create the remote after GitHub CLI authentication:

```powershell
gh repo create Notesapp-Codex --public --source . --remote origin --push --description "Local-first Android notes, diary, calendar and Folio AI — Codex continuation"
```

If it already exists:

```powershell
git remote add origin https://github.com/TheFadGhost/Notesapp-Codex.git
git push -u origin main
```

Before either command, confirm that the current directory is the Notesapp Codex repository and that no secret/private handoff is tracked.

## 9. Definition of the current handoff state

Notesapp Codex v3.1 code is implemented, isolated in its own repository, documented, and prepared for a public `main` push. The remaining immediate operation is repository publication if it has not already happened. Future product work should start from v3.2 scope or from issues discovered during an owner-authorized real-device smoke test—not by rebuilding the completed v3.1 features.
