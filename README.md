# Notesapp

Personal single-user Android app: notes + diary + calendar in one place, with
OpenRouter-powered AI, voice capture, and a fully custom ("Aura") UI. Distributed
as a sideloaded APK via GitHub Releases. See `PLAN.md` for the authoritative spec.

## Status — M0 (Foundation)

This milestone establishes the buildable foundation:

- Gradle project (Kotlin 2.x, AGP for SDK 36, Compose, version catalog, KSP, Hilt).
- Room database v1 (Note, Folder, Tag, NoteTagCrossRef, DiaryEntry, Event,
  Reminder) with an FTS5 external-content table + sync triggers, exported schema
  JSON, and Room migration-testing wiring.
- "Aura" theme token system (Light/Dark), CompositionLocal-provided, with instant
  switching backed by DataStore. No `MaterialTheme` in visible components.
- Custom single-activity Compose shell: edge-to-edge, translucent floating pill
  nav bar (spring-animated), and a spring-up capture bottom sheet.
- Settings skeleton with a working theme picker.

## Build

Requirements: JDK 17, Android SDK (platform 36, build-tools 36.0.0).

```
./gradlew.bat assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

Configuration lives in `local.properties` (SDK path) and `gradle.properties`
(`org.gradle.java.home` pins JDK 17). Both are environment-specific.

## Package

- applicationId / package: `com.fadghost.notesapp`
- minSdk 31, targetSdk 36, compileSdk 36
