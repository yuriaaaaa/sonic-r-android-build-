package com.yuriaaaaa.sonicr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browse and search Sonic '06 mods on GameBanana; tap one to see its files and install it. */
public class ModBrowserActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private EditText searchBox;
    private Button moreButton;
    private Button newestButton, updatedButton;

    private String sort = "new";
    private String query = "";
    private int page = 1;
    private boolean loading;
    private int generation;
    private final Set<Integer> shown = new HashSet<>();
    private Map<Integer, ModInstaller.Installed> installed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModManager.init(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Get mods", 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button done = button("Done", 0xFF2E7D32);
        done.setOnClickListener(v -> finish());
        header.addView(done);
        column.addView(header);

        TextView help = text("Sonic R mods from GameBanana. Installing downloads the mod and unpacks it straight "
                + "into SonicR/mods, ready to switch on. Mods that swap files, music or textures work; "
                + "parts that need a Windows DLL or cheat codes don't.", 13, 0xFF90A4AE);
        help.setPadding(0, dp(6), 0, dp(10));
        column.addView(help);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchBox = new EditText(this);
        searchBox.setHint("Search GameBanana");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(0xFF78909C);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button searchButton = button("Search", 0xFF1565C0);
        searchButton.setOnClickListener(v -> runSearch());
        searchRow.addView(searchButton);
        newestButton = button("Newest", 0xFF37474F);
        newestButton.setOnClickListener(v -> browse("new"));
        searchRow.addView(newestButton);
        updatedButton = button("Updated", 0xFF37474F);
        updatedButton.setOnClickListener(v -> browse("updated"));
        searchRow.addView(updatedButton);
        column.addView(searchRow);

        status = text("", 14, 0xFFE0E0E0);
        status.setPadding(0, dp(10), 0, dp(6));
        column.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        column.addView(list);

        moreButton = button("Load more", 0xFF37474F);
        moreButton.setOnClickListener(v -> loadPage());
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = dp(8);
        moreParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(moreButton, moreParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        scroll.addView(column);
        setContentView(scroll);

        browse("new");
    }

    @Override
    protected void onResume() {
        super.onResume();
        installed = ModInstaller.installed();
        // Refresh the badges after an install.
        for (int i = 0; i < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (tag instanceof GameBanana.Mod)
                updateBadge(list.getChildAt(i), (GameBanana.Mod) tag);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void browse(String newSort) {
        sort = newSort;
        query = "";
        searchBox.setText("");
        restart();
    }

    private void runSearch() {
        String q = searchBox.getText().toString().trim();
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null)
            ime.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        if (q.isEmpty()) {
            browse(sort);
            return;
        }
        query = q;
        restart();
    }

    private void restart() {
        generation++;
        page = 1;
        loading = false;
        shown.clear();
        list.removeAllViews();
        newestButton.setAlpha(query.isEmpty() && sort.equals("new") ? 1f : 0.6f);
        updatedButton.setAlpha(query.isEmpty() && sort.equals("updated") ? 1f : 0.6f);
        loadPage();
    }

    private void loadPage() {
        if (loading)
            return;
        loading = true;
        int myGeneration = generation;
        int fromPage = page;
        String q = query;
        String s = sort;
        status.setText(q.isEmpty() ? "Loading mods…" : "Searching for \u201c" + q + "\u201d…");
        moreButton.setVisibility(View.GONE);
        worker.execute(() -> {
            GameBanana.Page mods = null;
            String error = null;
            try {
                mods = q.isEmpty() ? GameBanana.list(s, fromPage) : GameBanana.search(q, fromPage);
            } catch (Exception e) {
                error = e.getMessage();
            }
            GameBanana.Page result = mods;
            String failure = error;
            runOnUiThread(() -> {
                if (myGeneration != generation || isFinishing())
                    return;
                loading = false;
                if (result == null) {
                    status.setText("Couldn't reach GameBanana: " + failure + ". Check the connection and try again.");
                    moreButton.setText("Try again");
                    moreButton.setVisibility(View.VISIBLE);
                    return;
                }
                page = result.next;
                int added = 0;
                for (GameBanana.Mod mod : result.mods) {
                    if (!shown.add(mod.id))
                        continue;
                    list.addView(row(mod));
                    added++;
                }
                int total = shown.size();
                status.setText(total == 0
                        ? (q.isEmpty() ? "No mods found." : "No mods match \u201c" + q + "\u201d.")
                        : total + (total == 1 ? " mod" : " mods") + (q.isEmpty() ? "" : " for \u201c" + q + "\u201d"));
                // A page that only brought pages we already have (or nothing) means the end.
                moreButton.setText("Load more");
                moreButton.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
            });
        });
    }

    private View row(GameBanana.Mod mod) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1A222C);
        background.setCornerRadius(dp(8));
        row.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);
        row.setTag(mod);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(0xFF263238);
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(128), dp(72));
        thumbParams.setMarginEnd(dp(12));
        row.addView(thumbnail, thumbParams);
        ImageLoader.load(thumbnail, mod.thumbnail);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(mod.name, 16, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        StringBuilder line = new StringBuilder();
        if (!mod.author.isEmpty())
            line.append("by ").append(mod.author);
        if (!mod.category.isEmpty())
            line.append(line.length() > 0 ? " \u00b7 " : "").append(mod.category);
        info.addView(text(line.toString(), 13, 0xFF90A4AE));
        String stats = "\u2665 " + mod.likes + " \u00b7 " + mod.views + " views"
                + (mod.updated > 0 ? " \u00b7 " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(mod.updated * 1000)) : "");
        info.addView(text(stats, 13, 0xFF78909C));
        TextView badge = text("", 13, 0xFF81C784);
        badge.setTag("badge");
        info.addView(badge);
        row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        updateBadge(row, mod);
        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, ModDetailsActivity.class);
            intent.putExtra(ModDetailsActivity.EXTRA_MOD_ID, mod.id);
            startActivity(intent);
        });
        return row;
    }

    private void updateBadge(View row, GameBanana.Mod mod) {
        TextView badge = row.findViewWithTag("badge");
        if (badge == null)
            return;
        ModInstaller.Installed i = installed != null ? installed.get(mod.id) : null;
        if (i == null) {
            badge.setVisibility(View.GONE);
        } else if (mod.dateUpdated > i.installedAt + 60) {
            badge.setText("Installed \u00b7 update available");
            badge.setTextColor(0xFFFFB74D);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setText("Installed");
            badge.setTextColor(0xFF81C784);
            badge.setVisibility(View.VISIBLE);
        }
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

    int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%d KB", Math.max(1, bytes / 1024));
    }
}
