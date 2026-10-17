<p align="center"><img src="assets/brand/icon.png" width="112" alt="Cutout icon"></p>

# Cutout

Remove a background, refine the edges, and copy a transparent image into your next task.

Cutout processes images on your device with a bundled ISNet model. On Android, an optional floating Bubble brings the same workflow above other apps: choose a picture, remove its background, then copy or save the result. Successful Bubble actions collapse the panel and leave the translucent shortcut on screen.

## What you can do

- Import a picture from the system photo picker, a file, or an explicit clipboard action.
- Remove its background offline, then refine the mask with erase and restore brushes.
- Zoom and pan with two fingers, compare with the original, and undo or redo up to 50 strokes.
- Copy a transparent PNG or save it. SVG files with a separate solid background can retain their vector format; other SVGs use a raster fallback.
- Use the system, light, or dark appearance on phone and tablet layouts.

The Bubble is off by default. Android asks for overlay access only when you choose to enable it. Background removal inside Cutout works without overlay or notification permission. Manufacturer-specific settings are shown only where applicable.

## Platforms and status

Android is the primary platform, with native Kotlin processing and a foreground overlay service. The iOS implementation provides the in-app workflow for iPhone and iPad. The floating Bubble is Android-only.

This repository contains development source. Signed store builds are not published here. Build and device acceptance are tracked separately; source support does not imply that every manufacturer configuration has been tested.

## Privacy

Images and clipboard content stay on the device. Temporary working images use app-private cache storage; exported files and copied images are created only by your actions.

Firebase usage analytics and crash reporting are enabled by default to help improve Cutout. A notice links to the separate opt-out controls in Settings. While enabled, these services send technical usage or diagnostic information, including SDK identifiers. Images, filenames, paths, and clipboard content are excluded. See the [privacy policy](https://kigya.github.io/cutout/privacy/).

## Build from source

Use Node.js 22.13 or later in the 22.x series, 24.3 or later in the 24.x series, or 26+. CI uses Node.js 22.23.2. You also need JDK 17 and the Android SDK. Android uses compile SDK 37 and targets SDK 36.

```sh
npm ci
npm run typecheck
npm test -- --runInBand
npm run android
```

The install script applies a small, pinned compatibility patch that lets Gradle tooling choose Node with the `cutout.nodeBinary` project property. Standard shell builds resolve Node from PATH.

For iOS, use Xcode and CocoaPods with the checked-in Gemfile and Podfile. The deployment target is iOS 15.1. Install Ruby dependencies with Bundler, run `bundle exec pod install` inside `ios`, then open the generated Cutout workspace.

Firebase configuration files are intentionally excluded. Local image processing remains buildable without them; optional telemetry requires your own Android and iOS Firebase registrations. Never commit service-account credentials or signing keys.

## Built with

React Native 0.87.1 and TypeScript provide the shared interface. NativeWind handles styling; Skia, Gesture Handler, Reanimated, and adapted React Native Free Canvas code power the mask editor. Kotlin and Swift connect the platform features, and ONNX Runtime runs ISNet locally.

Third-party code and the bundled model retain their licenses. See [Third-party notices](THIRD_PARTY_NOTICES.md).
