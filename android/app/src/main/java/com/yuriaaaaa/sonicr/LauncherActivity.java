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
        startActivity(i);
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
