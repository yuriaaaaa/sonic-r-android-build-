package com.yuriaaaaa.sonicr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.util.HashSet;
import java.util.Set;

/**
 * On-screen controls. Each control sends the keyboard key the PC game already
 * understands (see docs/desktop.md), so the game's own remapping still works:
 *   D-pad = arrows, A = accelerate, Jump = Space, L/R = Z/X sharp turns,
 *   Look = 1 (look back), Start = Enter, Back = Escape.
 * The top-centre button hides them; a connected controller hides them until
 * the screen is touched again. A three-finger tap away from the controls opens
 * the game menu.
 *
 * In edit mode (Edit touch controls) touches move the controls instead, and
 * the editor changes the selected control's size and label. The layout is
 * saved per profile (three slots) in the shared settings; see LauncherActivity.layoutKey().
 */
public class TouchControlsView extends View {

    private static final int DPAD = -2;
    private static final int NONE = -1;

    static final class Btn {
        final String id;          // stable name used in the saved layout
        final String defLabel;
        final int key;
        final float defX, defY, defR;
        String label;
        float fx, fy, fr;         // centre as fractions of width/height, radius as a fraction of height
        float cx, cy, r;
        Btn(String id, String label, int key, float fx, float fy, float fr) {
            this.id = id; this.defLabel = label; this.key = key;
            this.defX = fx; this.defY = fy; this.defR = fr;
            reset();
        }
        void reset() {
            label = defLabel; fx = defX; fy = defY; fr = defR;
        }
        boolean hasLabel() {
            return key != 0;
        }
    }

    private final Btn[] mButtons = {
        new Btn("A",     "A",     KeyEvent.KEYCODE_A,      0.895f, 0.70f, 0.115f),
        new Btn("Jump",  "Jump",  KeyEvent.KEYCODE_SPACE,  0.765f, 0.83f, 0.095f),
        new Btn("Look",  "Look",  KeyEvent.KEYCODE_1,      0.775f, 0.52f, 0.070f),
        new Btn("L",     "L",     KeyEvent.KEYCODE_Z,      0.075f, 0.22f, 0.080f),
        new Btn("R",     "R",     KeyEvent.KEYCODE_X,      0.925f, 0.22f, 0.080f),
        new Btn("Back",  "Back",  KeyEvent.KEYCODE_ESCAPE, 0.390f, 0.09f, 0.060f),
        new Btn("Start", "Start", KeyEvent.KEYCODE_ENTER,  0.610f, 0.09f, 0.060f),
    };
    // D-pad: fx < 0 means "default", placed from the left edge for the screen's aspect.
    private final Btn mPad = new Btn("DPad", "D-pad", 0, -1f, 0.70f, 0.21f);
    private final Btn mHide = new Btn("Hide", "Hide", 0, 0.5f, 0.09f, 0.050f);

    // Per-pointer target (DPAD, button index or NONE), indexed by pointer id.
    private final int[] mTarget = new int[16];
    private final float[] mPx = new float[16], mPy = new float[16];
    private final Set<Integer> mHeld = new HashSet<>();

    private boolean mHidden;           // hidden with the hide button
    private boolean mGamepadHidden;    // hidden because a controller is in use
    private boolean mDisabled;         // switched off in settings
    private Runnable mOnMenu;

    // Three-finger tap: every finger lands off the controls, three are down at
    // once, and all lift again quickly without sliding.
    private static final long TAP_MS = 450;
    private boolean mGestureClean;
    private int mGestureMax;
    private long mGestureStart;
    private float mGestureSlop;
    private final float[] mGx = new float[16], mGy = new float[16];

    // Edit mode
    private boolean mEditing;
    private Btn mSelected;
    private float mDragDx, mDragDy;
    private Runnable mOnSelect;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences mPrefs;

    public TouchControlsView(Context ctx) {
        super(ctx);
        for (int i = 0; i < mTarget.length; i++) mTarget[i] = NONE;
        mStroke.setStyle(Paint.Style.STROKE);
        mText.setColor(Color.argb(235, 255, 255, 255));
        mText.setTextAlign(Paint.Align.CENTER);
        mPrefs = ctx.getSharedPreferences("touch", Context.MODE_PRIVATE);
        mHidden = mPrefs.getBoolean("hidden", false);
        mGestureSlop = 24 * ctx.getResources().getDisplayMetrics().density;
        loadLayout(LauncherActivity.prefs(ctx).getString(
            LauncherActivity.layoutKey(LauncherActivity.activeProfile(ctx)), ""));
    }

    void setOnMenu(Runnable onMenu) {
        mOnMenu = onMenu;
    }

    void setEnabledControls(boolean enabled) {
        mDisabled = !enabled;
        releaseAll();
        invalidate();
    }

    void hideForGamepad() {
        if (!mGamepadHidden && !mEditing) {
            mGamepadHidden = true;
            releaseAll();
            postInvalidate();
        }
    }

    // ---- layout save/load ----

    private Btn[] all() {
        Btn[] out = new Btn[mButtons.length + 2];
        System.arraycopy(mButtons, 0, out, 0, mButtons.length);
        out[mButtons.length] = mPad;
        out[mButtons.length + 1] = mHide;
        return out;
    }

    /** "id,fx,fy,fr,label;..." — labels have ',' and ';' removed. */
    String saveLayout() {
        StringBuilder sb = new StringBuilder();
        for (Btn b : all()) {
            sb.append(b.id).append(',').append(b.fx).append(',').append(b.fy).append(',')
              .append(b.fr).append(',').append(b.label.replace(",", "").replace(";", "")).append(';');
        }
        return sb.toString();
    }

    private void loadLayout(String s) {
        for (Btn b : all()) b.reset();
        if (s == null || s.isEmpty()) return;
        for (String entry : s.split(";")) {
            String[] f = entry.split(",", 5);
            if (f.length < 4) continue;
            for (Btn b : all()) {
                if (!b.id.equals(f[0])) continue;
                try {
                    b.fx = Float.parseFloat(f[1]);
                    b.fy = Float.parseFloat(f[2]);
                    b.fr = Float.parseFloat(f[3]);
                } catch (NumberFormatException ignored) {
                    b.reset();
                }
                if (f.length == 5 && b.hasLabel() && !f[4].trim().isEmpty()) b.label = f[4];
            }
        }
    }

    // ---- edit mode, driven by TouchLayoutEditor ----

    void setEditing(boolean editing, Runnable onSelect) {
        mEditing = editing;
        mOnSelect = onSelect;
        mSelected = null;
        mGamepadHidden = false;
        releaseAll();
        invalidate();
    }

    Btn selected() {
        return mSelected;
    }

    void setSelectedSize(float fr) {
        if (mSelected == null) return;
        mSelected.fr = fr;
        relayout();
    }

    void setSelectedLabel(String label) {
        if (mSelected == null || !mSelected.hasLabel()) return;
        mSelected.label = label.isEmpty() ? mSelected.defLabel : label;
        invalidate();
    }

    void resetLayout() {
        for (Btn b : all()) b.reset();
        mSelected = null;
        relayout();
        if (mOnSelect != null) mOnSelect.run();
    }

    void reloadLayout() {
        loadProfile(LauncherActivity.activeProfile(getContext()));
    }

    /** Shows a saved profile (1-3); an empty slot shows the default layout. */
    void loadProfile(int profile) {
        loadLayout(LauncherActivity.prefs(getContext()).getString(LauncherActivity.layoutKey(profile), ""));
        mSelected = null;
        relayout();
    }

    private void relayout() {
        onSizeChanged(getWidth(), getHeight(), 0, 0);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        for (Btn b : mButtons) layout(b, w, h);
        layout(mHide, w, h);
        mPad.r = mPad.fr * h;
        mPad.cx = mPad.fx < 0 ? 0.06f * w + mPad.r : mPad.fx * w;
        mPad.cy = mPad.fy * h;
        mText.setTextSize(0.045f * h);
        mStroke.setStrokeWidth(Math.max(2f, 0.006f * h));
    }

    private static void layout(Btn b, int w, int h) {
        b.cx = b.fx * w;
        b.cy = b.fy * h;
        b.r = b.fr * h;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mEditing) {
            drawControls(c);
            return;
        }
        boolean visible = !mHidden && !mGamepadHidden;
        if (mGamepadHidden || mDisabled) {
            return;
        }

        drawHide(c, visible);
        if (!visible) {
            return;
        }
        drawControls(c);
    }

    private void drawHide(Canvas c, boolean visible) {
        // Hide/show toggle: always drawn so it can be found again.
        mFill.setColor(Color.argb(visible ? 130 : 70, 0, 0, 0));
        c.drawCircle(mHide.cx, mHide.cy, mHide.r, mFill);
        mStroke.setColor(Color.argb(visible ? 150 : 80, 255, 255, 255));
        c.drawCircle(mHide.cx, mHide.cy, mHide.r, mStroke);
        c.drawLine(mHide.cx - mHide.r * 0.5f, mHide.cy, mHide.cx + mHide.r * 0.5f, mHide.cy, mStroke);
        if (!visible) {
            c.drawLine(mHide.cx, mHide.cy - mHide.r * 0.5f, mHide.cx, mHide.cy + mHide.r * 0.5f, mStroke);
        }
    }

    private void drawControls(Canvas c) {
        if (mEditing) {
            drawHide(c, true);
        }

        // D-pad
        mFill.setColor(Color.argb(110, 0, 0, 0));
        c.drawCircle(mPad.cx, mPad.cy, mPad.r, mFill);
        mStroke.setColor(Color.argb(150, 255, 255, 255));
        c.drawCircle(mPad.cx, mPad.cy, mPad.r, mStroke);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_LEFT, -1, 0);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_RIGHT, 1, 0);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_UP, 0, -1);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_DOWN, 0, 1);

        for (Btn b : mButtons) {
            boolean down = mHeld.contains(b.key);
            mFill.setColor(down ? Color.argb(190, 60, 60, 60) : Color.argb(130, 0, 0, 0));
            c.drawCircle(b.cx, b.cy, b.r, mFill);
            c.drawCircle(b.cx, b.cy, b.r, mStroke);
            float size = mText.getTextSize();
            mText.setTextSize(Math.min(size, b.r * 0.9f));
            c.drawText(b.label, b.cx, b.cy - (mText.descent() + mText.ascent()) / 2, mText);
            mText.setTextSize(size);
        }

        if (mEditing && mSelected != null) {
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(mStroke.getStrokeWidth() * 2.5f);
            ring.setColor(0xFFFFC21A);
            c.drawCircle(mSelected.cx, mSelected.cy, mSelected.r + ring.getStrokeWidth(), ring);
        }
    }

    private void drawArrow(Canvas c, int key, int dx, int dy) {
        float d = mPad.r * 0.62f, s = mPad.r * 0.2f;
        float x = mPad.cx + dx * d, y = mPad.cy + dy * d;
        mFill.setColor(Color.argb(mHeld.contains(key) ? 255 : 200, 255, 255, 255));
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x + dx * s, y + dy * s);
        p.lineTo(x - dx * s + dy * s, y - dy * s + dx * s);
        p.lineTo(x - dx * s - dy * s, y - dy * s - dx * s);
        p.close();
        c.drawPath(p, mFill);
    }

    private static boolean inside(Btn b, float x, float y, float scale) {
        float dx = x - b.cx, dy = y - b.cy, r = b.r * scale;
        return dx * dx + dy * dy <= r * r;
    }

    private int hitButton(float x, float y) {
        for (int i = 0; i < mButtons.length; i++) {
            if (inside(mButtons[i], x, y, 1.25f)) return i;
        }
        return NONE;
    }

    private boolean onPad(float x, float y) {
        float dx = x - mPad.cx, dy = y - mPad.cy;
        return dx * dx + dy * dy <= mPad.r * mPad.r * 1.6f;
    }

    /** True if (x, y) is on a control that's currently shown. */
    private boolean onControl(float x, float y) {
        if (mDisabled || mGamepadHidden) {
            return false;
        }
        if (inside(mHide, x, y, 1.4f)) {
            return true;
        }
        if (mHidden) {
            return false;
        }
        return onPad(x, y) || hitButton(x, y) != NONE;
    }

    /* Tracks the three-finger tap. Returns true once it has fired, for this event. */
    private boolean trackMenuGesture(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mGestureClean = true;
                mGestureMax = 1;
                mGestureStart = e.getEventTime();
                // fall through
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                if (onControl(e.getX(idx), e.getY(idx))) mGestureClean = false;
                if (id < mGx.length) { mGx[id] = e.getX(idx); mGy[id] = e.getY(idx); }
                mGestureMax = Math.max(mGestureMax, e.getPointerCount());
                if (mGestureMax > 3) mGestureClean = false;
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= mGx.length) continue;
                    if (Math.abs(e.getX(i) - mGx[id]) > mGestureSlop
                            || Math.abs(e.getY(i) - mGy[id]) > mGestureSlop) {
                        mGestureClean = false;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean fire = mGestureClean && mGestureMax == 3
                    && e.getEventTime() - mGestureStart <= TAP_MS;
                mGestureClean = false;
                if (fire && mOnMenu != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    mOnMenu.run();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mGestureClean = false;
                break;
        }
        return false;
    }

    private boolean editTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Btn hit = null;
                for (Btn b : mButtons) if (inside(b, x, y, 1.1f)) hit = b;
                if (hit == null && inside(mHide, x, y, 1.3f)) hit = mHide;
                if (hit == null && onPad(x, y)) hit = mPad;
                mSelected = hit;
                if (hit != null) {
                    mDragDx = hit.cx - x;
                    mDragDy = hit.cy - y;
                }
                if (mOnSelect != null) mOnSelect.run();
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (mSelected != null && getWidth() > 0 && getHeight() > 0) {
                    float nx = Math.max(0, Math.min(getWidth(), x + mDragDx));
                    float ny = Math.max(0, Math.min(getHeight(), y + mDragDy));
                    mSelected.fx = nx / getWidth();
                    mSelected.fy = ny / getHeight();
                    relayout();
                }
                break;
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mEditing) {
            return editTouch(e);
        }
        int action = e.getActionMasked();
        int idx = e.getActionIndex();

        if (trackMenuGesture(e)) {
            releaseAll();
            return true;
        }
        if (mDisabled) {
            return true;
        }

        if (mGamepadHidden) {
            // A single tap brings the controls back.
            if (action == MotionEvent.ACTION_UP && mGestureMax == 1) {
                mGamepadHidden = false;
                invalidate();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (inside(mHide, x, y, 1.4f)) {
                mHidden = !mHidden;
                mPrefs.edit().putBoolean("hidden", mHidden).apply();
                releaseAll();
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            }
        }
        if (mHidden) {
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                if (id < mTarget.length) {
                    float x = e.getX(idx), y = e.getY(idx);
                    mTarget[id] = onPad(x, y) ? DPAD : hitButton(x, y);
                    mPx[id] = x;
                    mPy[id] = y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= mTarget.length) continue;
                    mPx[id] = e.getX(i);
                    mPy[id] = e.getY(i);
                    if (mTarget[id] != DPAD) {
                        mTarget[id] = hitButton(mPx[id], mPy[id]);   // allow sliding between buttons
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id < mTarget.length) mTarget[id] = NONE;
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < mTarget.length; i++) mTarget[i] = NONE;
                break;
        }
        sync();
        return true;
    }

    private void addDpadKeys(Set<Integer> out, float x, float y) {
        float dx = x - mPad.cx, dy = y - mPad.cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < mPad.r * 0.22f) return;
        float nx = dx / len, ny = dy / len;
        final float t = 0.38f;   // sin(22.5°): 8-way sectors
        if (nx > t) out.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        if (nx < -t) out.add(KeyEvent.KEYCODE_DPAD_LEFT);
        if (ny > t) out.add(KeyEvent.KEYCODE_DPAD_DOWN);
        if (ny < -t) out.add(KeyEvent.KEYCODE_DPAD_UP);
    }

    private void sync() {
        Set<Integer> want = new HashSet<>();
        for (int id = 0; id < mTarget.length; id++) {
            int t = mTarget[id];
            if (t == DPAD) addDpadKeys(want, mPx[id], mPy[id]);
            else if (t >= 0) want.add(mButtons[t].key);
        }
        boolean changed = false;
        for (Integer k : new HashSet<>(mHeld)) {
            if (!want.contains(k)) {
                SDLActivity.onNativeKeyUp(k);
                mHeld.remove(k);
                changed = true;
            }
        }
        for (Integer k : want) {
            if (!mHeld.contains(k)) {
                SDLActivity.onNativeKeyDown(k);
                mHeld.add(k);
                changed = true;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        }
        if (changed) invalidate();
    }

    private void releaseAll() {
        for (int i = 0; i < mTarget.length; i++) mTarget[i] = NONE;
        for (Integer k : mHeld) SDLActivity.onNativeKeyUp(k);
        mHeld.clear();
    }
}
