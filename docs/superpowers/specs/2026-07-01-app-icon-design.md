# App Icon Design Spec

**Date:** 2026-07-01  
**Platforms:** Android app + macOS bridge agent  
**Approach:** Layered Terminal Windows (two-tone)

## Overview

A unified app icon for cmux-app's Android client and macOS components. The design uses overlapping terminal window frames in cyan and magenta to suggest both the terminal control aspect and the remote bridge connection between devices.

## Visual Design

### Concept

Two slightly offset terminal window frames:
- **Primary frame** (foreground): Cyan (#00D9FF)
- **Secondary frame** (background, offset down and right): Magenta (#FF1493)
- **Terminal prompt symbols:** Small `$` or `>` characters in white/light gray within the frames
- **Background:** Deep dark (#0A0E27)
- **Style:** Flat, clean lines, no gradients or drop shadows

The layering creates visual depth and subtly conveys the remote connection concept—data flowing from one device to another through the relay.

### Rationale

- **Terminal-focused:** The window frames and prompt symbols immediately signal "terminal" or "command-line control"
- **Remote connection:** The two overlapping frames suggest communication between two entities (Mac and Android)
- **Professional aesthetic:** Dark background with high-contrast accents feels sophisticated and modern
- **Scalable:** Flat design with clean geometry scales well from 1024px down to 48px (minimum icon size)

## Color Palette

| Element | Color | Hex |
|---------|-------|-----|
| Primary frame | Cyan | #00D9FF |
| Secondary frame | Magenta | #FF1493 |
| Prompt symbols | White | #FFFFFF |
| Background | Dark | #0A0E27 |

These colors maintain high contrast at small sizes and work on both light and dark lock screens.

## Technical Specifications

### Master Format

- **SVG file:** `android/app/src/main/res/drawable/app_icon.svg`
- **Canvas size:** 1024×1024px
- **Minimum readable size:** 48px (but scales down further if needed)

### Android Assets

PNG exports at standard Android densities:
- `res/mipmap-mdpi/ic_launcher.png` (48×48px)
- `res/mipmap-hdpi/ic_launcher.png` (72×72px)
- `res/mipmap-xhdpi/ic_launcher.png` (96×96px)
- `res/mipmap-xxhdpi/ic_launcher.png` (144×144px)
- `res/mipmap-xxxhdpi/ic_launcher.png` (192×192px)

### macOS Bridge

- **App icon:** ICNS bundle exported from SVG (512×512px source)
- **Location:** `bridge/assets/app_icon.icns` (if the bridge agent has a GUI launcher)
- **Fallback:** High-resolution PNG (256×256px minimum) if no launcher GUI exists

## Manifest Integration

**Android:**
- Update `android/app/src/main/AndroidManifest.xml`
- Change `android:icon="@android:drawable/sym_def_app_icon"` to `android:icon="@mipmap/ic_launcher"`

## Implementation Steps

1. Design SVG using Figma, Illustrator, or code-based tool
2. Export PNG variants for Android at all standard densities
3. Create macOS `.icns` if needed (or high-res PNG fallback)
4. Place assets in project directories
5. Update Android manifest to reference the new icon
6. Commit SVG master + exported assets to git
7. Deploy and verify on test devices

## Success Criteria

- [ ] Icon renders clearly at 48px (smallest size)
- [ ] Icon is recognizable and distinct from system icons on Android home screen
- [ ] Cyan and magenta remain distinct and readable on both dark and light backgrounds
- [ ] SVG source is version-controlled and editable
- [ ] Android app displays new icon in launcher and system settings
- [ ] macOS version (if applicable) displays correctly in Finder

## Future Considerations

- **Adaptive icons** (Android 8+): Consider a circular variant with a safe zone for the center
- **Dark/light mode variations:** Current dark design works well; light background may need color inversion
- **Animation:** Potential for a subtle animation on app launch (cursor blinking, connection flowing)
