/**
 * mods.h — Sonic R Mod Loader compatible file replacement (SONICR_MODS builds).
 * See mods.c for the folder layout.
 */
#ifndef SONICR_MODS_H
#define SONICR_MODS_H

#include <stddef.h>
#include <stdio.h>

/* Reads mods/SonicRModLoader.ini and the enabled mods. Call after chdir to
 * the data folder. */
void Mods_Init(void);

/* Non-zero when at least one path is replaced. */
int Mods_Active(void);

/* fopen through the mod file map (reads only; writes go straight through). */
FILE *Mods_Open(const char *path, const char *mode);

/* Path to read instead of `path` (or `path` itself); NULL if a mod hides it. */
const char *Mods_Resolve(const char *path);

/* A mod's file for CD track `track`, any format. Returns 1 if found. */
int Mods_FindMusic(int track, char *out, size_t n);

/* Non-zero when an enabled mod's codes turn off character blinking. */
int Mods_NoBlink(void);

#endif /* SONICR_MODS_H */
