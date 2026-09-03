# Building XPlayer2

## Flavors: `play` (Google Play, 16 KB) vs `full` (GitHub, VITURE)

Two product flavors (dimension `distribution`):

- **`play`** — ships WITHOUT the VITURE One SDK, so it is fully **16 KB page-size compliant** → for **Google Play**.
  - AAB to upload: `./gradlew :app:bundlePlayRelease`
- **`full`** — includes VITURE 2D/3D switching (its prebuilt `libsdk.so` is 4 KB-aligned, so this build is *not* 16 KB-compliant) → for **GitHub / sideload**.
  - APK: `./gradlew :app:assembleFullRelease` — this is what CI builds and attaches to the GitHub release.

`VitureController` is the real SDK wrapper in `src/full/` and a no-op stub in `src/play/`; the VITURE
`.aar` (`app/libs/*.aar`) is a `fullImplementation` dependency, so the `play` flavor never pulls in
`libsdk.so`. In Android Studio pick the variant (`playRelease`, `fullDebug`, …). CI workflow:
`.github/workflows/android-release.yml`.

## Media3: Maven artifacts + one prebuilt AAR

Media3 (`androidx.media3:*`) comes from **Google Maven**, pinned as `media3` in
`gradle/libs.versions.toml`. Media3 is *not* built from source inside this project: its build system
pins its own AGP/Kotlin/Gradle versions, and configuring it in the same Gradle build as the app
(either by including its library modules, or via its official `includeMedia3()` composite build)
ends in classloader/version-constraint conflicts no matter how the versions are aligned.

The single exception is `media3-decoder-ffmpeg`, which Google never publishes. It is committed as a
**prebuilt AAR** in `external/prebuilt/media3-decoder-ffmpeg-<version>.aar`, built from the
`external/media3` submodule with media3's **own** Gradle wrapper (its versions, its build). It is pure
Java glue (`FfmpegAudioRenderer`, `FfmpegAudioDecoder`, `FfmpegLibrary`) — the native
`libffmpegJNI.so` ships separately (next section).

### Bumping media3

```bash
# 1) Pin the new version for the Maven artifacts:
#    gradle/libs.versions.toml -> media3 = "1.X.Y"
# 2) Check out the SAME tag in the submodule (the AAR must match the Maven artifacts' version):
git -C external/media3 fetch --tags && git -C external/media3 checkout 1.X.Y
# 3) Build the decoder module with media3's own wrapper. Make sure the jni/ffmpeg symlink is ABSENT,
#    so the AAR is Java-only and doesn't carry a second libffmpegJNI.so:
export ANDROID_HOME=~/Library/Android/sdk
(cd external/media3 && ./gradlew :lib-decoder-ffmpeg:assembleRelease)
cp external/media3/libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar \
   external/prebuilt/media3-decoder-ffmpeg-1.X.Y.aar
git rm -q external/prebuilt/media3-decoder-ffmpeg-<old>.aar
# 4) The Java glue's `native` methods must still match the prebuilt .so — if this diff is non-empty,
#    regenerate libffmpegJNI.so too (next section):
git -C external/media3 diff <old>..1.X.Y -- libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc \
   libraries/decoder_ffmpeg/src/main/java
# 5) Commit the catalog, the AAR and the submodule pointer.
```

## FFmpeg audio decoders (AC-3 / E-AC-3 / DTS / TrueHD / …)

Android's built-in `MediaCodec` does **not** decode Dolby (AC-3/E-AC-3/TrueHD), DTS, and several
other audio formats common in MKV movie rips. We decode those with Media3's FFmpeg audio extension
(`libffmpegJNI.so`, used via `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON`).

**`libffmpegJNI.so` is committed prebuilt** in `app/src/main/jniLibs/<abi>/` (≈3.4 MB, 2 ARM ABIs:
`arm64-v8a` + `armeabi-v7a`). The app is **ARM-only** — every real target (phones, XReal/RayNeo
glasses, the XREAL Beam box) is ARM, so we don't ship x86/x86_64 at all (emulators need an ARM
image). This is deliberate:

- Building FFmpeg from source is slow and fragile in CI, so the GitHub-release APK used to ship
  **without** these decoders and played AC-3/DTS files as "no audio" — while the Play build (built
  locally, which *does* link FFmpeg) was fine. Shipping the prebuilt `.so` makes both identical.
- The Media3 `decoder_ffmpeg` module only compiles its own `libffmpegJNI.so` **if the
  `external/media3/libraries/decoder_ffmpeg/src/main/jni/ffmpeg` symlink exists** (see that module's
  `build.gradle.kts`). The committed AAR (section above) is built without the symlink, so it is
  Java-only and the app's prebuilt `.so` is what ships. No NDK/FFmpeg build — and no submodule at
  all — is needed for a normal or CI release.
- The bundled `.so` is covered by the APK signature automatically — nothing extra to sign.

### Enabled decoders

See `ENABLED_DECODERS` in `scripts/setup_media3_ffmpeg.sh`
(`ac3 eac3 dca truehd mlp aac mp3 vorbis opus flac alac ape wmapro wmav1 wmav2 wmalossless
pcm_s16le pcm_s24le pcm_s32le pcm_f32le atrac3 atrac3p`).

### Regenerating the prebuilt `.so` (only when changing the decoder set or bumping FFmpeg/Media3)

```bash
# 1) Build FFmpeg for the ARM ABIs (creates the jni/ffmpeg symlink + applies the needed flags).
#    Edit --decoders to change the set; default is the wide list above.
scripts/setup_media3_ffmpeg.sh            # uses ANDROID_NDK_HOME / $ANDROID_HOME/ndk/*

# 2) Build the decoder module with media3's OWN wrapper (the app build never compiles media3);
#    with the symlink present, CMake links libffmpegJNI.so from the fresh FFmpeg .a into the AAR:
export ANDROID_HOME=~/Library/Android/sdk
(cd external/media3 && ./gradlew :lib-decoder-ffmpeg:assembleRelease)

# 3) Pull the stripped .so out of that AAR over the committed prebuilt (ARM only — the build also
#    produces x86/x86_64, but we ship ARM-only, so don't copy those):
for abi in arm64-v8a armeabi-v7a; do
  unzip -p external/media3/libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar \
     "jni/$abi/libffmpegJNI.so" > "app/src/main/jniLibs/$abi/libffmpegJNI.so"
done

# 4) Drop the symlink, then rebuild the AAR WITHOUT it and re-copy it to external/prebuilt/ — the
#    committed AAR must stay Java-only (no second .so inside):
rm external/media3/libraries/decoder_ffmpeg/src/main/jni/ffmpeg
(cd external/media3 && ./gradlew :lib-decoder-ffmpeg:assembleRelease)
cp external/media3/libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar \
   external/prebuilt/media3-decoder-ffmpeg-<version>.aar

# 5) Commit app/src/main/jniLibs/**/libffmpegJNI.so (and the AAR if it changed)
```

(The `external/ffmpeg` and `external/media3` submodules stay in place; only the `jni/ffmpeg` symlink
is transient. `scripts/setup_media3_ffmpeg.sh` also patches `build_ffmpeg.sh`/`CMakeLists.txt` inside
the media3 checkout with the link flags FFmpeg 6.0 needs — those show up as local modifications of the
submodule and are expected; don't commit them. `packaging { jniLibs.pickFirsts += "**/libffmpegJNI.so" }`
in `app/build.gradle.kts` keeps a stray local rebuild from colliding with the prebuilt.)
