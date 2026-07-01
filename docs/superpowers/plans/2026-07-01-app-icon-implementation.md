# App Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create and integrate a professional layered terminal window icon (cyan/magenta) for the cmux-app Android and macOS components.

**Architecture:** Design an SVG master icon with two overlapping terminal frames, then export to platform-specific formats. Android receives PNG variants at five density buckets; macOS receives an ICNS bundle. Update the Android manifest to reference the new icon resource. Verify on test devices.

**Tech Stack:** SVG (master format), ImageMagick/ffmpeg or Figma export (PNG generation), iconutil (macOS ICNS), Gradle build system (Android)

## Global Constraints

- **Colors (exact):** Cyan #00D9FF, Magenta #FF1493, White #FFFFFF, Dark #0A0E27
- **SVG canvas:** 1024×1024px
- **Android sizes:** 48, 72, 96, 144, 192px (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
- **macOS:** 512×512px source, exported to ICNS
- **Style:** Flat, clean lines, no gradients or drop shadows
- **Platforms:** Android (Kotlin/Compose) + macOS (Go bridge agent)

---

## Task 1: Create SVG Master Icon

**Files:**
- Create: `android/app/src/main/res/drawable/app_icon.svg`

**Interfaces:**
- Produces: Valid SVG file with layered terminal windows (1024×1024px canvas), colors matching spec, exportable without quality loss

- [ ] **Step 1: Create SVG file with base structure**

Create the file at `android/app/src/main/res/drawable/app_icon.svg`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <!-- Dark background -->
  <rect width="1024" height="1024" fill="#0A0E27"/>
  
  <!-- Secondary frame (magenta, background) - offset down and right -->
  <g id="secondary-frame">
    <rect x="280" y="380" width="480" height="360" fill="none" stroke="#FF1493" stroke-width="32" rx="8"/>
  </g>
  
  <!-- Primary frame (cyan, foreground) - overlapping -->
  <g id="primary-frame">
    <rect x="200" y="300" width="480" height="360" fill="none" stroke="#00D9FF" stroke-width="32" rx="8"/>
  </g>
  
  <!-- Prompt symbols inside primary frame -->
  <g id="prompt-symbols" font-family="monospace" font-size="56" font-weight="bold" fill="#FFFFFF">
    <text x="240" y="500">$</text>
    <text x="900" y="500">></text>
  </g>
</svg>
```

- [ ] **Step 2: Verify SVG renders correctly**

Open the SVG file in a web browser or vector editor (Figma, Inkscape, etc.) to confirm:
- Dark background fills the 1024×1024 canvas
- Secondary frame (magenta) is visible and offset
- Primary frame (cyan) overlaps correctly
- Prompt symbols are readable and positioned within the frames
- No rendering errors or warnings

- [ ] **Step 3: Commit SVG master**

```bash
cd /Users/perdos/prj/cmux-app
git add android/app/src/main/res/drawable/app_icon.svg
git commit -m "feat: create app icon SVG master (layered terminal design)"
```

---

## Task 2: Set Up Android Mipmap Directory Structure

**Files:**
- Create: `android/app/src/main/res/mipmap-mdpi/`
- Create: `android/app/src/main/res/mipmap-hdpi/`
- Create: `android/app/src/main/res/mipmap-xhdpi/`
- Create: `android/app/src/main/res/mipmap-xxhdpi/`
- Create: `android/app/src/main/res/mipmap-xxxhdpi/`

**Interfaces:**
- Consumes: SVG master from Task 1
- Produces: Empty mipmap directories (to be populated with PNG exports in Task 3)

- [ ] **Step 1: Create mipmap directories**

```bash
cd /Users/perdos/prj/cmux-app/android/app/src/main/res
mkdir -p mipmap-mdpi mipmap-hdpi mipmap-xhdpi mipmap-xxhdpi mipmap-xxxhdpi
```

- [ ] **Step 2: Verify directory structure**

```bash
ls -la /Users/perdos/prj/cmux-app/android/app/src/main/res/ | grep mipmap
```

Expected output shows five `mipmap-*` directories.

---

## Task 3: Export PNG Variants for Android Densities

**Files:**
- Create: `android/app/src/main/res/mipmap-mdpi/ic_launcher.png` (48×48px)
- Create: `android/app/src/main/res/mipmap-hdpi/ic_launcher.png` (72×72px)
- Create: `android/app/src/main/res/mipmap-xhdpi/ic_launcher.png` (96×96px)
- Create: `android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` (144×144px)
- Create: `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (192×192px)

**Interfaces:**
- Consumes: SVG file from Task 1 at `android/app/src/main/res/drawable/app_icon.svg`
- Produces: Five PNG files at specified densities, one per mipmap directory

- [ ] **Step 1: Export PNGs using ImageMagick**

ImageMagick's `convert` command can scale and export SVG to PNG. For each density:

```bash
# mdpi (48×48)
convert -density 150 -resize 48x48 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-mdpi/ic_launcher.png

# hdpi (72×72)
convert -density 150 -resize 72x72 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-hdpi/ic_launcher.png

# xhdpi (96×96)
convert -density 150 -resize 96x96 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-xhdpi/ic_launcher.png

# xxhdpi (144×144)
convert -density 150 -resize 144x144 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png

# xxxhdpi (192×192)
convert -density 150 -resize 192x192 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
```

If ImageMagick is not installed, use Figma, Inkscape, or an online SVG-to-PNG tool to export each size individually. Ensure:
- No quality loss or pixelation at small sizes
- Cyan and magenta colors remain distinct
- Prompt symbols are readable even at 48px

- [ ] **Step 2: Verify PNG files exist and have correct dimensions**

```bash
file /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-*/ic_launcher.png
identify /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-*/ic_launcher.png
```

Expected output shows five PNG files with correct dimensions (48×48, 72×72, 96×96, 144×144, 192×192).

- [ ] **Step 3: Visual inspection at smallest size**

Open the 48×48 PNG (mipmap-mdpi) in an image viewer. Verify:
- Cyan and magenta frames are distinct
- Prompt symbols are still readable
- No blur or pixelation
- Colors match the spec (#00D9FF, #FF1493, #FFFFFF, #0A0E27)

---

## Task 4: Create macOS ICNS File (Optional, if Bridge Has GUI)

**Files:**
- Create: `bridge/assets/` (if not already present)
- Create: `bridge/assets/app_icon.icns` (or `bridge/assets/app_icon_256.png` as fallback)

**Interfaces:**
- Consumes: SVG file from Task 1
- Produces: ICNS bundle for macOS app launcher or high-res PNG fallback

- [ ] **Step 1: Determine if macOS bridge needs GUI icon**

Check if the cmux-bridge agent on macOS has a graphical launcher or just runs as a background service:

```bash
grep -r "AppDelegate\|NSApplication\|cocoa" /Users/perdos/prj/cmux-app/bridge/cmd/cmux-bridge/ || echo "No GUI detected"
```

If no GUI is found, skip to Step 4 (create PNG fallback).

- [ ] **Step 2: Export 512×512 PNG from SVG**

If GUI is present, create a high-resolution PNG first:

```bash
convert -density 150 -resize 512x512 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/bridge/assets/app_icon_512.png
```

- [ ] **Step 3: Convert PNG to macOS ICNS**

Install ImageMagick if not already present, then use a tool like `png2icns` or Figma export:

```bash
# If png2icns is available
png2icns /Users/perdos/prj/cmux-app/bridge/assets/app_icon.icns /Users/perdos/prj/cmux-app/bridge/assets/app_icon_512.png
```

Alternatively, export ICNS directly from Figma by uploading the SVG, resizing to 512×512, and exporting as ICNS.

- [ ] **Step 4: Create PNG fallback if no GUI launcher**

If the bridge has no GUI, create a simple 256×256 PNG fallback:

```bash
mkdir -p /Users/perdos/prj/cmux-app/bridge/assets
convert -density 150 -resize 256x256 /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg /Users/perdos/prj/cmux-app/bridge/assets/app_icon_256.png
```

- [ ] **Step 5: Verify ICNS or PNG**

```bash
file /Users/perdos/prj/cmux-app/bridge/assets/app_icon.*
```

Expected: ICNS bundle or PNG file with correct format.

---

## Task 5: Update Android Manifest to Reference New Icon

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: PNG files from Task 3 in mipmap directories
- Produces: Updated manifest that references `@mipmap/ic_launcher` instead of default

- [ ] **Step 1: Open AndroidManifest.xml**

```bash
cat /Users/perdos/prj/cmux-app/android/app/src/main/AndroidManifest.xml
```

- [ ] **Step 2: Locate android:icon attribute**

Find the line with `android:icon="@android:drawable/sym_def_app_icon"` (currently around line 10).

- [ ] **Step 3: Update the icon reference**

Change:
```xml
android:icon="@android:drawable/sym_def_app_icon"
```

To:
```xml
android:icon="@mipmap/ic_launcher"
```

The complete `<application>` tag should now read:

```xml
<application
    android:name=".CmuxApp"
    android:allowBackup="false"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:supportsRtl="true"
    android:theme="@style/Theme.CmuxRemote">
```

- [ ] **Step 4: Verify syntax**

```bash
grep "android:icon=" /Users/perdos/prj/cmux-app/android/app/src/main/AndroidManifest.xml
```

Expected output: `android:icon="@mipmap/ic_launcher"`

- [ ] **Step 5: Commit manifest change**

```bash
cd /Users/perdos/prj/cmux-app
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat: update app icon reference to custom mipmap asset"
```

---

## Task 6: Build Android App and Verify Icon

**Files:**
- No new files created; uses existing gradle build system

**Interfaces:**
- Consumes: Updated manifest from Task 5, PNG assets from Task 3
- Produces: Debug APK with new icon embedded

- [ ] **Step 1: Build debug APK**

```bash
cd /Users/perdos/prj/cmux-app/android
./gradlew :app:assembleDebug
```

Expected output ends with `BUILD SUCCESSFUL` and shows APK path: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: Install APK on emulator or connected device**

```bash
adb install -r /Users/perdos/prj/cmux-app/android/app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success` message.

- [ ] **Step 3: Launch app on emulator/device**

Open the Android emulator or connect a physical device, then:

```bash
adb shell am start -n com.sodre90.cmuxremote/.MainActivity
```

- [ ] **Step 4: Visually verify icon on home screen**

Check the home screen or app drawer:
- Icon displays correctly (cyan and magenta frames visible)
- No distortion or pixelation
- Matches the design spec
- Distinct from system icons

- [ ] **Step 5: Verify in app settings**

Open Android Settings → Apps → cmux remote (or similar):
- Icon appears correctly in app details screen
- Icon matches home screen appearance

---

## Task 7: Commit All Assets and Icon

**Files:**
- No modifications; assets already created in Tasks 1–4

**Interfaces:**
- Consumes: All PNG exports, SVG master, manifest update, optional ICNS
- Produces: Clean git history with icon assets committed

- [ ] **Step 1: Verify all assets are present**

```bash
ls -la /Users/perdos/prj/cmux-app/android/app/src/main/res/drawable/app_icon.svg
ls -la /Users/perdos/prj/cmux-app/android/app/src/main/res/mipmap-*/ic_launcher.png
```

- [ ] **Step 2: Add PNG assets to git**

```bash
cd /Users/perdos/prj/cmux-app
git add android/app/src/main/res/mipmap-mdpi/ic_launcher.png
git add android/app/src/main/res/mipmap-hdpi/ic_launcher.png
git add android/app/src/main/res/mipmap-xhdpi/ic_launcher.png
git add android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png
git add android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
```

- [ ] **Step 3: Add macOS icon if present**

If Task 4 created an ICNS or PNG:

```bash
git add bridge/assets/app_icon.icns
# OR
git add bridge/assets/app_icon_256.png
```

- [ ] **Step 4: Commit all icon assets**

```bash
git commit -m "feat: add app icon assets (PNG and SVG master)"
```

- [ ] **Step 5: Verify git history**

```bash
git log --oneline -5
```

Should show recent commits for SVG, manifest update, and assets.

---

## Success Criteria Verification

After completing all tasks, verify against the spec:

- [ ] **Icon renders clearly at 48px** — Inspect mipmap-mdpi/ic_launcher.png; no pixelation or blur
- [ ] **Icon is recognizable on Android home screen** — Build and run; visually confirm
- [ ] **Cyan and magenta remain distinct** — Check all five PNG variants; colors should match spec hex values
- [ ] **SVG source is version-controlled** — Confirm `android/app/src/main/res/drawable/app_icon.svg` exists in git
- [ ] **Android app displays new icon in launcher** — Run app on emulator/device; verify home screen and app details
- [ ] **macOS version displays correctly (if applicable)** — Confirm ICNS or PNG exists; visually test if GUI launcher present

---

## Notes

- **ImageMagick:** If `convert` command is not available, install with `brew install imagemagick` on macOS.
- **Figma alternative:** Export PNGs directly from Figma by uploading the SVG, resizing, and batch-exporting at all required sizes.
- **Icon caching:** After installing the APK on a device, you may need to force stop the app or clear app cache to see the new icon update immediately.
- **Future enhancement:** Consider adaptive icons (Android 8+) with a circular safe zone in the center to prevent edge clipping on some devices.
