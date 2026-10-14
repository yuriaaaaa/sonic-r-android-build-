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
 * the screen is touched again. Whenever they aren't in use (hidden, switched
 * off, or a controller is playing), a two-finger tap opens the game menu.
 */
public class TouchControlsView extends View {

    private static final int DPAD = -2;
    private static final int NONE = -1;

    private static final class Btn {
        final String label;
        final int key;
        final float fx, fy, fr;   // centre and radius as fractions of the view height (x of width)
        float cx, cy, r;
        Btn(String label, int key, float fx, float fy, float fr) {
            this.label = label; this.key = key; this.fx = fx; this.fy = fy; this.fr = fr;
        }
    }

    private final Btn[] mButtons = {
        new Btn("A",     KeyEvent.KEYCODE_A,      0.895f, 0.70f, 0.115f),
        new Btn("Jump",  KeyEvent.KEYCODE_SPACE,  0.765f, 0.83f, 0.095f),
        new Btn("Look",  KeyEvent.KEYCODE_1,      0.775f, 0.52f, 0.070f),
        new Btn("L",     KeyEvent.KEYCODE_Z,      0.075f, 0.22f, 0.080f),
        new Btn("R",     KeyEvent.KEYCODE_X,      0.925f, 0.22f, 0.080f),
        new Btn("Back",  KeyEvent.KEYCODE_ESCAPE, 0.390f, 0.09f, 0.060f),
        new Btn("Start", KeyEvent.KEYCODE_ENTER,  0.610f, 0.09f, 0.060f),
    };
    private final Btn mHide = new Btn("", 0, 0.5f, 0.09f, 0.050f);

    private float mPadX, mPadY, mPadR;

    // Per-pointer target (DPAD, button index or NONE), indexed by pointer id.
    private final int[] mTarget = new int[16];
    private final float[] mPx = new float[16], mPy = new float[16];
    private final Set<Integer> mHeld = new HashSet<>();

    private boolean mHidden;           // hidden with the hide button
    private boolean mGamepadHidden;    // hidden because a controller is in use
    private boolean mDisabled;         // switched off in settings
    private boolean mMenuGesture;      // swallowing a two-finger tap until all fingers lift
    private Runnable mOnMenu;

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
        if (!mGamepadHidden) {
            mGamepadHidden = true;
            releaseAll();
            postInvalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (Btn b : mButtons) layout(b, w, h);
        layout(mHide, w, h);
        mPadR = 0.21f * h;
        mPadX = 0.06f * w + mPadR;
        mPadY = 0.70f * h;
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
        boolean visible = !mHidden && !mGamepadHidden;
        if (mGamepadHidden || mDisabled) {
            return;
        }

        // Hide/show toggle: always drawn so it can be found again.
        mFill.setColor(Color.argb(visible ? 130 : 70, 0, 0, 0));
        c.drawCircle(mHide.cx, mHide.cy, mHide.r, mFill);
        mStroke.setColor(Color.argb(visible ? 150 : 80, 255, 255, 255));
        c.drawCircle(mHide.cx, mHide.cy, mHide.r, mStroke);
        c.drawLine(mHide.cx - mHide.r * 0.5f, mHide.cy, mHide.cx + mHide.r * 0.5f, mHide.cy, mStroke);
        if (!visible) {
            c.drawLine(mHide.cx, mHide.cy - mHide.r * 0.5f, mHide.cx, mHide.cy + mHide.r * 0.5f, mStroke);
            return;
        }

        // D-pad
        mFill.setColor(Color.argb(110, 0, 0, 0));
        c.drawCircle(mPadX, mPadY, mPadR, mFill);
        mStroke.setColor(Color.argb(150, 255, 255, 255));
        c.drawCircle(mPadX, mPadY, mPadR, mStroke);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_LEFT, -1, 0);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_RIGHT, 1, 0);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_UP, 0, -1);
        drawArrow(c, KeyEvent.KEYCODE_DPAD_DOWN, 0, 1);

        for (Btn b : mButtons) {
            boolean down = mHeld.contains(b.key);
            mFill.setColor(down ? Color.argb(190, 60, 60, 60) : Color.argb(130, 0, 0, 0));
            c.drawCircle(b.cx, b.cy, b.r, mFill);
            c.drawCircle(b.cx, b.cy, b.r, mStroke);
            c.drawText(b.label, b.cx, b.cy - (mText.descent() + mText.ascent()) / 2, mText);
        }
    }

    private void drawArrow(Canvas c, int key, int dx, int dy) {
        float d = mPadR * 0.62f, s = mPadR * 0.2f;
        float x = mPadX + dx * d, y = mPadY + dy * d;
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

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();

        // Two-finger tap opens the game menu while the controls aren't in use.
        boolean inactive = mDisabled || mGamepadHidden || mHidden;
        if (action == MotionEvent.ACTION_DOWN) {
            mMenuGesture = false;
        }
        if (inactive && action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            mMenuGesture = true;
            if (mOnMenu != null) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                mOnMenu.run();
            }
            return true;
        }
        if (mMenuGesture) {
            return true;
        }
        if (mDisabled) {
            return true;
        }

        if (mGamepadHidden) {
            // A single tap (not a two-finger one) brings the controls back.
            if (action == MotionEvent.ACTION_UP) {
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
                    float dx = x - mPadX, dy = y - mPadY;
                    if (dx * dx + dy * dy <= mPadR * mPadR * 1.6f) {
                        mTarget[id] = DPAD;
                    } else {
                        mTarget[id] = hitButton(x, y);
                    }
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
        float dx = x - mPadX, dy = y - mPadY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < mPadR * 0.22f) return;
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
