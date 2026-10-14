package com.yuriaaaaa.sonicr;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Edit touch controls: drag a control to move it; the panel resizes and
 * renames the selected one. Used from the launcher (TouchEditorActivity) and
 * from the in-game menu. Blue and gold like the game menu.
 */
final class TouchLayoutEditor {
    static final int BLUE = 0xFF0A3CE6;
    static final int GOLD = 0xFFFFC21A;
    private static final float MIN_R = 0.035f, MAX_R = 0.35f;

    interface Done {
        void onDone(boolean saved);
    }

    private final Activity mActivity;
    private final TouchControlsView mView;
    private final FrameLayout mOverlay;
    private final LinearLayout mPanel;
    private final TextView mWhat;
    private final SeekBar mSize;
    private final EditText mLabel;
    private final Switch mShown;
    private final Done mDone;
    private int mPanelPos = 1;   // 0 top, 1 centre, 2 bottom
    private int mProfile;
    private final Button[] mProfileButtons = new Button[LauncherActivity.TOUCH_PROFILES];
    private boolean mUpdating;

    TouchLayoutEditor(Activity activity, ViewGroup parent, TouchControlsView view, Done done) {
        mActivity = activity;
        mView = view;
        mDone = done;
        float d = activity.getResources().getDisplayMetrics().density;

        mOverlay = new FrameLayout(activity);
        mPanel = new LinearLayout(activity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * d);
        mPanel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BLUE);
        bg.setCornerRadius(18 * d);
        bg.setStroke((int) (3 * d), GOLD);
        mPanel.setBackground(bg);
        mPanel.setClickable(true);   // touches on the panel don't reach the controls

        TextView title = text("Edit touch controls", 18, true);
        mPanel.addView(title);
        mPanel.addView(text("Drag a control to move it. Tap one to resize, rename or hide it (dashed ones are hidden in game; the C button is for mods like Sonic R Heroes). "
            + "Save stores the layout in the chosen profile and uses it.", 13, false));

        // Profiles: three saved layouts. Picking one shows it; Save stores the
        // edits there and makes it the one used in game.
        mProfile = LauncherActivity.activeProfile(activity);
        LinearLayout profiles = new LinearLayout(activity);
        profiles.setOrientation(LinearLayout.HORIZONTAL);
        profiles.setPadding(0, (int) (6 * d), 0, 0);
        for (int i = 0; i < mProfileButtons.length; i++) {
            final int p = i + 1;
            mProfileButtons[i] = button("Profile " + p, v -> {
                mProfile = p;
                mView.loadProfile(p);
                refresh();
            });
            profiles.addView(mProfileButtons[i]);
        }
        mPanel.addView(profiles);

        mWhat = text("Nothing selected", 15, true);
        mWhat.setTextColor(GOLD);
        mWhat.setPadding(0, (int) (8 * d), 0, 0);
        mPanel.addView(mWhat);

        mSize = new SeekBar(activity);
        mSize.setMax(100);
        ColorStateList gold = ColorStateList.valueOf(GOLD);
        mSize.setProgressTintList(gold);
        mSize.setThumbTintList(gold);
        mSize.setProgressBackgroundTintList(ColorStateList.valueOf(0x60FFFFFF));
        mSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int v, boolean user) {
                if (user && !mUpdating) mView.setSelectedSize(MIN_R + (MAX_R - MIN_R) * v / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        mPanel.addView(mSize);

        mLabel = new EditText(activity);
        mLabel.setSingleLine(true);
        mLabel.setTextColor(Color.WHITE);
        mLabel.setHintTextColor(0x99FFFFFF);
        mLabel.setHint("Button text");
        mLabel.setBackgroundTintList(gold);
        mLabel.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (!mUpdating) mView.setSelectedLabel(s.toString().trim());
            }
        });
        mPanel.addView(mLabel);

        mShown = new Switch(activity);
        mShown.setText("Show in game");
        mShown.setTextColor(Color.WHITE);
        mShown.setThumbTintList(gold);
        mShown.setTrackTintList(ColorStateList.valueOf(0x80FFC21A));
        mShown.setOnCheckedChangeListener((sw, on) -> {
            if (!mUpdating) mView.setSelectedShown(on);
        });
        mPanel.addView(mShown);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Reset", v -> mView.resetLayout()));
        row.addView(button("Move panel", v -> {
            mPanelPos = (mPanelPos + 1) % 3;
            placePanel();
        }));
        row.addView(button("Cancel", v -> finish(false)));
        row.addView(button("Save", v -> finish(true)));
        mPanel.addView(row);

        int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.42f);
        mOverlay.addView(mPanel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        placePanel();
        parent.addView(mOverlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlay.setClickable(false);

        mView.setEditing(true, this::refresh);
        refresh();
    }

    private void placePanel() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        lp.gravity = Gravity.CENTER_HORIZONTAL
            | (mPanelPos == 0 ? Gravity.TOP : mPanelPos == 1 ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM);
        mPanel.setLayoutParams(lp);
    }

    private void refresh() {
        float d = mActivity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mProfileButtons.length; i++) {
            boolean on = i + 1 == mProfile;
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(12 * d);
            chip.setColor(on ? GOLD : Color.TRANSPARENT);
            chip.setStroke((int) (2 * d), GOLD);
            mProfileButtons[i].setBackground(chip);
            mProfileButtons[i].setTextColor(on ? BLUE : Color.WHITE);
        }
        TouchControlsView.Btn b = mView.selected();
        mUpdating = true;
        if (b == null) {
            mWhat.setText("Nothing selected");
            mSize.setEnabled(false);
            mLabel.setEnabled(false);
            mLabel.setText("");
            mShown.setEnabled(false);
            mShown.setChecked(true);
        } else {
            mWhat.setText("Selected: " + b.defLabel);
            mSize.setEnabled(true);
            mSize.setProgress(Math.round((b.fr - MIN_R) / (MAX_R - MIN_R) * 100));
            mLabel.setEnabled(b.hasLabel());
            mLabel.setText(b.hasLabel() ? b.label : "");
            mShown.setEnabled(b.canHide());
            mShown.setChecked(b.shown);
        }
        mUpdating = false;
    }

    private void finish(boolean save) {
        if (save) {
            LauncherActivity.prefs(mActivity).edit()
                .putString(LauncherActivity.layoutKey(mProfile), mView.saveLayout())
                .putInt(LauncherActivity.KEY_TOUCH_PROFILE, mProfile).apply();
        } else {
            mView.reloadLayout();
        }
        mView.setEditing(false, null);
        ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
        View focus = mActivity.getCurrentFocus();
        if (focus != null) {
            ((android.view.inputmethod.InputMethodManager) mActivity.getSystemService(
                Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        mDone.onDone(save);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(mActivity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }
}
