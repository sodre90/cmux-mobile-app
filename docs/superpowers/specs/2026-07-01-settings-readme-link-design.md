# Settings README Link Design Spec

**Date:** 2026-07-01
**Component:** Android app — Settings screen
**File:** `android/app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsScreen.kt`
**Status:** not implemented. URLs below were updated 2026-08-08 for the repo
rename (`sodre90/cmux-app` → `sodre90/cmux-mobile-app`); the design itself is
unchanged from 2026-07-01.

## Overview

Add a help link to the Settings screen so a first-time user who only installs the Android app understands they need additional components set up (relay + bridge agent) before the app will work. The link opens the project's root README on GitHub, which documents the full system architecture and setup order.

## Problem

Someone who installs the app APK directly (without prior context) sees a form asking for a bridge URL, device token, and client certificate — with no explanation of what those are or how to obtain them. The root README (`README.md` at the repo root) already documents:
- The system architecture (Android app ↔ relay ↔ Mac bridge agent)
- The quick-start setup order (relay → agent → pair phone → configure app)

Surfacing a link to this document from the Settings screen gives new users a path to the missing context without duplicating documentation inside the app.

## Design

**What:** A `TextButton` labeled "Setup guide" placed above the existing form fields in `SettingsScreen.kt`.

**Behavior:** Tapping it launches an implicit `Intent(Intent.ACTION_VIEW, Uri.parse(url))` pointing to:
```
https://github.com/sodre90/cmux-mobile-app
```
This opens in the device's default browser. No new Android permissions are required (the app already declares `INTERNET`; browser launch via implicit intent doesn't need it anyway).

**Placement:** First item in the Settings screen's scrollable `Column`, before the "Bridge base URL" field — so it's the first thing a new user encounters before filling in connection details.

**Visual style:** `TextButton`, not a filled `Button` — reads as a secondary/informational action so it doesn't compete visually with the primary "Save & connect" action at the bottom.

## Non-Goals

- No in-app rendering of README content (link out to GitHub instead — avoids bundling/parsing Markdown, and the GitHub-rendered version is easier to read than plain text in-app).
- No change to `SettingsViewModel` or persisted settings — this is a static, non-configurable link.
- No deep-linking to a specific README section — links to the repo root, where the README's Quick start section is visible.

## Technical Details

**File modified:** `android/app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsScreen.kt`

**New imports needed:**
```kotlin
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.TextButton
```

**Implementation sketch:**
```kotlin
TextButton(
    onClick = {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sodre90/cmux-mobile-app")),
        )
    },
) {
    Text("Setup guide")
}
```

(`context` is already available in `SettingsScreen` via `LocalContext.current`.)

## Success Criteria

- [ ] "Setup guide" link/button appears at the top of the Settings screen, above the Bridge base URL field
- [ ] Tapping it opens `https://github.com/sodre90/cmux-mobile-app` in the device's browser
- [ ] No new Android permissions required
- [ ] No changes to `SettingsViewModel` or persisted settings data
- [ ] Existing Settings screen functionality (all fields, save button) unaffected
