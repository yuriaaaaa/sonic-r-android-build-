# Sonic R on Android

An arm64 Android build of the engine, with a launcher for the game data,
mods and settings. Releases are source-only: build the APK yourself and use
game data from your own copy of Sonic R.

## Build

You need the Android SDK (platform 35), NDK 27.2.12479018, CMake 3.22.1 and
JDK 17.

```sh
cd android
ANDROID_HOME=/path/to/android-sdk ./build.sh
```

`build.sh` downloads SDL2, SDL2_mixer, xz and libarchive into `android/deps`,
builds the archive library, then runs `./gradlew assembleRelease`. The APK
lands in `android/app/build/outputs/apk/release/app-release.apk` (debug-signed).
If Maven returns HTTP 429, run it again.

## Game data

1. Install the APK and open **Sonic R**. Allow **All files access** when asked.
2. Copy these folders from the PC disc or an installed copy into
   `Internal storage/SonicR`: `GENERAL ISLAND CITY RUIN FACTORY EMERALD AI BIN SOUND`.
3. For music, rip the disc's CD audio into `SonicR/MUSIC` as `track2` to
   `track21` (`.ogg`, `.mp3`, `.flac`, `.wav`, `.adx` or `.son`).

The launcher adds the repo's `DATA/` extras on first run and shows anything
missing.

## Mods

The launcher reads the Windows Sonic R Mod Loader layout, so mods move between
PC and phone: `SonicR/mods/<mod>/mod.ini` plus a `files/` folder, with the
enabled list in `mods/SonicRModLoader.ini`.

- **Mods** turns mods on and off, reorders them (later mods win) and adds
  archives from storage.
- **Get mods** browses GameBanana, and GameBanana's 1-click install opens the
  app directly.
- Textures (`.png`, `.gif`, `.bmp`) replace `.RAW` files; `music/trackN` in
  any format replaces that track.
- DLL mods are Windows-only, except `SonicR_ColorFix.dll` (Saturn Style
  Coloring): the engine already draws Saturn-style colours, so it needs no DLL.
  Its Radiant Emerald translucency isn't included.
- Codes (`Codes=` in mod.ini, or `Codes.lst`) run every frame in a race:
  `write`, the `if`/`else`/`endif` comparisons and `ifkbkey`, on the player
  data. That covers model swaps (Disable Blinking) and Sonic R Heroes'
  character swap on Z/X/C. Codes that touch other memory, or use pointers,
  can't run and are listed in the log.
- The loader logs to `mods/ModLoader-android.log`.

## Controls

On-screen buttons send the same keys as the PC version: D-pad, A (accelerate),
Jump, Look, L/R, Back and Start. A connected controller hides them until you
touch the screen.

- **Three-finger tap** away from the buttons pauses and opens the game menu.
- **Edit touch controls** (launcher Settings or the game menu): drag controls
  to move them, and resize, rename or hide the selected one. A hidden **C**
  button (for mods like Sonic R Heroes) can be switched on there. L, R and C
  send Z, X and C. Layouts save to three
  profiles; pick the active one in Settings or the game menu.
- **Back** asks whether to quit to the launcher.

## Multiplayer

- **Split-screen:** connect two Bluetooth controllers to one phone and use the
  game's 2-player mode.
- **Over Wi-Fi:** the engine's LAN play works between phones, and with PCs
  running the same version and data. Put both on the same Wi-Fi, or have one
  phone share a hotspot that the other joins. One player hosts and the other
  joins; hosts are found automatically. **Multiplayer** in the launcher shows
  your address, sets your player name, and can join a host by IP when
  automatic discovery is blocked.
- **Online rooms:** races over the internet through a
  [sonic-r-netplay](https://github.com/ZinexDa/sonic-r-netplay) hub, the same
  hub its Windows launcher uses, so phones and PCs can share rooms. Under
  **Multiplayer**, everyone enters the hub address once. One player taps
  **Host a room**; the others tap **Find rooms** and pick it. The game opens
  straight into the network lobby, and the host presses Start (or A) to begin
  once everyone is in. Players connect directly where their routers allow it,
  and through the hub's relay otherwise. With the hub address left blank,
  **Host a room** runs the hub on the host's own phone: friends enter that
  phone's address. Friends outside its network need TCP 8080 and UDP
  9000-9001 forwarded to it on the router (not possible on mobile data). A
  hub on a PC or server also works: see [netplay-hub.md](netplay-hub.md).

## Settings

| Setting | Launcher | Game menu |
|---|---|---|
| Screen: 4:3 or stretch to fill | yes | yes |
| Resolution: native, 1080p, 720p, 480p | yes | yes |
| Anti-aliasing: off, 2x, 4x | yes | needs a restart |
| Smooth textures | yes | yes |
| VSync | yes | yes |
| FPS counter | yes | yes |
| Touch controls on/off and opacity | yes | yes |
| Touch layout profile and editor | yes | yes |
| Debug logging | yes | |

The game runs at 30 fps, like the original.

## Logs

With **Debug logging** on, each run writes `SonicR/logs/game-<time>.log` (the
engine's output with device details) and `logcat-<time>.txt` (Android's log
for the game, including crashes). The newest 10 runs are kept.
