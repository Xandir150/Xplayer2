#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   scripts/setup_media3_ffmpeg.sh [--ndk <path>] [--abi <api>] [--host <host>] [--decoders "ac3 eac3 dca"]
#
# Defaults:
#   NDK_PATH: $ANDROID_NDK_HOME or $ANDROID_NDK or $ANDROID_HOME/ndk/* (first found)
#   ANDROID_ABI: 30 (must be <= minSdk in app)
#   HOST_PLATFORM: darwin-x86_64 (macOS). Use linux-x86_64 on Linux.
#   DECODERS: ac3 eac3 dca
#   MEDIA3_DIR: external/media3
#   FFMPEG_SRC_DIR: external/ffmpeg
#
# What it does:
# 1) Clones AndroidX Media3 (release branch) into external/media3 if missing
# 2) Clones FFmpeg 6.0 into external/ffmpeg if missing
# 3) Links FFmpeg src into Media3 decoder_ffmpeg module jni directory
# 4) Runs Media3 build_ffmpeg.sh for all ABIs to produce binaries

ROOT_DIR="$(cd "$(dirname "$0")"/.. && pwd)"
MEDIA3_DIR="${ROOT_DIR}/external/media3"
FFMPEG_SRC_DIR="${ROOT_DIR}/external/ffmpeg"
FFMPEG_MODULE_PATH="${MEDIA3_DIR}/libraries/decoder_ffmpeg/src/main"
JNI_DIR="${FFMPEG_MODULE_PATH}/jni"

# Parse args
NDK_PATH_ARG=""
ABI_ARG=""
HOST_ARG=""
DECODERS_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ndk)
      NDK_PATH_ARG="$2"; shift 2;;
    --abi)
      ABI_ARG="$2"; shift 2;;
    --host)
      HOST_ARG="$2"; shift 2;;
    --decoders)
      DECODERS_ARG="$2"; shift 2;;
    *) echo "Unknown arg: $1"; exit 1;;
  esac
done

# Resolve NDK path
resolve_ndk() {
  if [[ -n "${NDK_PATH_ARG}" ]]; then echo "${NDK_PATH_ARG}"; return; fi
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then echo "${ANDROID_NDK_HOME}"; return; fi
  if [[ -n "${ANDROID_NDK:-}" ]]; then echo "${ANDROID_NDK}"; return; fi
  if [[ -n "${ANDROID_HOME:-}" ]] && [[ -d "${ANDROID_HOME}/ndk" ]]; then
    # pick the latest
    ls -1d "${ANDROID_HOME}/ndk"/* 2>/dev/null | sort -V | tail -n1
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]] && [[ -d "${ANDROID_SDK_ROOT}/ndk" ]]; then
    ls -1d "${ANDROID_SDK_ROOT}/ndk"/* 2>/dev/null | sort -V | tail -n1
    return
  fi
  echo "" # not found
}

NDK_PATH="$(resolve_ndk)"
ANDROID_ABI="${ABI_ARG:-30}"
HOST_PLATFORM="${HOST_ARG:-darwin-x86_64}"
ENABLED_DECODERS=( ${DECODERS_ARG:-"ac3 eac3 dca"} )

if [[ -z "${NDK_PATH}" ]]; then
  echo "ERROR: Android NDK not found. Provide with --ndk or set ANDROID_NDK_HOME." >&2
  exit 2
fi

echo "ROOT_DIR=${ROOT_DIR}"
echo "NDK_PATH=${NDK_PATH}"
echo "ANDROID_ABI=${ANDROID_ABI}"
echo "HOST_PLATFORM=${HOST_PLATFORM}"
echo "DECODERS=${ENABLED_DECODERS[*]}"

# 1) Clone Media3 (release)
if [[ ! -d "${MEDIA3_DIR}" ]]; then
  echo "Cloning AndroidX Media3 into ${MEDIA3_DIR}..."
  mkdir -p "${ROOT_DIR}/external"
  git clone https://github.com/androidx/media.git "${MEDIA3_DIR}"
  (cd "${MEDIA3_DIR}" && git checkout release)
else
  echo "Media3 already present at ${MEDIA3_DIR}"
fi

# 2) Clone FFmpeg 6.0
if [[ ! -d "${FFMPEG_SRC_DIR}" ]]; then
  echo "Cloning FFmpeg 6.0 into ${FFMPEG_SRC_DIR}..."
  mkdir -p "${ROOT_DIR}/external"
  # Use GitHub mirror for reliability
  git clone https://github.com/FFmpeg/FFmpeg "${FFMPEG_SRC_DIR}"
  (cd "${FFMPEG_SRC_DIR}" && git checkout release/6.0)
else
  echo "FFmpeg already present at ${FFMPEG_SRC_DIR}"
fi

# 3) Link FFmpeg into Media3 decoder module
mkdir -p "${JNI_DIR}"
if [[ ! -e "${JNI_DIR}/ffmpeg" ]]; then
  ln -s "${FFMPEG_SRC_DIR}" "${JNI_DIR}/ffmpeg"
  echo "Linked ${FFMPEG_SRC_DIR} -> ${JNI_DIR}/ffmpeg"
else
  echo "Symlink already exists: ${JNI_DIR}/ffmpeg"
fi

# 4) Build FFmpeg via Media3 script
pushd "${JNI_DIR}" >/dev/null
  echo "Building FFmpeg via build_ffmpeg.sh..."
  ./build_ffmpeg.sh \
    "${FFMPEG_MODULE_PATH}" \
    "${NDK_PATH}" \
    "${HOST_PLATFORM}" \
    "${ANDROID_ABI}" \
    "${ENABLED_DECODERS[@]}"
popd >/dev/null

echo "Done. Now you can build the app: ./gradlew assembleDebug -x test"
