# XPlayer2

XPlayer2 is an Android video player with 3D/VR features for using with XReal glasses. It uses AndroidX Media3 ExoPlayer and an FFmpeg-based decoder module for specific codecs.

## Features (highlights)
- 3D video support; OU/SBS badge in Recent based on frame-packing metadata.
- Recent list shows media titles from metadata (preferred over filename) and resume positions.
- PlayerActivity can launch on an external ultra‑wide (>= 32:9) display.

## Repository layout
- `app/` – main Android application module.
- `external/` – third‑party sources:
  - `external/media3` – AndroidX Media3 (subset of modules vendored or as submodule)
  - `external/ffmpeg` – FFmpeg 6.0 source (used by Media3 decoder_ffmpeg)
- `scripts/` – helper scripts, including FFmpeg/Media3 setup.

The Gradle settings in `settings.gradle.kts` include a minimal set of Media3 modules directly from `external/media3/*` so they participate in the build.

## Prerequisites
- Android Studio (Giraffe+ recommended) with Android SDK
- Android NDK (r26+ recommended). Ensure one of the following is set: `ANDROID_NDK_HOME`, `ANDROID_NDK`, or install via SDK Manager
- Java 24 JDK (on macOS you can use Homebrew OpenJDK):
  - Example: `export JAVA_HOME=/opt/homebrew/opt/openjdk`
- Git
- macOS or Linux host

## Getting the sources
You have two supported ways to obtain the external dependencies:

### Option A: Git submodules (recommended)
This keeps external libraries out of the main repo while pinning exact revisions.

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/Xandir150/Xplayer2.git
cd Xplayer2

# If you cloned without --recurse-submodules
git submodule update --init --recursive
```

Current submodules (expected paths):
- `external/media3` -> https://github.com/androidx/media (branch: release)
- `external/ffmpeg` -> https://git.ffmpeg.org/ffmpeg.git (branch: release/6.0)

### Option B: Fetch sources via script
If you don’t want to use submodules, run the helper script to clone the required repos and prepare FFmpeg for Media3.

```bash
./scripts/setup_media3_ffmpeg.sh \
  --ndk "/path/to/android-ndk" \
  --host darwin-x86_64 \
  --abi 30
```

Notes:
- The script tries to auto-detect the NDK if `--ndk` is not provided.
- On Linux use `--host linux-x86_64`.
- It will:
  1) Clone AndroidX Media3 into `external/media3` (branch `release`) if missing
  2) Clone FFmpeg 6.0 into `external/ffmpeg` (branch `release/6.0`) if missing
  3) Symlink `external/ffmpeg` to `external/media3/libraries/decoder_ffmpeg/src/main/jni/ffmpeg`
  4) Invoke `build_ffmpeg.sh` in the Media3 decoder module to build binaries

### Enabled audio decoders (default)

The setup script enables a wide set of decoders by default to make the player "all‑eating":

```
ac3 eac3 dca aac mp3 vorbis opus flac alac ape wmalossless wmapro wma wmav2 
pcm_s16le pcm_s24le pcm_s32le pcm_f32le atrac3 atrac3p
```

You can override the list using `--decoders "<space separated names>"` when invoking the script, for example:

```bash
./scripts/setup_media3_ffmpeg.sh --abi 30 --decoders "ac3 eac3 dca aac hevc"
```

## Building the app
```bash
# From project root
./gradlew assembleDebug -x test
```
The app module includes Media3 projects from `external/media3` as defined in `settings.gradle.kts`.

## Troubleshooting
- NDK not found: set `ANDROID_NDK_HOME` or pass `--ndk` to the setup script.
- Java toolchain issues: set `JAVA_HOME` to a Java 24 JDK (e.g., `/opt/homebrew/opt/openjdk` on macOS/ARM).
- If FFmpeg build fails, clean the JNI build directory under `external/media3/libraries/decoder_ffmpeg/src/main/jni` and re-run the setup script.

## Contributing
Please open issues and pull requests on GitHub. For large changes, discuss in an issue first.

## License
This project’s source code is provided under the PolyForm Noncommercial License 1.0.0. See `LICENSE`.
