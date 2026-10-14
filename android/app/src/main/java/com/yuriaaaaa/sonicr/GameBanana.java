package com.yuriaaaaa.sonicr;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small client for GameBanana's public API (apiv11), limited to Sonic R
 * (game 6165): mod lists, search, mod pages and file downloads.
 */
final class GameBanana {
    static final int GAME_ID = 6165;
    private static final String API = "https://gamebanana.com/apiv11";
    static final String USER_AGENT = "SonicRAndroid/0.2 (+launcher mod browser)";

    static final class Mod {
        int id;
        String name = "", author = "", category = "", version = "", thumbnail = "", profileUrl = "";
        int likes, views;
        long updated;     // seconds: last update, else last change, else added
        long dateUpdated; // seconds: when the author last posted an update (0 if never)
    }

    static final class ModFile {
        int id;
        String name = "", description = "", version = "", md5 = "", downloadUrl = "";
        long size, added;
        int downloads;
    }

    static final class Details {
        Mod mod;
        String text = "";       // HTML
        String image = "";      // large screenshot
        int downloads;
        final List<ModFile> files = new ArrayList<>();
    }

    /** A batch of mods, and where the next batch starts. */
    static final class Page {
        final List<Mod> mods = new ArrayList<>();
        int next;
        boolean complete;
        boolean fallback;   // search done by scanning the mod list (GameBanana's search didn't answer)
    }

    /**
     * Mods from the game's feed, starting at feed page `page`: sort is "new" (newest) or "updated"
     * (recently updated). The feed also carries questions and requests, so keep reading pages
     * until a few mods turn up.
     */
    static Page list(String sort, int page) throws IOException {
        Page out = new Page();
        out.next = page;
        Set<Integer> seen = new HashSet<>();
        for (int tries = 0; tries < 4 && out.mods.size() < 8 && !out.complete; tries++) {
            JSONObject json;
            try {
                json = get(feedUrl(sort, out.next));
            } catch (IOException e) {
                if (tries > 0 || sort.equals("default"))
                    throw e;
                sort = "default"; // in case GameBanana stops taking this sort order
                json = get(feedUrl(sort, out.next));
            }
            out.next++;
            for (Mod mod : mods(json))
                if (seen.add(mod.id))
                    out.mods.add(mod);
            out.complete = complete(json);
        }
        return out;
    }

    static Page search(String query, int page) throws IOException {
        IOException failure;
        try {
            JSONObject json = get(API + "/Util/Search/Results?_sModelName=Mod&_sOrder=best_match&_idGameRow=" + GAME_ID
                    + "&_sSearchString=" + URLEncoder.encode(query, "UTF-8")
                    + "&_csvFields=name,description,article,attribs,studio,owner,credits&_nPage=" + page);
            if (json.has("_aRecords")) {
                Page out = new Page();
                out.mods.addAll(mods(json));
                out.next = page + 1;
                out.complete = complete(json);
                return out;
            }
            failure = new IOException("GameBanana's search sent something unexpected");
        } catch (IOException e) {
            failure = e;
        }
        // Fall back to reading the mod list and matching names, authors and categories.
        try {
            return scan(query, page);
        } catch (IOException e) {
            throw failure;
        }
    }

    private static Page scan(String query, int page) throws IOException {
        String[] words = query.toLowerCase(Locale.ROOT).split("\\s+");
        Page out = new Page();
        out.fallback = true;
        out.next = page;
        Set<Integer> seen = new HashSet<>();
        for (int tries = 0; tries < 10 && out.mods.size() < 6 && !out.complete; tries++) {
            JSONObject json = get(feedUrl("new", out.next));
            out.next++;
            out.complete = complete(json);
            for (Mod mod : mods(json)) {
                if (!seen.add(mod.id))
                    continue;
                String haystack = (mod.name + " " + mod.author + " " + mod.category).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String word : words)
                    all &= haystack.contains(word);
                if (all)
                    out.mods.add(mod);
            }
        }
        return out;
    }

    private static String feedUrl(String sort, int page) {
        return API + "/Game/" + GAME_ID + "/Subfeed?_sSort=" + sort + "&_nPage=" + page + "&_csvModelInclusions=Mod";
    }

    private static int records(JSONObject json) {
        JSONArray records = json.optJSONArray("_aRecords");
        return records == null ? 0 : records.length();
    }

    private static boolean complete(JSONObject json) {
        JSONObject meta = json.optJSONObject("_aMetadata");
        return records(json) == 0 || (meta != null && meta.optBoolean("_bIsComplete", false));
    }

    static Details details(int modId) throws IOException {
        JSONObject json = get(API + "/Mod/" + modId + "/ProfilePage");
        Details d = new Details();
        d.mod = mod(json);
        d.mod.id = modId;
        d.text = json.optString("_sText", "");
        d.downloads = json.optInt("_nDownloadCount");
        if (d.mod.category.isEmpty()) {
            JSONObject category = json.optJSONObject("_aCategory");  // ProfilePage has _aCategory, lists _aRootCategory
            if (category != null)
                d.mod.category = category.optString("_sName", "");
        }
        d.image = image(json, true);
        JSONArray files = json.optJSONArray("_aFiles");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null)
                    continue;
                ModFile file = new ModFile();
                file.id = f.optInt("_idRow");
                file.name = f.optString("_sFile", "");
                file.description = f.optString("_sDescription", "");
                file.version = f.optString("_sVersion", "");
                file.md5 = f.optString("_sMd5Checksum", "");
                file.downloadUrl = f.optString("_sDownloadUrl", "https://gamebanana.com/dl/" + file.id);
                file.size = f.optLong("_nFilesize");
                file.added = f.optLong("_tsDateAdded");
                file.downloads = f.optInt("_nDownloadCount");
                d.files.add(file);
            }
        }
        return d;
    }

    private static List<Mod> mods(JSONObject json) {
        List<Mod> out = new ArrayList<>();
        JSONArray records = json.optJSONArray("_aRecords");
        if (records == null)
            return out;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !"Mod".equals(r.optString("_sModelName")))
                continue;
            JSONObject game = r.optJSONObject("_aGame");
            if (game != null && game.optInt("_idRow", GAME_ID) != GAME_ID)
                continue;
            out.add(mod(r));
        }
        return out;
    }

    private static Mod mod(JSONObject r) {
        Mod m = new Mod();
        m.id = r.optInt("_idRow");
        m.name = r.optString("_sName", "");
        m.profileUrl = r.optString("_sProfileUrl", "https://gamebanana.com/mods/" + m.id);
        m.version = r.optString("_sVersion", "");
        m.likes = r.optInt("_nLikeCount");
        m.views = r.optInt("_nViewCount");
        m.dateUpdated = r.optLong("_tsDateUpdated");
        m.updated = r.optLong("_tsDateUpdated", r.optLong("_tsDateModified", r.optLong("_tsDateAdded")));
        JSONObject submitter = r.optJSONObject("_aSubmitter");
        if (submitter != null)
            m.author = submitter.optString("_sName", "");
        JSONObject category = r.optJSONObject("_aRootCategory");
        if (category != null)
            m.category = category.optString("_sName", "");
        m.thumbnail = image(r, false);
        return m;
    }

    private static String image(JSONObject r, boolean large) {
        JSONObject media = r.optJSONObject("_aPreviewMedia");
        JSONArray images = media != null ? media.optJSONArray("_aImages") : null;
        if (images == null || images.length() == 0)
            return "";
        JSONObject first = images.optJSONObject(0);
        if (first == null)
            return "";
        String base = first.optString("_sBaseUrl", "");
        String file = large ? first.optString("_sFile530", first.optString("_sFile", ""))
                : first.optString("_sFile220", first.optString("_sFile100", first.optString("_sFile", "")));
        return base.isEmpty() || file.isEmpty() ? "" : base + "/" + file;
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    // API answers are a few hundred KB at most.
    private static final int MAX_JSON = 8 << 20;

    static JSONObject get(String url) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code != 200)
                throw new IOException("GameBanana answered " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_JSON)
                        throw new IOException("GameBanana sent too much data");
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("GameBanana sent something unexpected");
        } finally {
            connection.disconnect();
        }
    }
}
