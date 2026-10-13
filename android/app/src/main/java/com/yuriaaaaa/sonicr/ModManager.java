package com.yuriaaaaa.sonicr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Windows Sonic R Mod Manager's format, so mods and lists move between PC and phone:
 * mods/&lt;folder&gt;/mod.ini (Name, Author, Version, Description, DLLFile) with a files/
 * folder, and mods/SonicRModLoader.ini listing the enabled mods as Mod1=, Mod2=, ...
 * The engine reads the same files (source/sdl/src/mods.c). Later mods win.
 */
final class ModManager {
    /** Set by init(); every screen calls it in onCreate. */
    static File MODS;

    static void init(android.content.Context context) {
        MODS = Paths.mods(context);
    }

    static File loaderIni() {
        return new File(MODS, "SonicRModLoader.ini");
    }

    static final class Mod {
        String folder;      // name under mods/
        File directory;
        String title = "", author = "", version = "", description = "";
        boolean enabled;
        final List<String> notes = new ArrayList<>();
    }

    final List<Mod> mods = new ArrayList<>();
    /** Lines of SonicRModLoader.ini other than ModN=, kept as they are. */
    private final List<String> otherLines = new ArrayList<>();

    void load() {
        MODS.mkdirs();
        Map<String, Mod> found = new LinkedHashMap<>();
        File[] entries = MODS.listFiles();
        if (entries != null) {
            java.util.Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File dir : entries) {
                if (!dir.isDirectory() || dir.getName().startsWith("."))
                    continue;
                File ini = child(dir, "mod.ini");
                if (ini == null)
                    continue;
                found.put(dir.getName().toLowerCase(Locale.ROOT), parse(dir, ini));
            }
        }

        mods.clear();
        otherLines.clear();
        List<String> order = new ArrayList<>();
        for (String line : readLines(loaderIni())) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (eq > 3 && t.regionMatches(true, 0, "Mod", 0, 3) && isDigits(t.substring(3, eq).trim())) {
                order.add(t.substring(eq + 1).trim());
                continue;
            }
            if (!t.isEmpty())
                otherLines.add(line);
        }
        for (String name : order) {
            Mod mod = found.remove(name.replace('\\', '/').toLowerCase(Locale.ROOT));
            if (mod != null) {
                mod.enabled = true;
                mods.add(mod);
            }
        }
        mods.addAll(found.values());
    }

    void save() throws IOException {
        MODS.mkdirs();
        StringBuilder out = new StringBuilder();
        for (String line : otherLines)
            out.append(line).append("\r\n");
        int n = 1;
        for (Mod mod : mods)
            if (mod.enabled)
                out.append("Mod").append(n++).append('=').append(mod.folder).append("\r\n");
        try (OutputStream o = new FileOutputStream(loaderIni())) {
            o.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    int enabledCount() {
        int n = 0;
        for (Mod mod : mods)
            if (mod.enabled)
                n++;
        return n;
    }

    /** Switches on the mods in these folders (added last, so they win). */
    static void enable(List<File> folders) throws IOException {
        ModManager manager = new ModManager();
        manager.load();
        for (File folder : folders) {
            for (int i = 0; i < manager.mods.size(); i++) {
                Mod mod = manager.mods.get(i);
                if (mod.directory.equals(folder) && !mod.enabled) {
                    mod.enabled = true;
                    manager.mods.remove(i);
                    manager.mods.add(manager.enabledCount(), mod);
                    break;
                }
            }
        }
        manager.save();
    }

    private static Mod parse(File dir, File ini) {
        Mod mod = new Mod();
        mod.folder = dir.getName();
        mod.directory = dir;
        mod.title = dir.getName();
        String group = "";
        String dll = "";
        for (String line : readLines(ini)) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                group = t.substring(1, t.length() - 1).trim();
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0 || !group.isEmpty())
                continue;
            String key = t.substring(0, eq).trim(), value = t.substring(eq + 1).trim();
            if (key.equalsIgnoreCase("Name") && !value.isEmpty()) mod.title = value;
            else if (key.equalsIgnoreCase("Author")) mod.author = value;
            else if (key.equalsIgnoreCase("Version")) mod.version = value;
            else if (key.equalsIgnoreCase("Description")) mod.description = value.replace("\\n", "\n");
            else if (key.equalsIgnoreCase("DLLFile")) dll = value;
        }
        if (!dll.isEmpty())
            mod.notes.add("Has a Windows DLL (" + dll + "), which can't run on Android. Its files still "
                    + "apply, but parts of the mod may be missing.");
        if (child(dir, "Codes.lst") != null)
            mod.notes.add("Has cheat codes (Codes.lst) for the Windows exe; they're ignored here.");
        if (child(dir, "files") == null && dll.isEmpty())
            mod.notes.add("Has no files folder, so it changes nothing.");
        return mod;
    }

    static File child(File dir, String name) {
        File[] children = dir.listFiles();
        if (children != null)
            for (File c : children)
                if (c.getName().equalsIgnoreCase(name))
                    return c;
        return null;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty())
            return false;
        for (int i = 0; i < s.length(); i++)
            if (!Character.isDigit(s.charAt(i)))
                return false;
        return true;
    }

    static List<String> readLines(File file) {
        List<String> out = new ArrayList<>();
        if (!file.isFile())
            return out;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1 << 20)];
            int off = 0, n;
            while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0)
                off += n;
            String text = new String(bytes, 0, off, StandardCharsets.UTF_8);
            if (text.startsWith("﻿"))
                text = text.substring(1);
            for (String line : text.split("\r?\n"))
                out.add(line);
        } catch (IOException ignored) {
        }
        return out;
    }
}
