/**
 * mods.c — Sonic R Mod Loader compatible file replacement (SONICR_MODS builds).
 *
 * Reads the same layout the Windows Sonic R Mod Loader / Mod Manager use:
 *
 *   mods/SonicRModLoader.ini   Mod1=<folder>, Mod2=<folder>, ... (load order)
 *   mods/<folder>/mod.ini      Name=..., plus optional [IgnoreFiles],
 *                              [ReplaceFiles] and [SwapFiles] groups
 *   mods/<folder>/files/...    files that replace the game's own, by path
 *
 * Later mods win over earlier ones, as in the Windows loader. Any file under
 * files/music/ stands in for that CD track whatever its extension, and a
 * .png/.gif/.bmp next to a .RAW texture's path replaces the texture (it is
 * converted to the RAW's own size and cached under mods/.cache).
 *
 * Codes (mod.ini Codes=<file>, or Codes.lst) address SONICR.EXE memory; the
 * interpreter below maps them onto the engine (see "Codes"). DLLFile mods
 * can't load, apart from ones the engine already covers (s_builtInDlls).
 */
#ifdef SONICR_MODS

#include "mods.h"

#include <SDL.h>

#include <ctype.h>
#include <dirent.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#define STBI_ONLY_PNG
#define STBI_ONLY_GIF
#define STBI_ONLY_BMP
#define STBI_NO_STDIO_WRITE
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include "thirdparty/stb_image.h"

#define MODS_DIR      "mods"
#define MODS_INI      MODS_DIR "/SonicRModLoader.ini"
#define MODS_CACHE    MODS_DIR "/.cache"
#define MODS_LOG      MODS_DIR "/ModLoader-android.log"
#define NULL_FILE     "\x01nullfile"

/* =====================================================================
 * Logging
 * ===================================================================== */

static FILE *s_log;

static void ModLog(const char *fmt, ...)
{
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "SonicR", "mods: %s", line);
#endif
    if (s_log) {
        fprintf(s_log, "%s\n", line);
        fflush(s_log);
    }
}

/* =====================================================================
 * String map: normalized game path -> replacement path
 * ===================================================================== */

typedef struct {
    char *key;
    char *value;
} MapEntry;

static MapEntry *s_map;
static size_t s_mapCap;
static size_t s_mapCount;

static uint64_t Hash(const char *s)
{
    uint64_t h = 1469598103934665603ULL;
    while (*s) {
        h ^= (unsigned char)*s++;
        h *= 1099511628211ULL;
    }
    return h;
}

static char *Dup(const char *s)
{
    size_t n = strlen(s) + 1;
    char *d = (char *)malloc(n);
    if (d) {
        memcpy(d, s, n);
    }
    return d;
}

static MapEntry *Find(const char *key)
{
    if (s_mapCap == 0) {
        return NULL;
    }
    size_t i = (size_t)(Hash(key) & (s_mapCap - 1));
    while (s_map[i].key) {
        if (strcmp(s_map[i].key, key) == 0) {
            return &s_map[i];
        }
        i = (i + 1) & (s_mapCap - 1);
    }
    return NULL;
}

static void Put(const char *key, const char *value);

static void Grow(void)
{
    size_t oldCap = s_mapCap;
    MapEntry *old = s_map;
    s_mapCap = oldCap ? oldCap * 2 : 1024;
    s_map = (MapEntry *)calloc(s_mapCap, sizeof(MapEntry));
    s_mapCount = 0;
    for (size_t i = 0; i < oldCap; i++) {
        if (old[i].key) {
            Put(old[i].key, old[i].value);
            free(old[i].key);
            free(old[i].value);
        }
    }
    free(old);
}

static void Put(const char *key, const char *value)
{
    if ((s_mapCount + 1) * 2 > s_mapCap) {
        Grow();
    }
    size_t i = (size_t)(Hash(key) & (s_mapCap - 1));
    while (s_map[i].key) {
        if (strcmp(s_map[i].key, key) == 0) {
            free(s_map[i].value);
            s_map[i].value = Dup(value);
            return;
        }
        i = (i + 1) & (s_mapCap - 1);
    }
    s_map[i].key = Dup(key);
    s_map[i].value = Dup(value);
    s_mapCount++;
}

/* Game path -> map key: forward slashes, no leading "./", lower case. */
static void Normalize(const char *in, char *out, size_t n)
{
    size_t o = 0;
    while (in[0] == '.' && (in[1] == '/' || in[1] == '\\')) {
        in += 2;
    }
    while (*in == '/' || *in == '\\') {
        in++;
    }
    for (; *in && o + 1 < n; in++) {
        char c = (*in == '\\') ? '/' : *in;
        if (c == '/' && o > 0 && out[o - 1] == '/') {
            continue;
        }
        out[o++] = (char)tolower((unsigned char)c);
    }
    out[o] = '\0';
}

/* "music/track5.ogg" -> "music/track5" (any extension stands in for a track). */
static void MusicKey(char *key)
{
    if (strncmp(key, "music/", 6) != 0) {
        return;
    }
    char *slash = strrchr(key, '/');
    char *dot = strrchr(key, '.');
    if (dot && dot > slash) {
        *dot = '\0';
    }
}

/* =====================================================================
 * INI reading (just enough for mod.ini / SonicRModLoader.ini)
 * ===================================================================== */

typedef void (*IniFn)(void *ctx, const char *group, const char *key, const char *value);

static char *Trim(char *s)
{
    while (isspace((unsigned char)*s)) {
        s++;
    }
    char *e = s + strlen(s);
    while (e > s && isspace((unsigned char)e[-1])) {
        *--e = '\0';
    }
    return s;
}

static int ReadIni(const char *path, IniFn fn, void *ctx)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        return 0;
    }
    char line[2048];
    char group[256] = "";
    int first = 1;
    while (fgets(line, sizeof(line), f)) {
        char *s = line;
        if (first) {
            first = 0;
            if ((unsigned char)s[0] == 0xEF && (unsigned char)s[1] == 0xBB &&
                (unsigned char)s[2] == 0xBF) {
                s += 3;     /* UTF-8 BOM */
            }
        }
        s = Trim(s);
        if (*s == '\0' || *s == ';' || *s == '#') {
            continue;
        }
        if (*s == '[') {
            char *end = strchr(s, ']');
            if (end) {
                *end = '\0';
                snprintf(group, sizeof(group), "%s", Trim(s + 1));
            }
            continue;
        }
        char *eq = strchr(s, '=');
        if (!eq) {
            continue;
        }
        *eq = '\0';
        fn(ctx, group, Trim(s), Trim(eq + 1));
    }
    fclose(f);
    return 1;
}

/* =====================================================================
 * Loading mods
 * ===================================================================== */

#define MAX_MODS 999

static char *s_modOrder[MAX_MODS + 1];   /* index = Mod<N> */
static int s_modsLoaded;

typedef struct {
    char from[512];
    char to[512];
} Replace;

static Replace *s_replaces;
static int s_replaceCount, s_replaceCap;

static void AddReplace(const char *from, const char *to)
{
    if (s_replaceCount == s_replaceCap) {
        s_replaceCap = s_replaceCap ? s_replaceCap * 2 : 32;
        s_replaces = (Replace *)realloc(s_replaces, (size_t)s_replaceCap * sizeof(Replace));
    }
    Normalize(from, s_replaces[s_replaceCount].from, sizeof(s_replaces[0].from));
    Normalize(to, s_replaces[s_replaceCount].to, sizeof(s_replaces[0].to));
    s_replaceCount++;
}

static void LoaderIniFn(void *ctx, const char *group, const char *key, const char *value)
{
    (void)ctx;
    if (group[0] != '\0') {
        return;
    }
    if ((key[0] == 'M' || key[0] == 'm') && (key[1] == 'o' || key[1] == 'O') &&
        (key[2] == 'd' || key[2] == 'D') && isdigit((unsigned char)key[3])) {
        int n = atoi(key + 3);
        if (n >= 1 && n <= MAX_MODS && value[0]) {
            free(s_modOrder[n]);
            s_modOrder[n] = Dup(value);
        }
    }
}

typedef struct {
    char name[256];
    char codes[256];
    int hasDll;
    int builtInDll;
} ModInfo;

/* ---- Codes ----
 * The Windows loader's codes files (same format as the SA Mod Loader's):
 *
 *   Code "Character Swap" Required
 *   ifne8 00502158 30
 *       ifkbkey 00000000 Z
 *           write8 007B747A 0
 *       endif
 *   endif
 *
 * Required codes run once per race frame. Addresses are SONICR.EXE memory,
 * so they're mapped onto the engine:
 *   - the player array (0x7B7388, stride 0x71C) maps onto g_playerBase, whose
 *     struct has the exe's exact layout (player_struct.h asserts it);
 *   - writes that RET out a known routine (s_knownCodes) set an engine flag;
 *   - any other address is a scratch byte, so codes that keep their own flags
 *     in spare memory (Sonic R Heroes uses 0x502158) still work.
 * A code using anything else (pointers, unknown ops) is skipped and logged. */
static int s_noBlink;

typedef struct {
    unsigned addr;
    unsigned value;
    int *flag;
    const char *what;
} KnownCode;

static const KnownCode s_knownCodes[] = {
    /* RET at the start of the two face-animation routines: characters stop
     * blinking, so models from mods don't get the original eye UVs. */
    { 0x00428CB0, 0xC3, &s_noBlink, "disable blinking" },
    { 0x00428D40, 0xC3, &s_noBlink, "disable blinking" },
};

#define EXE_PLAYER_BASE   0x007B7388u
#define EXE_PLAYER_STRIDE 0x71Cu
#define EXE_PLAYER_COUNT  10u

enum { OP_WRITE, OP_ADD, OP_SUB, OP_OR, OP_AND, OP_XOR,
       OP_IFEQ, OP_IFNE, OP_IFGT, OP_IFGE, OP_IFLT, OP_IFLE, OP_IFMASK, OP_IFKEY,
       OP_ELSE, OP_ENDIF, OP_NOP };

typedef struct {
    unsigned char op, size;   /* size in bytes (1, 2, 4) */
    unsigned addr;
    unsigned value;
    int scancode;             /* OP_IFKEY */
} CodeOp;

static CodeOp *s_ops;
static int s_opCount, s_opCap;

/* Scratch memory for addresses the engine doesn't have. */
typedef struct { unsigned addr; unsigned char used, value; } ScratchByte;
static ScratchByte s_scratch[1024];

static unsigned char *ScratchCell(unsigned addr)
{
    unsigned h = (addr * 2654435761u) & 1023u;
    for (int i = 0; i < 1024; i++, h = (h + 1) & 1023u) {
        if (!s_scratch[h].used) {
            s_scratch[h].used = 1;
            s_scratch[h].addr = addr;
            s_scratch[h].value = 0;
            return &s_scratch[h].value;
        }
        if (s_scratch[h].addr == addr) {
            return &s_scratch[h].value;
        }
    }
    static unsigned char sink;
    return &sink;
}

extern unsigned char *Mods_PlayerBytes(void);   /* main.c: (unsigned char *)g_playerBase */

static unsigned char *MemByte(unsigned addr)
{
    if (addr >= EXE_PLAYER_BASE && addr < EXE_PLAYER_BASE + EXE_PLAYER_STRIDE * EXE_PLAYER_COUNT) {
        return Mods_PlayerBytes() + (addr - EXE_PLAYER_BASE);
    }
    return ScratchCell(addr);
}

static unsigned MemRead(unsigned addr, int size)
{
    unsigned v = 0;
    for (int i = 0; i < size; i++) {
        v |= (unsigned)*MemByte(addr + i) << (8 * i);
    }
    return v;
}

static void MemWrite(unsigned addr, int size, unsigned v)
{
    for (int i = 0; i < size; i++) {
        *MemByte(addr + i) = (unsigned char)(v >> (8 * i));
    }
}

static int ParseOp(const char *word, CodeOp *op)
{
    static const struct { const char *name; int op; } names[] = {
        { "write", OP_WRITE }, { "add", OP_ADD }, { "sub", OP_SUB }, { "or", OP_OR },
        { "and", OP_AND }, { "xor", OP_XOR }, { "ifeq", OP_IFEQ }, { "ifne", OP_IFNE },
        { "ifgt", OP_IFGT }, { "ifge", OP_IFGE }, { "iflt", OP_IFLT }, { "ifle", OP_IFLE },
        { "ifmask", OP_IFMASK },
    };
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        size_t n = strlen(names[i].name);
        if (strncasecmp(word, names[i].name, n) != 0) continue;
        const char *sz = word + n;
        if (strcmp(sz, "8") == 0) op->size = 1;
        else if (strcmp(sz, "16") == 0) op->size = 2;
        else if (strcmp(sz, "32") == 0) op->size = 4;
        else continue;
        op->op = (unsigned char)names[i].op;
        return 1;
    }
    return 0;
}

static int KeyScancode(const char *name)
{
    if (strlen(name) == 1) {
        char k[2] = { (char)toupper((unsigned char)name[0]), 0 };
        return SDL_GetScancodeFromName(k);
    }
    return SDL_GetScancodeFromName(name);
}

static void AddOp(const CodeOp *op)
{
    if (s_opCount == s_opCap) {
        s_opCap = s_opCap ? s_opCap * 2 : 256;
        s_ops = realloc(s_ops, (size_t)s_opCap * sizeof(CodeOp));
    }
    s_ops[s_opCount++] = *op;
}

/* Reads one codes file; keeps the Required codes it can run. */
static void ApplyCodes(const char *path, const char *modName)
{
    FILE *f = fopen(path, "r");
    if (!f) {
        return;
    }
    char line[512], name[256] = "";
    int required = 0, ok = 1, depth = 0;
    int start = s_opCount;       /* first op of the current code */
    int kept = 0, skipped = 0;

#define FINISH_CODE() do { \
        if (name[0]) { \
            if (required && ok && depth == 0) { kept++; \
                ModLog("   %s: code %s", modName, name); } \
            else { s_opCount = start; \
                if (required) { skipped++; ModLog("   %s: code %s can't run on Android, skipped", modName, name); } } \
        } \
        start = s_opCount; ok = 1; depth = 0; required = 0; name[0] = '\0'; \
    } while (0)

    while (fgets(line, sizeof(line), f)) {
        char *t = Trim(line);
        if (!t[0] || t[0] == ';' || (t[0] == '/' && t[1] == '/')) continue;
        if (strncasecmp(t, "Code ", 5) == 0 || strncasecmp(t, "Patch ", 6) == 0) {
            FINISH_CODE();
            snprintf(name, sizeof(name), "%s", strchr(t, ' ') + 1);
            required = strstr(t, "Required") != NULL;
            continue;
        }
        if (!name[0] || !ok) continue;

        char w0[32] = "", w1[64] = "", w2[64] = "";
        int n = sscanf(t, "%31s %63s %63s", w0, w1, w2);
        CodeOp op;
        memset(&op, 0, sizeof(op));
        if (strcasecmp(w0, "endif") == 0) { op.op = OP_ENDIF; depth--; }
        else if (strcasecmp(w0, "else") == 0) { op.op = OP_ELSE; }
        else if (strcasecmp(w0, "ifkbkey") == 0 && n >= 3) {
            op.op = OP_IFKEY;
            op.scancode = KeyScancode(w2);
            if (op.scancode == SDL_SCANCODE_UNKNOWN) ok = 0;
            depth++;
        }
        else if (n >= 3 && ParseOp(w0, &op) && w1[0] != 'p' && w1[0] != 'P') {
            op.addr = (unsigned)strtoul(w1, NULL, 16);
            op.value = (unsigned)strtoul(w2, NULL, 0);
            if (op.op >= OP_IFEQ && op.op <= OP_IFMASK) depth++;
            if (op.op == OP_WRITE) {
                for (size_t i = 0; i < sizeof(s_knownCodes) / sizeof(s_knownCodes[0]); i++) {
                    if (s_knownCodes[i].addr == op.addr && s_knownCodes[i].value == op.value) {
                        *s_knownCodes[i].flag = 1;
                        ModLog("   %s: %s", modName, s_knownCodes[i].what);
                        op.op = OP_NOP;
                    }
                }
            }
        }
        else {
            ok = 0;    /* an op we can't run: drop the whole code */
            continue;
        }
        AddOp(&op);
    }
    FINISH_CODE();
#undef FINISH_CODE
    fclose(f);
    (void)kept; (void)skipped;
}

int Mods_NoBlink(void)
{
    return s_noBlink;
}

static int Test(const CodeOp *op)
{
    if (op->op == OP_IFKEY) {
        const Uint8 *keys = SDL_GetKeyboardState(NULL);
        return keys && op->scancode > 0 && keys[op->scancode];
    }
    unsigned v = MemRead(op->addr, op->size);
    switch (op->op) {
        case OP_IFEQ: return v == op->value;
        case OP_IFNE: return v != op->value;
        case OP_IFGT: return v > op->value;
        case OP_IFGE: return v >= op->value;
        case OP_IFLT: return v < op->value;
        case OP_IFLE: return v <= op->value;
        case OP_IFMASK: return (v & op->value) == op->value;
    }
    return 0;
}

void Mods_RunCodes(void)
{
    if (s_opCount == 0 || Mods_PlayerBytes() == NULL) {
        return;
    }
    /* skip > 0: inside that many false (or already-taken) branches. */
    int skip = 0;
    for (int i = 0; i < s_opCount; i++) {
        const CodeOp *op = &s_ops[i];
        if (op->op == OP_ENDIF) {
            if (skip > 0) skip--;
            continue;
        }
        if (op->op == OP_ELSE) {
            if (skip == 1) skip = 0;          /* the if was false: run the else */
            else if (skip == 0) skip = 1;     /* the if ran: skip the else */
            continue;
        }
        if (op->op >= OP_IFEQ && op->op <= OP_IFKEY) {
            if (skip > 0 || !Test(op)) skip++;
            continue;
        }
        if (skip > 0 || op->op == OP_NOP) continue;
        unsigned mask = op->size == 4 ? 0xFFFFFFFFu : (1u << (8 * op->size)) - 1u;
        unsigned v = MemRead(op->addr, op->size);
        switch (op->op) {
            case OP_WRITE: v = op->value; break;
            case OP_ADD: v += op->value; break;
            case OP_SUB: v -= op->value; break;
            case OP_OR:  v |= op->value; break;
            case OP_AND: v &= op->value; break;
            case OP_XOR: v ^= op->value; break;
        }
        MemWrite(op->addr, op->size, v & mask);
    }
}

static void ModIniFn(void *ctx, const char *group, const char *key, const char *value)
{
    ModInfo *info = (ModInfo *)ctx;
    if (group[0] == '\0') {
        if (strcasecmp(key, "Name") == 0) {
            snprintf(info->name, sizeof(info->name), "%s", value);
        }
        else if (strcasecmp(key, "DLLFile") == 0 && value[0]) {
            if (strcasecmp(value, "SonicR_ColorFix.dll") == 0) {
                /* Saturn-style vertex colouring (D3D ADDSIGNED for the track
                 * and characters) is how this engine renders already. */
                info->builtInDll = 1;
            }
            else {
                info->hasDll = 1;
            }
        }
        else if (strcasecmp(key, "Codes") == 0 && value[0]) {
            snprintf(info->codes, sizeof(info->codes), "%s", value);
        }
    }
    else if (strcasecmp(group, "IgnoreFiles") == 0) {
        char k[512];
        Normalize(key, k, sizeof(k));
        Put(k, NULL_FILE);
    }
    else if (strcasecmp(group, "ReplaceFiles") == 0) {
        AddReplace(key, value);
    }
    else if (strcasecmp(group, "SwapFiles") == 0) {
        AddReplace(key, value);
        AddReplace(value, key);
    }
}

static int ScanFolder(const char *dir, size_t rootLen)
{
    DIR *d = opendir(dir);
    if (!d) {
        return 0;
    }
    int count = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') {
            continue;
        }
        char path[1024];
        snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
        struct stat st;
        if (stat(path, &st) != 0) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            count += ScanFolder(path, rootLen);
        }
        else if (S_ISREG(st.st_mode)) {
            char key[1024];
            Normalize(path + rootLen, key, sizeof(key));
            MusicKey(key);
            Put(key, path);
            count++;
        }
    }
    closedir(d);
    return count;
}

/* Exact-case lookup of child `name` in `dir` (mod folders may use any case). */
static int FindChild(const char *dir, const char *name, char *out, size_t n)
{
    DIR *d = opendir(dir);
    if (!d) {
        return 0;
    }
    struct dirent *e;
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        if (strcasecmp(e->d_name, name) == 0) {
            snprintf(out, n, "%s/%s", dir, e->d_name);
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

void Mods_Init(void)
{
    mkdir(MODS_DIR, 0775);
    s_log = fopen(MODS_LOG, "w");

    if (!ReadIni(MODS_INI, LoaderIniFn, NULL)) {
        ModLog("No %s; playing without mods.", MODS_INI);
        return;
    }

    for (int n = 1; n <= MAX_MODS; n++) {
        if (!s_modOrder[n]) {
            break;      /* the Windows loader also stops at the first gap */
        }
        char modDir[512];
        snprintf(modDir, sizeof(modDir), MODS_DIR "/%s", s_modOrder[n]);
        for (char *p = modDir; *p; p++) {
            if (*p == '\\') *p = '/';
        }

        char ini[640];
        ModInfo info;
        memset(&info, 0, sizeof(info));
        snprintf(info.name, sizeof(info.name), "%s", s_modOrder[n]);
        if (!FindChild(modDir, "mod.ini", ini, sizeof(ini)) ||
            !ReadIni(ini, ModIniFn, &info)) {
            ModLog("%d. %s: mod.ini missing, skipped", n, s_modOrder[n]);
            continue;
        }

        int files = 0;
        char filesDir[640];
        if (FindChild(modDir, "files", filesDir, sizeof(filesDir))) {
            files = ScanFolder(filesDir, strlen(filesDir) + 1);
        }

        ModLog("%d. %s: %d files%s", n, info.name, files,
               info.hasDll ? " (its DLL can't load on Android)"
               : info.builtInDll ? " (what its DLL does is built into this engine)" : "");
        char codes[640];
        if ((info.codes[0] && FindChild(modDir, info.codes, codes, sizeof(codes)))
                || FindChild(modDir, "Codes.lst", codes, sizeof(codes))) {
            ApplyCodes(codes, info.name);
        }
        s_modsLoaded++;
    }

    /* ReplaceFiles/SwapFiles point at another game path; follow it through
     * the files the mods supply, as the Windows loader does. */
    for (int i = 0; i < s_replaceCount; i++) {
        MapEntry *target = Find(s_replaces[i].to);
        char dest[1024];
        snprintf(dest, sizeof(dest), "%s", target ? target->value : s_replaces[i].to);
        char key[512];
        snprintf(key, sizeof(key), "%s", s_replaces[i].from);
        MusicKey(key);
        Put(key, dest);
    }

    ModLog("%d mods loaded, %zu replaced paths", s_modsLoaded, s_mapCount);
}

int Mods_Active(void)
{
    return s_mapCount > 0;
}

/* =====================================================================
 * Lookups
 * ===================================================================== */

static const char *Lookup(const char *path)
{
    if (s_mapCount == 0) {
        return NULL;
    }
    char key[1024];
    Normalize(path, key, sizeof(key));
    MusicKey(key);
    MapEntry *e = Find(key);
    return e ? e->value : NULL;
}

const char *Mods_Resolve(const char *path)
{
    const char *r = Lookup(path);
    if (r && strcmp(r, NULL_FILE) == 0) {
        return NULL;
    }
    return r ? r : path;
}

int Mods_FindMusic(int track, char *out, size_t n)
{
    if (s_mapCount == 0) {
        return 0;
    }
    char key[64];
    snprintf(key, sizeof(key), "music/track%d", track);
    MapEntry *e = Find(key);
    if (!e || strcmp(e->value, NULL_FILE) == 0) {
        return 0;
    }
    snprintf(out, n, "%s", e->value);
    return 1;
}

/* =====================================================================
 * Image -> RAW texture replacement
 * ===================================================================== */

static int EndsWithCI(const char *s, const char *suffix)
{
    size_t a = strlen(s), b = strlen(suffix);
    return a >= b && strcasecmp(s + a - b, suffix) == 0;
}

/* Area-average (down) / nearest (up) resample of RGB pixels. */
static void Resample(const unsigned char *src, int sw, int sh,
                     unsigned char *dst, int dw, int dh)
{
    for (int y = 0; y < dh; y++) {
        int y0 = (int)((long long)y * sh / dh);
        int y1 = (int)((long long)(y + 1) * sh / dh);
        if (y1 <= y0) y1 = y0 + 1;
        for (int x = 0; x < dw; x++) {
            int x0 = (int)((long long)x * sw / dw);
            int x1 = (int)((long long)(x + 1) * sw / dw);
            if (x1 <= x0) x1 = x0 + 1;
            unsigned r = 0, g = 0, b = 0, c = 0;
            for (int yy = y0; yy < y1 && yy < sh; yy++) {
                const unsigned char *p = src + ((size_t)yy * sw + x0) * 3;
                for (int xx = x0; xx < x1 && xx < sw; xx++, p += 3) {
                    r += p[0]; g += p[1]; b += p[2]; c++;
                }
            }
            unsigned char *o = dst + ((size_t)y * dw + x) * 3;
            o[0] = (unsigned char)(c ? r / c : 0);
            o[1] = (unsigned char)(c ? g / c : 0);
            o[2] = (unsigned char)(c ? b / c : 0);
        }
    }
}

/* Builds (or reuses) mods/.cache/<...>.raw holding `image` converted to
 * `rawBytes` of RGB at the original texture's dimensions. */
static int ImageToRaw(const char *image, long rawBytes, char *out, size_t n)
{
    struct stat st;
    if (stat(image, &st) != 0) {
        return 0;
    }
    mkdir(MODS_CACHE, 0775);
    snprintf(out, n, MODS_CACHE "/%016llx_%ld_%lld.raw",
             (unsigned long long)Hash(image), rawBytes, (long long)st.st_mtime);
    if (stat(out, &st) == 0 && st.st_size == rawBytes) {
        return 1;
    }

    int w, h, comp;
    unsigned char *px = stbi_load(image, &w, &h, &comp, 3);
    if (!px) {
        ModLog("Can't read %s: %s", image, stbi_failure_reason());
        return 0;
    }

    long pixels = rawBytes / 3;
    int dw = w, dh = h;
    if ((long)w * h != pixels) {
        /* RAWs carry no header, so pick the original's dimensions from its
         * size: the common widths that divide it, nearest the image's shape.
         * The image is then stretched to fit (hi-res packs, other aspects). */
        static const int widths[] = { 256, 640, 128, 512, 320, 1024, 64, 32 };
        double aspect = (double)w / h;
        double best = 1e9;
        dw = 0;
        for (size_t i = 0; i < sizeof(widths) / sizeof(widths[0]); i++) {
            int cw = widths[i];
            if (pixels % cw != 0) {
                continue;
            }
            int ch = (int)(pixels / cw);
            double err = fabs(log(((double)cw / ch) / aspect));
            if (err < best) {
                best = err;
                dw = cw;
                dh = ch;
            }
        }
        if (dw == 0) {
            dw = (int)lround(sqrt((double)pixels * aspect));
            if (dw <= 0) dw = 1;
            dh = (int)(pixels / dw);
        }
    }

    unsigned char *raw = (unsigned char *)calloc((size_t)rawBytes, 1);
    if (!raw || dw <= 0 || dh <= 0) {
        free(raw);
        stbi_image_free(px);
        return 0;
    }
    if (dw == w && dh == h) {
        memcpy(raw, px, (size_t)((long)w * h * 3 < rawBytes ? (long)w * h * 3 : rawBytes));
    }
    else {
        unsigned char *tmp = (unsigned char *)malloc((size_t)dw * dh * 3);
        if (tmp) {
            Resample(px, w, h, tmp, dw, dh);
            long bytes = (long)dw * dh * 3;
            memcpy(raw, tmp, (size_t)(bytes < rawBytes ? bytes : rawBytes));
            free(tmp);
        }
    }
    stbi_image_free(px);

    FILE *f = fopen(out, "wb");
    int ok = 0;
    if (f) {
        ok = fwrite(raw, 1, (size_t)rawBytes, f) == (size_t)rawBytes;
        fclose(f);
    }
    free(raw);
    if (ok) {
        ModLog("Texture %s -> %dx%d (from %dx%d)", image, dw, dh, w, h);
    }
    return ok;
}

static long FileSize(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0 ? (long)st.st_size : -1;
}

FILE *Mods_Open(const char *path, const char *mode)
{
    if (s_mapCount == 0 || strchr(mode, 'w') || strchr(mode, 'a') || strchr(mode, '+')) {
        return fopen(path, mode);
    }

    const char *resolved = Lookup(path);
    if (resolved && strcmp(resolved, NULL_FILE) == 0) {
        return NULL;
    }

    if (EndsWithCI(path, ".raw")) {
        static const char *const exts[] = { ".png", ".gif", ".bmp" };
        char alt[1024];
        size_t base = strlen(path) - 4;
        for (int i = 0; i < 3; i++) {
            snprintf(alt, sizeof(alt), "%.*s%s", (int)base, path, exts[i]);
            const char *img = Lookup(alt);
            if (!img || strcmp(img, NULL_FILE) == 0) {
                continue;
            }
            long rawBytes = FileSize(resolved ? resolved : path);
            if (rawBytes <= 0) {
                rawBytes = 256 * 256 * 3;
            }
            char cache[1024];
            if (ImageToRaw(img, rawBytes, cache, sizeof(cache))) {
                return fopen(cache, mode);
            }
        }
    }

    return fopen(resolved ? resolved : path, mode);
}

#endif /* SONICR_MODS */
