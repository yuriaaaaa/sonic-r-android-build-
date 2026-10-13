#!/usr/bin/env bash
# Builds the Sonic R Android APK (arm64). Needs JDK 17+, the Android SDK with
# NDK 27.2.12479018 and CMake 3.22.1, and network access for the SDL sources.
#   ANDROID_HOME=/path/to/sdk ./build.sh
# Output: app/build/outputs/apk/release/app-release.apk
set -euo pipefail
cd "$(dirname "$0")"

SDL2_VERSION=2.30.12
SDL2_MIXER_VERSION=2.8.1

fetch() {   # name version url
    if [ ! -f "deps/$1/CMakeLists.txt" ]; then
        mkdir -p deps
        rm -rf "deps/$1"
        echo "Fetching $1 $2"
        curl -fsSL --retry 4 "$3" | tar xz -C deps
        mv "deps/$1-$2" "deps/$1"
    fi
}
fetch SDL2 "$SDL2_VERSION" \
    "https://github.com/libsdl-org/SDL/releases/download/release-$SDL2_VERSION/SDL2-$SDL2_VERSION.tar.gz"
fetch SDL2_mixer "$SDL2_MIXER_VERSION" \
    "https://github.com/libsdl-org/SDL_mixer/releases/download/release-$SDL2_MIXER_VERSION/SDL2_mixer-$SDL2_MIXER_VERSION.tar.gz"

# The Java side of SDL (app/src/main/java/org/libsdl/app) is copied from
# SDL $SDL2_VERSION; re-copy it from deps/SDL2/android-project when bumping.

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
    echo "Set ANDROID_HOME to your Android SDK." >&2
    exit 1
fi

bash ./gradlew --no-daemon assembleRelease
echo "APK: $(pwd)/app/build/outputs/apk/release/app-release.apk"
