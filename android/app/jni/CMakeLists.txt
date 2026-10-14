cmake_minimum_required(VERSION 3.18)
project(sonicr C CXX)

# SDL2 and SDL2_mixer sources are fetched into android/deps by build.sh.
set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/../../deps)
set(GAME ${CMAKE_CURRENT_SOURCE_DIR}/../../../source/sdl/src)

add_subdirectory(${DEPS}/SDL2 SDL2)

# Music: WAV, OGG (stb_vorbis), MP3 (minimp3) and FLAC (dr_flac) are built in.
# The game decodes .son, .adx and .adp itself.
set(SDL2MIXER_VENDORED OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_OPUS OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MOD OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MIDI OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_WAVPACK OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_GME OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_FLAC_LIBFLAC OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_FLAC_DRFLAC ON CACHE BOOL "" FORCE)
set(SDL2MIXER_MP3_MPG123 OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MP3_MINIMP3 ON CACHE BOOL "" FORCE)
set(SDL2MIXER_VORBIS "STB" CACHE STRING "" FORCE)
add_subdirectory(${DEPS}/SDL2_mixer SDL2_mixer)

file(GLOB_RECURSE GAME_SOURCES ${GAME}/*.c)
add_library(main SHARED ${GAME_SOURCES})

target_include_directories(main PRIVATE ${GAME})
target_compile_options(main PRIVATE
    -std=gnu11 -O2 -fno-common -fno-strict-aliasing
    -Wno-int-to-void-pointer-cast -Wno-void-pointer-to-int-cast
    -Wno-pointer-to-int-cast -Wno-int-to-pointer-cast
    -Wno-incompatible-pointer-types -Wno-int-conversion
    -Wno-unused-variable -Wno-unused-function -Wno-unused-parameter)
target_compile_definitions(main PRIVATE
    SONICR_SDL SONICR_GLES2 SONICR_MATCHMAKER SONICR_MODS _GNU_SOURCE)
target_link_libraries(main PRIVATE SDL2::SDL2 SDL2_mixer GLESv2 log m)

# libmodarchive: the launcher's mod unpacker (zip, 7z, rar), linking the static
# xz and libarchive that build.sh prepares in deps/archive-prefix/<abi>.
set(ARCHIVE_PREFIX ${DEPS}/archive-prefix/${ANDROID_ABI})
add_library(modarchive SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../../modarchive/modarchive.c)
target_include_directories(modarchive PRIVATE ${DEPS}/libarchive/libarchive)
target_link_libraries(modarchive PRIVATE
    ${ARCHIVE_PREFIX}/lib/libarchive.a ${ARCHIVE_PREFIX}/lib/liblzma.a z)
target_link_options(modarchive PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL)
