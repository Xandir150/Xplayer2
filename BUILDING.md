# Building XPlayer2

Standard build: `./gradlew :app:assembleRelease` (or open in Android Studio). CI does the same — see
`.github/workflows/android-release.yml`.

## FFmpeg audio decoders (AC-3 / E-AC-3 / DTS / TrueHD / …)

Android's built-in `MediaCodec` does **not** decode Dolby (AC-3/E-AC-3/TrueHD), DTS, and several
other audio formats common in MKV movie rips. We decode those with Media3's FFmpeg audio extension
(`libffmpegJNI.so`, used via `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON`).

**`libffmpegJNI.so` is committed prebuilt** in `app/src/main/jniLibs/<abi>/` (≈7 MB, 4 ABIs). This is
deliberate:

- Building FFmpeg from source is slow and fragile in CI, so the GitHub-release APK used to ship
  **without** these decoders and played AC-3/DTS files as "no audio" — while the Play build (built
  locally, which *does* link FFmpeg) was fine. Shipping the prebuilt `.so` makes both identical.
- The Media3 `decoder_ffmpeg` module only compiles its own `libffmpegJNI.so` **if the
  `external/media3/libraries/decoder_ffmpeg/src/main/jni/ffmpeg` symlink exists** (see that module's
  `build.gradle`). In a normal/CI checkout the symlink is absent, so the module is Java-only and the
  app's prebuilt `.so` is what ships. No NDK/FFmpeg build needed for a normal release.
- The bundled `.so` is covered by the APK signature automatically — nothing extra to sign.

### Enabled decoders

See `ENABLED_DECODERS` in `scripts/setup_media3_ffmpeg.sh`
(`ac3 eac3 dca truehd mlp aac mp3 vorbis opus flac alac ape wmapro wmav1 wmav2 wmalossless
pcm_s16le pcm_s24le pcm_s32le pcm_f32le atrac3 atrac3p`).

### Regenerating the prebuilt `.so` (only when changing the decoder set or bumping FFmpeg/Media3)

```bash
# 1) Build FFmpeg for all ABIs (creates the jni/ffmpeg symlink + applies the needed flags).
#    Edit --decoders to change the set; default is the wide list above.
scripts/setup_media3_ffmpeg.sh            # uses ANDROID_NDK_HOME / $ANDROID_HOME/ndk/*

# 2) Build once so the module links libffmpegJNI.so from the fresh FFmpeg .a:
./gradlew :app:assembleRelease

# 3) Copy the freshly built, stripped .so over the committed prebuilt:
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cp "app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/$abi/libffmpegJNI.so" \
     "app/src/main/jniLibs/$abi/libffmpegJNI.so"
done

# 4) Drop the symlink so normal builds use the prebuilt again (avoids a duplicate-.so build):
rm external/media3/libraries/decoder_ffmpeg/src/main/jni/ffmpeg

# 5) Commit app/src/main/jniLibs/**/libffmpegJNI.so
```

(The `external/ffmpeg` and `external/media3` submodules stay in place; only the `jni/ffmpeg` symlink
is transient. `packaging { jniLibs.pickFirsts += "**/libffmpegJNI.so" }` in `app/build.gradle.kts`
keeps a stray local rebuild from colliding with the prebuilt.)
