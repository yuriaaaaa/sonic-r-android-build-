#!/usr/bin/env bash
# Builds the Sonic R Android APK (arm64). Needs JDK 17+, the Android SDK with
# NDK 27.2.12479018 and CMake 3.22.1, and network access for the SDL sources.
#   ANDROID_HOME=/path/to/sdk ./build.sh
# Output: app/build/outputs/apk/release/app-release.apk
set -euo pipefail
cd "$(dirname "$0")"

SDL2_VERSION=2.30.12
SDL2_MIXER_VERSION=2.8.1

fetch() {   # name version url sha256
    if [ ! -f "deps/$1/CMakeLists.txt" ]; then
        mkdir -p deps
        rm -rf "deps/$1"
        echo "Fetching $1 $2"
        local tarball="deps/$1-$2.tar.gz"
        curl -fsSL --retry 4 -o "$tarball" "$3"
        # Archives are checked against known hashes before anything is unpacked.
        if ! echo "$4  $tarball" | sha256sum -c --status; then
            echo "Checksum mismatch for $tarball; refusing to use it." >&2
            rm -f "$tarball"
            exit 1
        fi
        tar xzf "$tarball" -C deps
        rm -f "$tarball"
        mv "deps/$1-$2" "deps/$1"
    fi
}
fetch SDL2 "$SDL2_VERSION" \
    "https://github.com/libsdl-org/SDL/releases/download/release-$SDL2_VERSION/SDL2-$SDL2_VERSION.tar.gz" \
    ac356ea55e8b9dd0b2d1fa27da40ef7e238267ccf9324704850d5d47375b48ea
fetch SDL2_mixer "$SDL2_MIXER_VERSION" \
    "https://github.com/libsdl-org/SDL_mixer/releases/download/release-$SDL2_MIXER_VERSION/SDL2_mixer-$SDL2_MIXER_VERSION.tar.gz" \
    cb760211b056bfe44f4a1e180cc7cb201137e4d1572f2002cc1be728efd22660

# xz + libarchive (static) for the launcher's mod unpacker (zip, 7z, rar).
XZ_VERSION=5.6.4
LIBARCHIVE_VERSION=3.8.1
fetch xz "$XZ_VERSION" \
    "https://github.com/tukaani-project/xz/releases/download/v$XZ_VERSION/xz-$XZ_VERSION.tar.gz" \
    269e3f2e512cbd3314849982014dc199a7b2148cf5c91cedc6db629acdf5e09b
fetch libarchive "$LIBARCHIVE_VERSION" \
    "https://github.com/libarchive/libarchive/releases/download/v$LIBARCHIVE_VERSION/libarchive-$LIBARCHIVE_VERSION.tar.gz" \
    bde832a5e3344dc723cfe9cc37f8e54bde04565bfe6f136bc1bd31ab352e9fab

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}"
NDK="$SDK/ndk/27.2.12479018"
CMAKE_BIN="$SDK/cmake/3.22.1/bin"
PREFIX="$(pwd)/deps/archive-prefix"
if [ ! -f "$PREFIX/lib/libarchive.a" ]; then
    echo "Building xz and libarchive"
    ANDROID_CMAKE=(-G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja"
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$PREFIX")
    "$CMAKE_BIN/cmake" -S deps/xz -B deps/xz-build "${ANDROID_CMAKE[@]}" \
        -DBUILD_SHARED_LIBS=OFF -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF \
        -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF -DXZ_TOOL_SYMLINKS=OFF \
        -DXZ_TOOL_SYMLINKS_LZMA=OFF -DXZ_DOC=OFF -DXZ_NLS=OFF -DBUILD_TESTING=OFF
    "$CMAKE_BIN/ninja" -C deps/xz-build install
    "$CMAKE_BIN/cmake" -S deps/libarchive -B deps/libarchive-build "${ANDROID_CMAKE[@]}" \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DLIBLZMA_INCLUDE_DIR="$PREFIX/include" -DLIBLZMA_LIBRARY="$PREFIX/lib/liblzma.a" \
        -DENABLE_LZMA=ON -DENABLE_ZLIB=ON -DENABLE_BZip2=OFF -DENABLE_LZ4=OFF -DENABLE_ZSTD=OFF \
        -DENABLE_LIBB2=OFF -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF \
        -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF \
        -DENABLE_LIBGCC=OFF -DENABLE_CNG=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF \
        -DENABLE_UNZIP=OFF -DENABLE_XATTR=OFF -DENABLE_ACL=OFF -DENABLE_ICONV=OFF -DENABLE_TEST=OFF \
        -DENABLE_INSTALL=ON -DENABLE_WERROR=OFF
    "$CMAKE_BIN/ninja" -C deps/libarchive-build archive_static
    cp deps/libarchive-build/libarchive/libarchive.a "$PREFIX/lib/"
fi

# The Java side of SDL (app/src/main/java/org/libsdl/app) is copied from
# SDL $SDL2_VERSION; re-copy it from deps/SDL2/android-project when bumping.

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
    echo "Set ANDROID_HOME to your Android SDK." >&2
    exit 1
fi

bash ./gradlew --no-daemon assembleRelease
echo "APK: $(pwd)/app/build/outputs/apk/release/app-release.apk"
