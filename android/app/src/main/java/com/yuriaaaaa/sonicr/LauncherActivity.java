package com.yuriaaaaa.sonicr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Finds the player's Sonic R data folder, asks for storage access, copies the
 * repo's DATA/ extras into it, then starts the game. If anything is missing it
 * shows what to do instead of starting.
 */
public class LauncherActivity extends Activity {

    static final String FOLDER_NAME = "SonicR";
    static final String[] REQUIRED = {
        "GENERAL", "ISLAND", "CITY", "RUIN", "FACTORY", "EMERALD", "AI", "BIN", "SOUND"
    };

    private TextView mText;
    private Button mRetry;
    private LinearLayout mMenu;
    private File mData;
    private static boolean sAskedForAccess = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 24, 64));

        mText = new TextView(this);
        mText.setTextColor(Color.WHITE);
        mText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(mText);

        mRetry = new Button(this);
        mRetry.setText("Check again");
        mRetry.setOnClickListener(v -> check());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(mRetry, lp);

        // Shown once the game data is in place.
        mMenu = new LinearLayout(this);
        mMenu.setOrientation(LinearLayout.HORIZONTAL);
        mMenu.setGravity(Gravity.CENTER);
        mMenu.addView(menuButton("Play", 0xFF2E7D32, v -> play()));
        mMenu.addView(menuButton("Mods", 0xFF455A64,
            v -> startActivity(new Intent(this, ModsActivity.class))));
        mMenu.addView(menuButton("Get mods", 0xFF1565C0,
            v -> startActivity(new Intent(this, ModBrowserActivity.class))));
        mMenu.addView(menuButton("Multiplayer", 0xFFEF6C00, v -> showMultiplayer()));
        mMenu.addView(menuButton("Settings", 0xFF6A1B9A, v -> showSettings()));
        mMenu.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(20);
        root.addView(mMenu, mp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    private Button menuButton(String label, int color, android.view.View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void play() {
        if (mData == null) {
            return;
        }
        startActivity(gameIntent(SonicRActivity.class));
    }

    /** The game's launch intent with every setting, aimed at `target`. */
    private Intent gameIntent(Class<?> target) {
        Intent i = new Intent(this, target);
        i.putExtra(SonicRActivity.EXTRA_DATA_DIR, mData.getAbsolutePath());
        android.content.SharedPreferences prefs = prefs(this);
        i.putExtra(SonicRActivity.EXTRA_STRETCH, prefs.getBoolean(KEY_STRETCH, false));
        i.putExtra(SonicRActivity.EXTRA_RESOLUTION, prefs.getInt(KEY_RESOLUTION, 0));
        i.putExtra(SonicRActivity.EXTRA_MSAA, prefs.getInt(KEY_MSAA, 0));
        i.putExtra(SonicRActivity.EXTRA_SMOOTH, prefs.getBoolean(KEY_SMOOTH, false));
        i.putExtra(SonicRActivity.EXTRA_VSYNC, prefs.getBoolean(KEY_VSYNC, true));
        i.putExtra(SonicRActivity.EXTRA_FPS, prefs.getBoolean(KEY_FPS, false));
        i.putExtra(SonicRActivity.EXTRA_DEBUG_LOG, prefs.getBoolean(KEY_DEBUG_LOG, false));
        i.putExtra(SonicRActivity.EXTRA_NET_NAME, prefs.getString(KEY_NET_NAME, ""));
        i.putExtra(SonicRActivity.EXTRA_NET_HOST, prefs.getString(KEY_NET_HOST, ""));
        return i;
    }

    static final String PREFS = "settings";
    static final String KEY_STRETCH = "stretch";
    static final String KEY_RESOLUTION = "resolution"; // render height, 0 = native
    static final String KEY_MSAA = "msaa";
    static final String KEY_SMOOTH = "smooth";
    static final String KEY_FPS = "fps";
    static final String KEY_VSYNC = "vsync";
    static final String KEY_DEBUG_LOG = "debugLog";
    static final String KEY_TOUCH = "touchControls";
    static final String KEY_NET_NAME = "netName";
    static final String KEY_NET_HOST = "netHost";   // blank = find a host on the Wi-Fi
    static final String KEY_NET_HUB = "netHub";     // sonic-r-netplay hub for online rooms
    static final String KEY_TOUCH_OPACITY = "touchOpacity"; // percent, 20-100
    static final String KEY_TOUCH_PROFILE = "touchProfile"; // active layout, 1-3
    static final int TOUCH_PROFILES = 3;

    static String layoutKey(int profile) {
        return "touchLayout" + profile;
    }

    static int activeProfile(android.content.Context ctx) {
        int p = prefs(ctx).getInt(KEY_TOUCH_PROFILE, 1);
        return p < 1 || p > TOUCH_PROFILES ? 1 : p;
    }

    /** Settings shared with the game process, which can change some of them. */
    @SuppressWarnings("deprecation")
    static android.content.SharedPreferences prefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_MULTI_PROCESS);
    }

    private void showSettings() {
        android.content.SharedPreferences prefs = prefs(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        android.widget.RadioGroup screen = choices(box, "Screen",
            new String[] { "4:3 (original, black bars)", "Stretch to fill (16:9)" },
            prefs.getBoolean(KEY_STRETCH, false) ? 1 : 0);
        int[] heights = { 0, 1080, 720, 480 };
        android.widget.RadioGroup res = choices(box, "Resolution",
            new String[] { "Native (sharpest)", "1080p", "720p (faster)", "480p (original look)" },
            indexOf(heights, prefs.getInt(KEY_RESOLUTION, 0)));
        int[] samples = { 0, 2, 4 };
        android.widget.RadioGroup aa = choices(box, "Anti-aliasing (smooths jagged edges)",
            new String[] { "Off", "2x", "4x" },
            indexOf(samples, prefs.getInt(KEY_MSAA, 0)));
        android.widget.CheckBox smooth = new android.widget.CheckBox(this);
        smooth.setText("Smooth textures (bilinear filtering)");
        smooth.setChecked(prefs.getBoolean(KEY_SMOOTH, false));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        box.addView(smooth, sp);
        android.widget.CheckBox touch = new android.widget.CheckBox(this);
        touch.setText("Touch controls");
        touch.setChecked(prefs.getBoolean(KEY_TOUCH, true));
        box.addView(touch);
        TextView opacityLabel = new TextView(this);
        box.addView(opacityLabel);
        android.widget.SeekBar opacity = new android.widget.SeekBar(this);
        opacity.setMax(80);   // 20% to 100%
        opacity.setProgress(prefs.getInt(KEY_TOUCH_OPACITY, 100) - 20);
        opacityLabel.setText("Touch opacity: " + (opacity.getProgress() + 20) + "%");
        opacity.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int v, boolean user) {
                opacityLabel.setText("Touch opacity: " + (v + 20) + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) { }
        });
        box.addView(opacity);
        TextView profLabel = new TextView(this);
        profLabel.setText("Touch layout profile");
        profLabel.setPadding(0, dp(8), 0, 0);
        box.addView(profLabel);
        android.widget.RadioGroup profile = new android.widget.RadioGroup(this);
        profile.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        for (int p = 1; p <= TOUCH_PROFILES; p++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(p);
            rb.setText("Profile " + p);
            profile.addView(rb);
        }
        profile.check(activeProfile(this));
        box.addView(profile);
        Button edit = new Button(this);
        edit.setText("Edit touch controls");
        edit.setAllCaps(false);
        final android.app.AlertDialog[] settingsDialog = new android.app.AlertDialog[1];
        edit.setOnClickListener(v -> {
            // Save what's set here first, so the editor's profile choice isn't overwritten.
            if (settingsDialog[0] != null)
                settingsDialog[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            startActivity(new Intent(this, TouchEditorActivity.class));
        });
        box.addView(edit);
        TextView menuHint = new TextView(this);
        menuHint.setText("In game, tap with three fingers anywhere away from the touch buttons "
            + "to open the game menu.");
        box.addView(menuHint);
        android.widget.CheckBox vsync = new android.widget.CheckBox(this);
        vsync.setText("VSync (no tearing; off can feel more responsive)");
        vsync.setChecked(prefs.getBoolean(KEY_VSYNC, true));
        box.addView(vsync);
        android.widget.CheckBox fps = new android.widget.CheckBox(this);
        fps.setText("Show FPS counter");
        fps.setChecked(prefs.getBoolean(KEY_FPS, false));
        box.addView(fps);
        android.widget.CheckBox log = new android.widget.CheckBox(this);
        log.setText("Debug logging (saves logs to the logs folder next to your game files)");
        log.setChecked(prefs.getBoolean(KEY_DEBUG_LOG, false));
        box.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        settingsDialog[0] = new android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Save", (d, w) -> prefs.edit()
                .putBoolean(KEY_STRETCH, checkedIndex(screen) == 1)
                .putInt(KEY_RESOLUTION, heights[checkedIndex(res)])
                .putInt(KEY_MSAA, samples[checkedIndex(aa)])
                .putBoolean(KEY_SMOOTH, smooth.isChecked())
                .putBoolean(KEY_VSYNC, vsync.isChecked())
                .putBoolean(KEY_FPS, fps.isChecked())
                .putBoolean(KEY_DEBUG_LOG, log.isChecked())
                .putBoolean(KEY_TOUCH, touch.isChecked())
                .putInt(KEY_TOUCH_OPACITY, opacity.getProgress() + 20)
                .putInt(KEY_TOUCH_PROFILE, Math.max(1, profile.getCheckedRadioButtonId()))
                .apply())
            .setNegativeButton("Cancel", null)
            .show();
    }

    /* Network play is the engine's own LAN/online mode (UDP 7847). Phones on
     * the same Wi-Fi, or one phone's hotspot, find each other automatically;
     * a host IP is only needed when broadcasts are blocked. */
    private void showMultiplayer() {
        android.content.SharedPreferences prefs = prefs(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView how = new TextView(this);
        how.setText("Play 2 players (or more) over Wi-Fi with another phone or a PC running "
            + "the same version and game data.\n\n"
            + "1. Connect both phones to the same Wi-Fi, or turn on one phone's hotspot and "
            + "join it from the other.\n"
            + "2. Start Sonic R on both and open the game's network/multiplayer menu.\n"
            + "3. One player hosts; the other joins and finds the host automatically.\n\n"
            + "Your address on this network: " + localAddresses());
        box.addView(how);


        TextView nameLabel = new TextView(this);
        nameLabel.setText("Your player name");
        nameLabel.setPadding(0, dp(12), 0, 0);
        box.addView(nameLabel);
        android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setText(prefs.getString(KEY_NET_NAME, ""));
        name.setHint(Build.MODEL);
        box.addView(name);

        // Online rooms through a sonic-r-netplay hub: host a room, or pick one to join.
        final android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        TextView onlineTitle = new TextView(this);
        onlineTitle.setText("Online rooms");
        onlineTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        onlineTitle.setPadding(0, dp(14), 0, dp(4));
        box.addView(onlineTitle);
        TextView online = new TextView(this);
        online.setText("Race over the internet through a Sonic R netplay hub. Everyone enters the "
            + "same hub address. One player hosts a room; the others find it and join. In the "
            + "lobby the host presses Start (or A) to begin once everyone is in.");
        box.addView(online);
        android.widget.EditText hub = new android.widget.EditText(this);
        hub.setSingleLine(true);
        hub.setHint("Hub address, e.g. 203.0.113.5 or wss://hub.example.com");
        hub.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        hub.setText(prefs.getString(KEY_NET_HUB, ""));
        box.addView(hub);
        LinearLayout onlineRow = new LinearLayout(this);
        onlineRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button hostRoom = new android.widget.Button(this);
        hostRoom.setText("Host a room");
        hostRoom.setAllCaps(false);
        android.widget.Button findRooms = new android.widget.Button(this);
        findRooms.setText("Find rooms");
        findRooms.setAllCaps(false);
        onlineRow.addView(hostRoom);
        onlineRow.addView(findRooms);
        box.addView(onlineRow);
        hostRoom.setOnClickListener(v -> {
            String h = saveOnline(prefs, name, hub);
            if (h == null) return;
            if (dialog[0] != null) dialog[0].dismiss();
            hostRoom(h, playerName(prefs));
        });
        findRooms.setOnClickListener(v -> {
            String h = saveOnline(prefs, name, hub);
            if (h == null) return;
            if (dialog[0] != null) dialog[0].dismiss();
            findRooms(h);
        });

        TextView hostLabel = new TextView(this);
        hostLabel.setText("Join a host by IP (leave blank to find one automatically)");
        hostLabel.setPadding(0, dp(12), 0, 0);
        box.addView(hostLabel);
        android.widget.EditText host = new android.widget.EditText(this);
        host.setSingleLine(true);
        host.setHint("e.g. 192.168.1.20");
        host.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        host.setText(prefs.getString(KEY_NET_HOST, ""));
        box.addView(host);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        dialog[0] = new android.app.AlertDialog.Builder(this)
            .setTitle("Multiplayer")
            .setView(scroll)
            .setPositiveButton("Save", (d, w) -> prefs.edit()
                .putString(KEY_NET_NAME, name.getText().toString().trim())
                .putString(KEY_NET_HOST, host.getText().toString().trim())
                .apply())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String playerName(android.content.SharedPreferences prefs) {
        String n = prefs.getString(KEY_NET_NAME, "").trim();
        return n.isEmpty() ? Build.MODEL : n;
    }

    /** Saves the name and hub fields; returns the hub, or null (with a hint) when it's blank. */
    private String saveOnline(android.content.SharedPreferences prefs, android.widget.EditText name,
                              android.widget.EditText hub) {
        String h = hub.getText().toString().trim();
        prefs.edit().putString(KEY_NET_NAME, name.getText().toString().trim())
            .putString(KEY_NET_HUB, h).apply();
        if (h.isEmpty()) {
            hub.setError("Enter the hub address first");
            return null;
        }
        if (mData == null) {
            android.widget.Toast.makeText(this, "Add the game data first", android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
        if (!Netplay.available()) {
            android.widget.Toast.makeText(this, "Online rooms aren't included in this build",
                android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
        return h;
    }

    private void hostRoom(String hub, String player) {
        Intent i = gameIntent(NetplayActivity.class);
        i.putExtra(NetplayActivity.EXTRA_MODE, "host");
        i.putExtra(NetplayActivity.EXTRA_HUB, hub);
        i.putExtra(NetplayActivity.EXTRA_ROOM_NAME, player + "'s room");
        startActivity(i);
    }

    private void joinRoom(String hub, String id, String roomName) {
        Intent i = gameIntent(NetplayActivity.class);
        i.putExtra(NetplayActivity.EXTRA_MODE, "join");
        i.putExtra(NetplayActivity.EXTRA_HUB, hub);
        i.putExtra(NetplayActivity.EXTRA_ROOM, id);
        i.putExtra(NetplayActivity.EXTRA_ROOM_NAME, roomName);
        startActivity(i);
    }

    /* Lists the hub's rooms; tapping an open one joins it. */
    private void findRooms(String hub) {
        android.app.AlertDialog wait = new android.app.AlertDialog.Builder(this)
            .setTitle("Finding rooms…")
            .setMessage("Asking " + hub)
            .setCancelable(false)
            .show();
        new Thread(() -> {
            String result = Netplay.nativeListRooms(hub);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                wait.dismiss();
                showRooms(hub, result);
            });
        }, "netplay-list").start();
    }

    private void showRooms(String hub, String result) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String[]> open = new java.util.ArrayList<>();   // {id, name} per label, null if full
        String error = null;
        try {
            org.json.JSONObject j = new org.json.JSONObject(result);
            if (j.has("error")) {
                error = j.getString("error");
            } else {
                org.json.JSONArray rooms = j.getJSONArray("rooms");
                for (int k = 0; k < rooms.length(); k++) {
                    org.json.JSONObject r = rooms.getJSONObject(k);
                    boolean joinable = r.optBoolean("joinable");
                    labels.add(r.optString("name") + "  (" + r.optInt("players") + "/" + r.optInt("max")
                        + (joinable ? ")" : ", racing or full)"));
                    open.add(joinable ? new String[] { r.optString("id"), r.optString("name") } : null);
                }
            }
        } catch (org.json.JSONException e) {
            error = "The hub sent something unexpected";
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
            .setNegativeButton("Close", null)
            .setNeutralButton("Refresh", (d, w) -> findRooms(hub));
        if (error != null) {
            b.setTitle("Couldn't reach the hub").setMessage(error);
        } else if (labels.isEmpty()) {
            b.setTitle("No rooms yet").setMessage("Nobody is hosting on this hub right now. Host a room, "
                + "or refresh once your friend has.");
        } else {
            b.setTitle("Rooms").setItems(labels.toArray(new String[0]), (d, which) -> {
                String[] room = open.get(which);
                if (room == null) {
                    android.widget.Toast.makeText(this, "That room is racing or full", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                joinRoom(hub, room[0], room[1]);
            });
        }
        b.show();
    }

    /* VPN (e.g. Tailscale) addresses, which aren't on the Wi-Fi. */
    private static boolean isTailscale(java.net.InetAddress a) {
        byte[] b = a.getAddress();
        // 100.64.0.0/10, the carrier-grade NAT range Tailscale uses.
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    private static String localAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String n = ni.getName();
                        // Mobile data addresses can't be reached from the Wi-Fi.
                        if (n.startsWith("rmnet") || n.startsWith("ccmni")) continue;
                        // VPN addresses aren't on the Wi-Fi either.
                        if (n.startsWith("tun") || isTailscale(a)) continue;
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(a.getHostAddress());
                        if (n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap"))
                            sb.append(" (hotspot)");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() > 0 ? sb.toString() : "not on Wi-Fi";
    }

    private android.widget.RadioGroup choices(LinearLayout box, String title, String[] labels, int checked) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(12), 0, dp(2));
        box.addView(t);
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton b = new android.widget.RadioButton(this);
            b.setId(i + 1);
            b.setText(labels[i]);
            group.addView(b);
        }
        group.check(checked + 1);
        box.addView(group);
        return group;
    }

    private static int checkedIndex(android.widget.RadioGroup group) {
        return Math.max(0, group.getCheckedRadioButtonId() - 1);
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++)
            if (values[i] == v)
                return i;
        return 0;
    }

    private void showMenu(boolean ready) {
        mMenu.setVisibility(ready ? android.view.View.VISIBLE : android.view.View.GONE);
        mRetry.setVisibility(ready ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
        }
    }

    private File sharedFolder() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER_NAME);
    }

    private static boolean looksLikeData(File dir) {
        return new File(dir, "GENERAL/SONICR.BIT").isFile();
    }

    private void check() {
        showMenu(false);
        File shared = sharedFolder();
        File appDir = getExternalFilesDir(null);

        File data = null;
        if (hasStorageAccess()) {
            shared.mkdirs();
            if (looksLikeData(shared)) {
                data = shared;
            }
        }
        if (data == null && appDir != null && looksLikeData(appDir)) {
            data = appDir;
        }

        if (data == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Sonic R needs the game files from your own PC copy.\n\n");
            if (!hasStorageAccess()) {
                sb.append("First, allow \"All files access\" for Sonic R so it can read ")
                  .append(shared.getAbsolutePath()).append(".\n\n");
                if (!sAskedForAccess) {
                    sAskedForAccess = true;
                    requestStorageAccess();
                }
            }
            sb.append("Copy these folders from the disc (or an installed copy) into\n")
              .append(shared.getAbsolutePath()).append("\n\n");
            for (String r : REQUIRED) {
                sb.append("  ").append(r).append("\n");
            }
            sb.append("\nMusic: put tracks in a MUSIC folder there, named track2 to track21 ")
              .append("(.ogg, .mp3, .flac, .wav, .adx or .son). Rip them from the disc's CD audio.\n");
            if (appDir != null) {
                sb.append("\nWithout all files access, the folder ")
                  .append(appDir.getAbsolutePath()).append(" also works.\n");
            }
            mText.setText(sb.toString());
            return;
        }

        StringBuilder missing = new StringBuilder();
        for (String r : REQUIRED) {
            if (!new File(data, r).isDirectory()) {
                missing.append("  ").append(r).append("\n");
            }
        }
        if (missing.length() > 0) {
            mText.setText("Found " + data.getAbsolutePath() + " but these folders are missing:\n\n"
                + missing + "\nCopy them from the disc, then tap Check again.");
            return;
        }

        try {
            copyAssetTree(getAssets(), "DATA", data);
        } catch (IOException e) {
            mText.setText("Could not copy the bundled extras into " + data.getAbsolutePath()
                + ": " + e.getMessage());
            return;
        }

        mData = data;
        StringBuilder ready = new StringBuilder("Sonic R is ready.\n\nGame data: " + data.getAbsolutePath());
        ModManager.init(this);
        ModManager mods = new ModManager();
        mods.load();
        if (!mods.mods.isEmpty())
            ready.append("\nMods: ").append(mods.enabledCount()).append(" of ").append(mods.mods.size()).append(" on");
        if (prefs(this).getBoolean(KEY_DEBUG_LOG, false))
            ready.append("\nDebug logging is on: ").append(new File(data, "logs").getAbsolutePath());
        if (!new File(data, "MUSIC").isDirectory())
            ready.append("\n\nNo MUSIC folder yet, so races will be silent apart from sound effects.");
        mText.setText(ready.toString());
        showMenu(true);
    }

    /** Copies assets/<src>/... into dst/..., skipping files that already exist. */
    private static void copyAssetTree(AssetManager am, String src, File dst) throws IOException {
        String[] children = am.list(src);
        if (children == null || children.length == 0) {
            if (dst.exists()) {
                return;
            }
            File parent = dst.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (InputStream in = am.open(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return;
        }
        dst.mkdirs();
        for (String c : children) {
            copyAssetTree(am, src + "/" + c, new File(dst, c));
        }
    }
}
