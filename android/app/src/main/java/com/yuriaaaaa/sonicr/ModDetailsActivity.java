package com.yuriaaaaa.sonicr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One GameBanana mod: its description and files, with Install / Remove. */
public class ModDetailsActivity extends Activity {
    static final String EXTRA_MOD_ID = "modId";
    /** Set by a 1-click link: offer to install this file as soon as the page loads. */
    static final String EXTRA_FILE_ID = "fileId";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int modId;
    private int pendingFileId;
    private GameBanana.Details details;

    private TextView title;
    private LinearLayout body;
    private Button retry;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModManager.init(this);
        modId = getIntent().getIntExtra(EXTRA_MOD_ID, 0);
        pendingFileId = savedInstanceState == null ? getIntent().getIntExtra(EXTRA_FILE_ID, 0) : 0;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = text("Loading…", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button back = button("Back", 0xFF455A64);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        column.addView(header);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        column.addView(body);

        retry = button("Try again", 0xFF37474F);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> load());
        column.addView(retry);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        scroll.addView(column);
        setContentView(scroll);

        if (modId == 0) {
            title.setText("Mod not found");
            return;
        }
        load();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!busy)
            super.onBackPressed();
    }

    private void load() {
        retry.setVisibility(View.GONE);
        body.removeAllViews();
        TextView loading = text("Loading the mod page from GameBanana…", 14, 0xFFB0BEC5);
        loading.setPadding(0, dp(12), 0, 0);
        body.addView(loading);
        worker.execute(() -> {
            GameBanana.Details result = null;
            String error = null;
            try {
                result = GameBanana.details(modId);
            } catch (Exception e) {
                error = e.getMessage();
            }
            GameBanana.Details d = result;
            String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                if (d == null) {
                    title.setText("Couldn't load the mod");
                    body.removeAllViews();
                    body.addView(text("GameBanana didn't answer: " + failure + ". Check the connection and try again.",
                            14, 0xFFFFB74D));
                    retry.setVisibility(View.VISIBLE);
                    return;
                }
                details = d;
                render();
                offerPendingFile();
            });
        });
    }

    private void render() {
        GameBanana.Mod mod = details.mod;
        title.setText(mod.name.isEmpty() ? "Mod " + mod.id : mod.name);
        body.removeAllViews();

        if (!details.image.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_START);
            image.setMaxHeight(dp(240));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(10);
            body.addView(image, params);
            ImageLoader.load(image, details.image);
        }

        StringBuilder line = new StringBuilder();
        if (!mod.author.isEmpty())
            line.append("by ").append(mod.author);
        if (!mod.category.isEmpty())
            line.append(line.length() > 0 ? " · " : "").append(mod.category);
        if (!mod.version.isEmpty())
            line.append(line.length() > 0 ? " · " : "").append("version ").append(mod.version);
        TextView by = text(line.toString(), 14, 0xFF90A4AE);
        by.setPadding(0, dp(10), 0, 0);
        body.addView(by);
        String stats = "♥ " + mod.likes + " · " + details.downloads + " downloads · " + mod.views + " views"
                + (mod.updated > 0 ? " · updated " + date(mod.updated) : "");
        body.addView(text(stats, 13, 0xFF78909C));

        for (String warning : warnings()) {
            TextView w = text("⚠ " + warning, 14, 0xFFFFB74D);
            w.setPadding(0, dp(8), 0, 0);
            body.addView(w);
        }

        List<ModInstaller.Installed> mine = installedForMod();
        if (!mine.isEmpty()) {
            long newestInstalled = 0;
            StringBuilder state = new StringBuilder("Installed: ");
            for (int i = 0; i < mine.size(); i++) {
                ModInstaller.Installed m = mine.get(i);
                newestInstalled = Math.max(newestInstalled, m.fileAdded);
                state.append(i > 0 ? ", " : "").append(m.fileName).append(" → ").append(m.where());
            }
            GameBanana.ModFile newer = null;
            for (GameBanana.ModFile file : details.files)
                if (file.added > newestInstalled + 60 && (newer == null || file.added > newer.added))
                    newer = file;
            boolean installedListed = false;
            for (ModInstaller.Installed m : mine)
                for (GameBanana.ModFile file : details.files)
                    installedListed |= file.id == m.fileId;
            if (newer != null && installedListed)
                state.append("\nA newer file was uploaded on ").append(date(newer.added)).append(": ").append(newer.name);
            else if (newer != null || !installedListed)
                state.append("\nThe file you installed has been replaced on GameBanana; install the new one to update.");
            TextView stateView = text(state.toString(), 14, newer != null || !installedListed ? 0xFFFFB74D : 0xFF81C784);
            stateView.setPadding(0, dp(8), 0, 0);
            body.addView(stateView);
        }

        // Files
        TextView filesTitle = text("Files", 20, Color.WHITE);
        filesTitle.setTypeface(Typeface.DEFAULT_BOLD);
        filesTitle.setPadding(0, dp(16), 0, dp(6));
        body.addView(filesTitle);
        if (details.files.isEmpty())
            body.addView(text("This mod has no downloadable files on GameBanana (it may link elsewhere).",
                    14, 0xFFB0BEC5));
        for (GameBanana.ModFile file : details.files)
            body.addView(fileRow(file, mine));

        // Description
        if (!details.text.trim().isEmpty()) {
            TextView aboutTitle = text("About", 20, Color.WHITE);
            aboutTitle.setTypeface(Typeface.DEFAULT_BOLD);
            aboutTitle.setPadding(0, dp(16), 0, dp(6));
            body.addView(aboutTitle);
            TextView about = text("", 14, 0xFFCFD8DC);
            String html = details.text.replaceAll("(?is)<img[^>]*>", "").replaceAll("(?is)<iframe.*?</iframe>", "");
            about.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
            about.setLinkTextColor(0xFF64B5F6);
            about.setMovementMethod(LinkMovementMethod.getInstance());
            about.setMaxLines(14);
            body.addView(about);
            Button more = button("Show more", 0xFF37474F);
            more.setOnClickListener(v -> {
                boolean collapsed = about.getMaxLines() != Integer.MAX_VALUE;
                about.setMaxLines(collapsed ? Integer.MAX_VALUE : 14);
                more.setText(collapsed ? "Show less" : "Show more");
            });
            LinearLayout.LayoutParams moreParams = (LinearLayout.LayoutParams) more.getLayoutParams();
            moreParams.setMarginStart(0);
            moreParams.topMargin = dp(6);
            body.addView(more, moreParams);
            about.post(() -> {
                if (about.getLineCount() <= 14)
                    more.setVisibility(View.GONE);
            });
        }

        Button open = button("Open on GameBanana", 0xFF37474F);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mod.profileUrl)));
            } catch (ActivityNotFoundException ignored) {
            }
        });
        LinearLayout.LayoutParams openParams = (LinearLayout.LayoutParams) open.getLayoutParams();
        openParams.setMarginStart(0);
        openParams.topMargin = dp(16);
        body.addView(open, openParams);
    }

    private List<String> warnings() {
        StringBuilder all = new StringBuilder(details.mod.name).append(' ').append(details.text);
        for (GameBanana.ModFile file : details.files)
            all.append(' ').append(file.name).append(' ').append(file.description);
        String text = all.toString().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (text.contains(".dll") || text.contains(" dll"))
            out.add("Mentions a DLL. Windows DLL mods can't run on Android; only their replacement files apply.");
        if (text.contains("codes.lst") || text.contains("cheat code"))
            out.add("Mentions cheat codes. Codes made for the Windows exe are ignored here.");
        if (text.contains("widescreen") || text.contains("border"))
            out.add("Mentions widescreen or borders, which the Mod Loader's own settings did; this engine has its own.");
        return out;
    }

    private List<ModInstaller.Installed> installedForMod() {
        List<ModInstaller.Installed> out = new ArrayList<>();
        for (ModInstaller.Installed i : ModInstaller.installedAll())
            if (i.modId == details.mod.id)
                out.add(i);
        return out;
    }

    private View fileRow(GameBanana.ModFile file, List<ModInstaller.Installed> installed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1A222C);
        background.setCornerRadius(dp(8));
        row.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(file.name, 15, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        StringBuilder line = new StringBuilder(ModBrowserActivity.size(file.size));
        if (!file.version.isEmpty())
            line.append(" · version ").append(file.version);
        if (file.added > 0)
            line.append(" · ").append(date(file.added));
        line.append(" · ").append(file.downloads).append(" downloads");
        info.addView(text(line.toString(), 13, 0xFF90A4AE));
        top.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ModInstaller.Installed same = null;
        for (ModInstaller.Installed i : installed)
            if (i.fileId == file.id)
                same = i;
        ModInstaller.Installed sameFile = same;
        boolean supported = ModInstaller.isArchive(file.name) || file.name.toLowerCase(Locale.ROOT).endsWith(".mlua")
                || file.name.toLowerCase(Locale.ROOT).endsWith(".arc");
        if (sameFile != null) {
            Button remove = button("Remove", 0xFF6D4C41);
            remove.setOnClickListener(v -> confirmRemove(sameFile));
            top.addView(remove);
        }
        Button install = button(sameFile != null ? "Reinstall" : (installed.isEmpty() ? "Install" : "Install…"),
                supported ? 0xFF2E7D32 : 0xFF455A64);
        install.setOnClickListener(v -> chooseInstall(file));
        top.addView(install);
        row.addView(top);

        if (sameFile != null) {
            TextView where = text("Installed → " + sameFile.where(), 13, 0xFF81C784);
            where.setPadding(0, dp(4), 0, 0);
            row.addView(where);
        }
        if (!supported) {
            TextView note = text("Not an archive the launcher knows; it will be copied into the mod folder as it is.",
                    13, 0xFFFFB74D);
            note.setPadding(0, dp(4), 0, 0);
            row.addView(note);
        }
        if (!file.description.trim().isEmpty()) {
            TextView description = text(file.description.trim(), 13, 0xFFB0BEC5);
            description.setPadding(0, dp(4), 0, 0);
            row.addView(description);
        }
        return row;
    }

    private void offerPendingFile() {
        if (pendingFileId == 0)
            return;
        int fileId = pendingFileId;
        pendingFileId = 0;
        for (GameBanana.ModFile file : details.files) {
            if (file.id == fileId) {
                new AlertDialog.Builder(this)
                        .setTitle("Install from GameBanana?")
                        .setMessage(details.mod.name + "\n" + file.name + " (" + ModBrowserActivity.size(file.size)
                                + ")\n\nIt's downloaded and unpacked straight into SonicR/mods.")
                        .setPositiveButton("Install", (dialog, which) -> chooseInstall(file))
                        .setNegativeButton("Not now", null)
                        .show();
                return;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("File not found")
                .setMessage("That download isn't on this mod's page any more. Pick one of its files below.")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Asks whether to replace an earlier download of this mod or keep both, then installs. */
    private void chooseInstall(GameBanana.ModFile file) {
        if (busy)
            return;
        if (!hasAccess()) {
            new AlertDialog.Builder(this)
                    .setTitle("File access needed")
                    .setMessage("Go back to the launcher and allow all files access first, so mods can be "
                            + "saved to SonicR/mods.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        List<ModInstaller.Installed> installed = installedForMod();
        ModInstaller.Installed same = null;
        for (ModInstaller.Installed i : installed)
            if (i.fileId == file.id)
                same = i;
        if (same != null) {
            install(file, same);
            return;
        }
        if (installed.isEmpty()) {
            install(file, null);
            return;
        }
        ModInstaller.Installed previous = installed.get(0);
        for (ModInstaller.Installed i : installed)
            if (i.installedAt > previous.installedAt)
                previous = i;
        ModInstaller.Installed replace = previous;
        new AlertDialog.Builder(this)
                .setTitle("Already installed")
                .setMessage("You have " + previous.fileName + " from this mod (" + previous.where() + ").\n\n"
                        + "Replace it with " + file.name + " (an update, or a different version), "
                        + "or keep both (an add-on)?")
                .setPositiveButton("Replace", (dialog, which) -> install(file, replace))
                .setNeutralButton("Keep both", (dialog, which) -> install(file, null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void install(GameBanana.ModFile file, ModInstaller.Installed replace) {
        busy = true;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);
        TextView message = text("Starting…", 14, 0xFFE0E0E0);
        content.addView(message);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(10);
        content.addView(bar, barParams);
        TextView amount = text("", 13, 0xFF90A4AE);
        content.addView(amount);

        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Installing " + details.mod.name)
                .setView(content)
                .setCancelable(false)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true);
            message.setText("Cancelling…");
            v.setEnabled(false);
        }));
        dialog.show();

        GameBanana.Mod mod = details.mod;
        File cache = getCacheDir();
        worker.execute(() -> {
            ModInstaller.Result result = null;
            String error = null;
            try {
                result = ModInstaller.install(cache, mod, file, replace, (text, done, total) -> {
                    if (cancelled.get())
                        return false;
                    runOnUiThread(() -> {
                        if (cancelled.get())
                            return;
                        message.setText(text);
                        if (total > 0) {
                            bar.setIndeterminate(false);
                            bar.setProgress((int) Math.min(1000, done * 1000 / total));
                            amount.setText(text.startsWith("Downloading")
                                    ? ModBrowserActivity.size(done) + " of " + ModBrowserActivity.size(total)
                                    : (done * 100 / total) + "%");
                        } else {
                            bar.setIndeterminate(true);
                            amount.setText("");
                        }
                    });
                    return true;
                });
            } catch (IOException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = e.toString();
            }
            ModInstaller.Result done = result;
            String failure = error;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed())
                    return;
                dialog.dismiss();
                if (done == null) {
                    if (!cancelled.get())
                        new AlertDialog.Builder(this)
                                .setTitle("Install failed")
                                .setMessage(failure == null ? "Something went wrong." : failure)
                                .setPositiveButton("OK", null)
                                .show();
                    render();
                    return;
                }
                render();
                installed(done);
            });
        });
    }

    /** After an install: say where it went, list what was found, and offer to switch it on. */
    private void installed(ModInstaller.Result result) {
        ModInstaller.Installed installed = result.installed;
        ModManager manager = new ModManager();
        manager.load();
        List<ModManager.Mod> found = new ArrayList<>();
        for (ModManager.Mod mod : manager.mods)
            if (result.folders.contains(mod.directory))
                found.add(mod);

        StringBuilder message = new StringBuilder("Saved to SonicR/mods/" + installed.folder.getName() + ".");
        boolean allOn = !found.isEmpty();
        for (ModManager.Mod mod : found)
            allOn &= mod.enabled;
        if (!result.looksLikeMod)
            message.append("\n\nIt came without a mod.ini, so one was made for it. "
                    + "Check the mod's page in case it needs setting up some other way.");
        if (found.size() > 1)
            message.append("\n\nIt holds ").append(found.size()).append(" mods:");
        for (ModManager.Mod mod : found) {
            if (found.size() > 1)
                message.append("\n• ").append(mod.title);
            for (String note : mod.notes)
                message.append(found.size() > 1 ? "\n   " : "\n\n").append("⚠ ").append(note);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Installed").setMessage(message);
        if (found.isEmpty() || allOn) {
            if (allOn)
                message.append("\n\nIt's switched on.");
            builder.setMessage(message).setPositiveButton("OK", null);
        } else {
            message.append(found.size() > 1 ? "\n\nTurn them on now?" : "\n\nTurn it on now?");
            builder.setMessage(message)
                    .setPositiveButton("Turn on", (dialog, which) -> {
                        try {
                            ModManager.enable(new ArrayList<>(result.folders));
                        } catch (IOException e) {
                            new AlertDialog.Builder(this).setMessage("Couldn't save the mod list: " + e.getMessage())
                                    .setPositiveButton("OK", null).show();
                        }
                    })
                    .setNegativeButton("Later", null);
        }
        builder.show();
    }

    private void confirmRemove(ModInstaller.Installed installed) {
        if (busy)
            return;
        new AlertDialog.Builder(this)
                .setTitle("Remove " + installed.fileName + "?")
                .setMessage("Deletes " + installed.where() + " from your phone.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    busy = true;
                    worker.execute(() -> {
                        ModInstaller.uninstall(installed);
                        runOnUiThread(() -> {
                            busy = false;
                            if (!isDestroyed())
                                render();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static String date(long seconds) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(seconds * 1000));
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
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        view.setBackground(background);
        view.setPadding(dp(14), dp(6), dp(14), dp(6));
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
