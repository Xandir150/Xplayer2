# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

XPlayer2 — an Android video player app (`com.teleteh.xplayer2`) that turns XR glasses (XREAL/RayNeo/VITURE,
plus generic USB-C DisplayPort dongles) into a personal 3D cinema. Kotlin, View-based UI (no Compose in
app code despite the Compose plugin being applied), Media3/ExoPlayer for playback, TensorFlow Lite for
on-device 2D→3D depth conversion ("Lazy 3D"), OpenGL for stereo rendering.

## Build commands

```bash
./gradlew :app:assembleFullRelease   # GitHub/sideload build, VITURE SDK included (what CI builds)
./gradlew :app:bundlePlayRelease     # Google Play AAB, VITURE excluded (16 KB page-size compliant)
./gradlew :app:assembleFullDebug     # local debug build (pick playDebug/fullDebug variant in Android Studio)
./gradlew test                       # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest       # instrumented tests (app/src/androidTest), needs a device/emulator
```

There is no lint/format step wired into CI beyond the Gradle build itself.

### Flavors: `play` vs `full`

Two product flavors on dimension `distribution` (see `app/build.gradle.kts` and `BUILDING.md`):
- **`play`** — no VITURE One SDK → 16 KB page-size compliant → Google Play upload.
- **`full`** — includes VITURE (`app/libs/*.aar`, fetched at CI build time, not committed — no
  redistribution) for glasses-native 2D/3D switching → GitHub sideload APK.

`VitureController` is the real SDK wrapper in `app/src/full/java/.../data/glasses/` and a no-op stub in
`app/src/play/java/.../data/glasses/`. When touching glasses 2D/3D-mode code, check both variants exist
and stay in sync.

### FFmpeg audio decoders

`libffmpegJNI.so` (AC-3/E-AC-3/DTS/TrueHD/etc., codecs `MediaCodec` can't decode) ships **prebuilt** in
`app/src/main/jniLibs/<abi>/` — do not expect a normal build to compile FFmpeg from source. Regenerating
it is a deliberate, manual process described in `BUILDING.md`; only do it if asked to change the decoder
set or bump FFmpeg/Media3 versions.

### ARM-only

`abiFilters` is `arm64-v8a` + `armeabi-v7a` only — no x86/x86_64, by policy (every real target is ARM;
emulators need an ARM system image).

### Local Media3 / FFmpeg build graph

`external/media3` and `external/ffmpeg` are git submodules. `settings.gradle.kts` wires in a curated
subset of Media3 library modules directly from `external/media3/libraries/*` (as `:media3-lib-*`
projects) rather than pulling Maven artifacts, and patches in placeholder `proguard-rules.txt` files for
any submodule library that's missing one (AGP 9 requires the file to exist). Don't "fix" this by editing
files inside `external/media3` — it's meant to stay pristine; workarounds belong in `settings.gradle.kts`.

## Architecture

### Two-screen split: phone = remote, glasses = screen

The core interaction model: the phone acts as a remote control/UI, while the actual video renders on the
external display (the glasses, presented as a secondary `Display`).

- `PlayerActivity` (`player/`) — the real player: owns the Media3 `ExoPlayer`, format/track selection,
  SBS/OU detection, subtitle handling, resume position, and orchestrates everything below. Large/central
  file (~2600 lines) — most player-facing changes touch this.
- `ExternalPlayerPresentation` — an Android `Presentation` shown on the glasses' `Display` that hosts the
  actual `PlayerView`/video surface.
- `RemoteControlActivity` — the phone-side UI once video is playing on the glasses (play/pause/seek/etc.
  without touching the glasses' own screen).
- `MenuMirrorPresentation` — mirrors the phone's menu/UI onto the glasses per-eye (`SbsMirrorLayout`) so
  menus are visible in the headset, laid out to reflect phone focus.
- `PlaybackService` — foreground `MediaSession` service so playback survives backgrounding and gets
  proper system media controls/notification.
- `OuToSbsGlView` — GLSurfaceView that receives decoder output via `SurfaceTexture` and does the actual
  stereo format conversion (Over-Under → Side-by-Side) and any depth-based stereo warp, via a custom GL
  shader pipeline.

### Glasses integration (`data/glasses/`)

- `GlassesController` — USB HID glue: detects/connects to XR glasses (VID/PID list mirrored from
  wheaney/XRLinuxDriver covers most brands), but the actual "set 2D/3D display mode" command is only
  implemented for XREAL (`GlassesProtocol`, ported from xspace/nrealAirLinuxDriver).
- `XrealImuReader` — reads the raw gyro stream off XREAL glasses over USB HID (reverse-engineered
  packet format).
- `HeadOrientationTracker` — integrates `XrealImuReader`'s gyro deltas into yaw/pitch/roll for head-based
  UI interactions (e.g. the head-as-D-pad menu navigation, screensaver head-pointer). Not a real 6-DoF
  pose — gyro-only with a slow zero-rate-bias correction for drift.
- `VitureController` — VITURE-brand glasses SDK wrapper; real implementation in `full` flavor only, no-op
  in `play` (see flavors above).

### Lazy 3D — on-device 2D→3D conversion (`data/depth/`)

- `DepthModelManager` — resolves/downloads the TFLite depth model. Supports **more than one model**
  (user-selectable, for A/B testing quality/perf) — resolution order is bundled asset → cached download
  → fetch from a GitHub-release URL, validated by size + TFLite magic bytes, single-flighted.
- `DepthEstimator` — wraps the TFLite interpreter (NNAPI/GPU delegate) for a MiDaS-family monocular
  depth model; fixed verified I/O shape, output normalized to inverse-depth 0..1.
- `DepthFrameWorker` — single-thread worker owning a `DepthEstimator`, fed via a **latest-only slot**
  (never queues stale frames — always processes the newest frame available, skipping ahead if inference
  falls behind). This is the pattern to follow for any other "keep up with real-time, drop is fine"
  pipeline stage.
- Depth output feeds `OuToSbsGlView`'s shader to warp 2D into stereo SBS in real time.

### Content sources (`data/network/`, `ui/network/`, `util/`)

Local files, DLNA/UPnP (`DlnaDiscovery`/`DlnaBrowser`), SMB (`SmbStorage`), arbitrary pasted/shared URLs,
and Share-target intents from VK/OK/Yandex Disk/etc. (`VkClubActivity`, `YaDiskActivity`,
`WebSourceClassifier`, `VideoStreamExtractor`) which need per-service URL resolution (some behind a
Chrome-UA spoof) to extract the actual playable stream. `MailCloudActivity`/`MailCloudApi` similarly
handle mail.ru cloud links.

### Native code

`app/src/main/cpp/native-lib.cpp` is a placeholder ("Hello from C++" JNI stub) — not load-bearing;
FFmpeg's native decoding comes from the prebuilt `.so`, not this CMake target.

## CI / release

`.github/workflows/android-release.yml` triggers on `v*` tags: downloads the VITURE SDK, builds
`:app:assembleFullRelease`, and attaches the APK to the GitHub Release. It does not touch the Play
Store flavor/build — that's built and uploaded locally.
