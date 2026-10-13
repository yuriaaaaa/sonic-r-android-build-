package com.yuriaaaaa.sonicr;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads a mod file from GameBanana (or takes an archive the player picked) and unpacks it
 * straight into SonicR/mods, without going through the Downloads folder. Each folder holding a
 * mod.ini becomes one mod; a download without one (a bare files/ folder, or loose game folders)
 * gets a mod.ini written for it. Installed folders get a .gamebanana.json so the browser can
 * show what's installed and offer updates.
 */
final class ModInstaller {
    static final String METADATA = ".gamebanana.json";
    private static File incomingRoot() {
        return new File(ModManager.MODS, ".incoming");
    }

    interface Progress {
        /** Return false to cancel. */
        boolean update(String message, long done, long total);
    }

    static final class Installed {
        File folder;            // mod folder, or null for a patches-only install
        File metadata;
        final List<File> patchFiles = new ArrayList<>();
        int modId, fileId;
        String name = "", fileName = "", version = "";
        long updated, fileAdded, installedAt;

        String where() {
            return "SonicR/mods/" + (folder != null ? folder.getName() : "");
        }
    }

    static final class Result {
        Installed installed;
        boolean looksLikeMod;   // came with a mod.ini
        final List<File> folders = new ArrayList<>();   // every mod folder it added
    }

    /** Everything installed from GameBanana. */
    static List<Installed> installedAll() {
        List<Installed> out = new ArrayList<>();
        File[] folders = ModManager.MODS.listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                Installed i = readMetadata(new File(folder, METADATA));
                if (i != null) {
                    i.folder = folder;
                    out.add(i);
                }
            }
        }
        return out;
    }

    /** The most recent install of each mod, by mod id. */
    static Map<Integer, Installed> installed() {
        Map<Integer, Installed> out = new HashMap<>();
        for (Installed i : installedAll()) {
            Installed other = out.get(i.modId);
            if (other == null || i.installedAt > other.installedAt)
                out.put(i.modId, i);
        }
        return out;
    }

    private static Installed readMetadata(File meta) {
        if (!meta.isFile())
            return null;
        try {
            JSONObject json = new JSONObject(read(meta));
            Installed i = new Installed();
            i.metadata = meta;
            i.modId = json.optInt("modId");
            i.name = json.optString("name", "");
            i.fileId = json.optInt("fileId");
            i.fileName = json.optString("file", "");
            i.version = json.optString("version", "");
            i.updated = json.optLong("updated");
            i.fileAdded = json.optLong("fileAdded");
            i.installedAt = json.optLong("installed");
            return i.modId != 0 ? i : null;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * Downloads, checks and unpacks the file into SonicR/mods.
     * replace is an earlier install of the same mod to swap out, or null to install alongside.
     */
    static Result install(File cacheDir, GameBanana.Mod mod, GameBanana.ModFile file, Installed replace,
                          Progress progress) throws IOException {
        File downloads = new File(cacheDir, "gamebanana");
        downloads.mkdirs();
        File archive = new File(downloads, file.id + "-" + safeName(file.name));
        try {
            download(file, archive, progress);
            JSONObject meta = metadata(mod, file);
            Result result = unpackAndInstall(archive, file.name, mod.name, replace, meta, progress);
            Installed installed = result.installed;
            installed.modId = mod.id;
            installed.name = mod.name;
            installed.fileId = file.id;
            installed.fileName = file.name;
            installed.version = meta.optString("version");
            installed.updated = mod.updated;
            installed.fileAdded = file.added;
            installed.installedAt = meta.optLong("installed");
            return result;
        } finally {
            archive.delete();
        }
    }

    /** Installs an archive (or a single mod file) the player picked. */
    static Result installLocal(File archive, String fileName, Progress progress) throws IOException {
        String title = stripExtension(fileName);
        return unpackAndInstall(archive, fileName, title, null, null, progress);
    }

    private static Result unpackAndInstall(File archive, String fileName, String title, Installed replace,
                                           JSONObject meta, Progress progress) throws IOException {
        File incoming = new File(incomingRoot(), String.valueOf(System.nanoTime()));
        try {
            deleteRecursively(incoming);
            if (!incoming.mkdirs())
                throw new IOException("Can't write to SonicR/mods. Allow file access in the launcher.");
            if (isArchive(fileName)) {
                progress.update("Unpacking " + fileName + "…", 0, archive.length());
                ArchiveExtractor.extract(archive, incoming, (done, total) ->
                        progress.update("Unpacking " + fileName + "…", done, total));
            } else {
                copy(archive, new File(incoming, fileName));
            }
            if (!containsFile(incoming))
                throw new IOException("The download is empty");
            if (!progress.update("Installing…", 0, 0))
                throw new IOException("Cancelled");

            Result result = new Result();
            List<File> roots = new ArrayList<>();
            findModRoots(incoming, 0, roots);
            result.looksLikeMod = !roots.isEmpty();
            if (roots.isEmpty())
                roots.add(wrapLooseFiles(incoming, title));

            for (int r = 0; r < roots.size(); r++) {
                File root = roots.get(r);
                String name = modName(root, roots.size() == 1 ? title : root.getName());
                Installed into = (r == 0) ? replace : null;
                File target = moveIn(root, name, into);
                if (meta != null)
                    write(new File(target, METADATA), meta.toString());
                result.folders.add(target);
                if (r == 0) {
                    Installed installed = new Installed();
                    installed.folder = target;
                    installed.metadata = new File(target, METADATA);
                    installed.name = name;
                    installed.fileName = fileName;
                    result.installed = installed;
                }
            }
            return result;
        } finally {
            deleteRecursively(incomingRoot());
        }
    }

    /** Folders holding a mod.ini, shallowest first; nothing inside a found mod is searched. */
    private static void findModRoots(File folder, int depth, List<File> out) {
        if (ModManager.child(folder, "mod.ini") != null) {
            out.add(folder);
            return;
        }
        if (depth >= 4)
            return;
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null)
            return;
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children)
            findModRoots(child, depth + 1, out);
    }

    private static final String[] GAME_FOLDERS = {
        "general", "island", "city", "ruin", "factory", "emerald", "ai", "bin", "sound", "music"
    };

    /**
     * No mod.ini: find a files/ folder, or the game folders themselves, and build a mod around it.
     */
    private static File wrapLooseFiles(File incoming, String title) throws IOException {
        File filesParent = findFilesFolderParent(incoming, 0);
        if (filesParent != null) {
            writeModIni(filesParent, title);
            return filesParent;
        }
        File gameRoot = findGameRoot(incoming, 0);
        if (gameRoot == null)
            gameRoot = incoming;
        File wrapper = new File(incomingRoot(), "wrap-" + System.nanoTime());
        File files = new File(wrapper, "files");
        if (!wrapper.mkdirs() || !gameRoot.renameTo(files))
            throw new IOException("Can't set up the mod folder");
        writeModIni(wrapper, title);
        return wrapper;
    }

    private static File findFilesFolderParent(File folder, int depth) {
        File files = ModManager.child(folder, "files");
        if (files != null && files.isDirectory())
            return folder;
        if (depth >= 3)
            return null;
        File[] children = folder.listFiles(File::isDirectory);
        if (children != null)
            for (File child : children) {
                File found = findFilesFolderParent(child, depth + 1);
                if (found != null)
                    return found;
            }
        return null;
    }

    private static File findGameRoot(File folder, int depth) {
        File[] children = folder.listFiles();
        if (children == null)
            return null;
        for (File child : children)
            for (String game : GAME_FOLDERS)
                if (child.isDirectory() && child.getName().equalsIgnoreCase(game))
                    return folder;
        if (depth >= 3)
            return null;
        for (File child : children)
            if (child.isDirectory()) {
                File found = findGameRoot(child, depth + 1);
                if (found != null)
                    return found;
            }
        return null;
    }

    private static void writeModIni(File folder, String title) throws IOException {
        write(new File(folder, "mod.ini"), "Name=" + title + "\r\nDescription=Installed without a mod.ini\r\n");
    }

    /** The mod's Name= from its mod.ini, else the fallback. */
    private static String modName(File root, String fallback) {
        File ini = ModManager.child(root, "mod.ini");
        if (ini != null)
            for (String line : ModManager.readLines(ini)) {
                String t = line.trim();
                if (t.toLowerCase(Locale.ROOT).startsWith("name=") && t.length() > 5)
                    return t.substring(5).trim();
            }
        return fallback;
    }

    /** Moves a mod folder into SonicR/mods, replacing `replace`'s folder if given. */
    private static File moveIn(File source, String name, Installed replace) throws IOException {
        File target;
        if (replace != null && replace.folder != null) {
            target = replace.folder;
        } else {
            String base = safeName(name);
            target = new File(ModManager.MODS, base);
            for (int n = 2; target.exists(); n++)
                target = new File(ModManager.MODS, base + " (" + n + ")");
        }
        File backup = null;
        if (target.exists()) {
            backup = new File(incomingRoot(), "previous-" + System.nanoTime());
            if (!target.renameTo(backup))
                throw new IOException("Can't replace " + target.getName() + " (is a file in it open?)");
        }
        if (!source.renameTo(target)) {
            if (backup != null)
                backup.renameTo(target);
            throw new IOException("Can't move the mod into " + target);
        }
        if (backup != null)
            deleteRecursively(backup);
        return target;
    }

    /** Removes an install's folder. */
    static void uninstall(Installed installed) {
        if (installed.folder != null)
            deleteRecursively(installed.folder);
    }

    private static JSONObject metadata(GameBanana.Mod mod, GameBanana.ModFile file) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("modId", mod.id);
            meta.put("name", mod.name);
            meta.put("fileId", file.id);
            meta.put("file", file.name);
            meta.put("version", file.version.isEmpty() ? mod.version : file.version);
            meta.put("updated", mod.updated);
            meta.put("fileAdded", file.added);
            meta.put("installed", System.currentTimeMillis() / 1000);
            meta.put("source", mod.profileUrl);
        } catch (JSONException ignored) {
        }
        return meta;
    }

    private static void download(GameBanana.ModFile file, File destination, Progress progress) throws IOException {
        String url = "https://gamebanana.com/dl/" + file.id;
        HttpURLConnection connection = GameBanana.open(url);
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try {
            int code = connection.getResponseCode();
            if (code != 200)
                throw new IOException("The download failed (GameBanana answered " + code + ")");
            long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : file.size;
            long done = 0, lastReport = 0;
            if (!progress.update("Downloading " + file.name + "…", 0, total))
                throw new IOException("Cancelled");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    md5.update(buffer, 0, n);
                    done += n;
                    if (done - lastReport >= 256 * 1024 || done == total) {
                        lastReport = done;
                        if (!progress.update("Downloading " + file.name + "…", done, total))
                            throw new IOException("Cancelled");
                    }
                }
            }
            if (total > 0 && done < total)
                throw new IOException("The download stopped early. Try again.");
        } finally {
            connection.disconnect();
        }
        if (!file.md5.isEmpty()) {
            StringBuilder hex = new StringBuilder();
            for (byte b : md5.digest())
                hex.append(String.format(Locale.ROOT, "%02x", b));
            if (!hex.toString().equalsIgnoreCase(file.md5))
                throw new IOException("The download is damaged (checksum mismatch). Try again.");
        }
    }

    private static boolean containsFile(File folder) {
        File[] children = folder.listFiles();
        if (children == null)
            return false;
        for (File child : children)
            if (child.isFile() || containsFile(child))
                return true;
        return false;
    }

    static boolean isArchive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[] { ".zip", ".7z", ".rar", ".tar", ".gz", ".tgz", ".xz", ".bz2" })
            if (lower.endsWith(ext))
                return true;
        return false;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (clean.startsWith("."))
            clean = clean.substring(1).trim();
        if (clean.length() > 80)
            clean = clean.substring(0, 80).trim();
        return clean.isEmpty() ? "GameBanana mod" : clean;
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null)
            for (File child : children)
                deleteRecursively(child);
        file.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[1 << 20];
            int n;
            while ((n = in.read(buffer)) > 0)
                out.write(buffer, 0, n);
        }
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0)
                    break;
                off += n;
            }
            return new String(bytes, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
