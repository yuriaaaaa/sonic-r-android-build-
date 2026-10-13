package com.yuriaaaaa.sonicr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.ProgressBar;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Turn mods on or off, set their order, and add mods from archives. */
public class ModsActivity extends Activity {
    private static final int PICK_ARCHIVE = 1;
    private final ModManager manager = new ModManager();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout modList;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModManager.init(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        column.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Mods", 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button getMods = button("Get mods", 0xFF1565C0);
        getMods.setOnClickListener(v -> startActivity(new Intent(this, ModBrowserActivity.class)));
        header.addView(getMods);
        Button add = button("Add file", 0xFF6A1B9A);
        add.setOnClickListener(v -> pickArchive());
        header.addView(add);
        Button refresh = button("Refresh", 0xFF455A64);
        refresh.setOnClickListener(v -> reload());
        header.addView(refresh);
        Button done = button("Done", 0xFF2E7D32);
        done.setOnClickListener(v -> finish());
        header.addView(done);
        column.addView(header);

        TextView help = text("Mods use the Windows Sonic R Mod Manager's format, in SonicR/mods. Tap Get mods "
                + "to download from GameBanana (its 1-click buttons work too), Add file to install a .zip, .7z or "
                + ".rar you already have, or copy a mod folder (the one with mod.ini inside) there and tap Refresh. "
                + "Mods lower in the list win when two change the same file. Changes apply the next time "
                + "the game starts.", 14, 0xFFB0BEC5);
        help.setPadding(0, dp(10), 0, dp(6));
        column.addView(help);

        summary = text("", 14, 0xFFE0E0E0);
        summary.setPadding(0, 0, 0, dp(10));
        column.addView(summary);

        modList = new LinearLayout(this);
        modList.setOrientation(LinearLayout.VERTICAL);
        column.addView(modList);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        scroll.addView(column);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also picks up mods installed or switched on from Get mods.
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            manager.save();
        } catch (IOException ignored) {
        }
    }

    private void reload() {
        manager.load();
        render();
    }

    private void render() {
        modList.removeAllViews();
        if (manager.mods.isEmpty()) {
            modList.addView(text("No mods in SonicR/mods yet.", 15, 0xFFCFD8DC));
        }
        for (int i = 0; i < manager.mods.size(); i++)
            modList.addView(modRow(i));

        updateSummary();
    }

    private View modRow(int index) {
        ModManager.Mod mod = manager.mods.get(index);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1A222C);
        background.setCornerRadius(dp(8));
        row.setBackground(background);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        CheckBox box = new CheckBox(this);
        box.setChecked(mod.enabled);
        box.setOnCheckedChangeListener((view, checked) -> {
            mod.enabled = checked;
            updateSummary();
        });
        row.addView(box);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(mod.title, 16, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        StringBuilder details = new StringBuilder();
        if (!mod.author.isEmpty())
            details.append(mod.author);
        if (!mod.version.isEmpty())
            details.append(details.length() > 0 ? " · " : "").append("v").append(mod.version);
        if (details.length() > 0)
            info.addView(text(details.toString(), 13, 0xFF90A4AE));
        if (!mod.description.isEmpty()) {
            TextView description = text(mod.description, 13, 0xFFB0BEC5);
            description.setMaxLines(3);
            info.addView(description);
        }
        for (String note : mod.notes)
            info.addView(text(note, 13, 0xFFFFB74D));
        info.setOnClickListener(v -> box.toggle());
        row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button up = smallButton("\u25B2");
        up.setEnabled(index > 0);
        up.setAlpha(index > 0 ? 1f : 0.3f);
        up.setOnClickListener(v -> move(index, index - 1));
        row.addView(up);
        Button down = smallButton("\u25BC");
        boolean canMoveDown = index < manager.mods.size() - 1;
        down.setEnabled(canMoveDown);
        down.setAlpha(canMoveDown ? 1f : 0.3f);
        down.setOnClickListener(v -> move(index, index + 1));
        row.addView(down);
        return row;
    }

    private void move(int from, int to) {
        Collections.swap(manager.mods, from, to);
        render();
    }

    private void updateSummary() {
        summary.setText(manager.mods.size() + (manager.mods.size() == 1 ? " mod" : " mods") + " found · "
                + manager.enabledCount() + " on");
    }

    private void pickArchive() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_ARCHIVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ARCHIVE && resultCode == RESULT_OK && data != null && data.getData() != null)
            importArchive(data.getData());
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0))
                return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "mod.zip";
    }

    private void importArchive(Uri uri) {
        String name = displayName(uri);
        ProgressBar bar = new ProgressBar(this);
        bar.setPadding(dp(24), dp(16), dp(24), dp(16));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Installing " + name)
                .setView(bar)
                .setCancelable(false)
                .show();
        File cache = new File(getCacheDir(), "import");
        worker.execute(() -> {
            String error = null;
            ModInstaller.Result result = null;
            File copy = new File(cache, ModInstaller.safeName(name));
            try {
                cache.mkdirs();
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(copy)) {
                    if (in == null)
                        throw new IOException("Can't open " + name);
                    byte[] buffer = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buffer)) > 0)
                        out.write(buffer, 0, n);
                }
                result = ModInstaller.installLocal(copy, name, (message, done, total) -> true);
                ModManager.enable(result.folders);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                copy.delete();
            }
            String failure = error;
            ModInstaller.Result done = result;
            runOnUiThread(() -> {
                if (isDestroyed())
                    return;
                dialog.dismiss();
                reload();
                if (done == null) {
                    new AlertDialog.Builder(this).setTitle("Install failed").setMessage(failure)
                            .setPositiveButton("OK", null).show();
                } else {
                    StringBuilder msg = new StringBuilder();
                    for (File f : done.folders)
                        msg.append("SonicR/mods/").append(f.getName()).append('\n');
                    msg.append(done.folders.size() == 1 ? "\nIt's switched on." : "\nThey're switched on.");
                    new AlertDialog.Builder(this).setTitle("Installed").setMessage(msg)
                            .setPositiveButton("OK", null).show();
                }
            });
        });
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, int color) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        view.setBackground(background);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(10));
        view.setLayoutParams(params);
        return view;
    }

    private Button smallButton(String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF37474F);
        background.setCornerRadius(dp(6));
        view.setBackground(background);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(6));
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
