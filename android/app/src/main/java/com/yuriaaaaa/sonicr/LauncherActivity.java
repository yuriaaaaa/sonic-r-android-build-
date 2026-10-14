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
        Intent i = new Intent(this, SonicRActivity.class);
        i.putExtra(SonicRActivity.EXTRA_DATA_DIR, mData.getAbsolutePath());
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        i.putExtra(SonicRActivity.EXTRA_STRETCH, prefs.getBoolean(KEY_STRETCH, false));
        i.putExtra(SonicRActivity.EXTRA_RESOLUTION, prefs.getInt(KEY_RESOLUTION, 0));
        i.putExtra(SonicRActivity.EXTRA_MSAA, prefs.getInt(KEY_MSAA, 0));
        i.putExtra(SonicRActivity.EXTRA_SMOOTH, prefs.getBoolean(KEY_SMOOTH, false));
        i.putExtra(SonicRActivity.EXTRA_VSYNC, prefs.getBoolean(KEY_VSYNC, true));
        i.putExtra(SonicRActivity.EXTRA_FPS, prefs.getBoolean(KEY_FPS, false));
        i.putExtra(SonicRActivity.EXTRA_DEBUG_LOG, prefs.getBoolean(KEY_DEBUG_LOG, false));
        startActivity(i);
    }

    static final String PREFS = "settings";
    static final String KEY_STRETCH = "stretch";
    static final String KEY_RESOLUTION = "resolution"; // render height, 0 = native
    static final String KEY_MSAA = "msaa";
    static final String KEY_SMOOTH = "smooth";
    static final String KEY_FPS = "fps";
    static final String KEY_VSYNC = "vsync";
    static final String KEY_DEBUG_LOG = "debugLog";

    private void showSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
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
        new android.app.AlertDialog.Builder(this)
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
                .apply())
            .setNegativeButton("Cancel", null)
            .show();
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
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DEBUG_LOG, false))
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
