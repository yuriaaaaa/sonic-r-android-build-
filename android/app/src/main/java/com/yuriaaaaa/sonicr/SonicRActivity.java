package com.yuriaaaaa.sonicr;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/** SDLActivity running the engine, with the touch controls laid over it. */
public class SonicRActivity extends SDLActivity {

    static final String EXTRA_DATA_DIR = "dataDir";
    static final String EXTRA_STRETCH = "stretch";
    static final String EXTRA_RESOLUTION = "resolution";
    static final String EXTRA_MSAA = "msaa";
    static final String EXTRA_SMOOTH = "smooth";
    static final String EXTRA_VSYNC = "vsync";
    static final String EXTRA_FPS = "fps";

    /** Frames per second times ten, counted at each buffer swap (platform_sdl.c). */
    private static native int nativeFpsTenths();

    private android.widget.TextView mFps;
    private final android.os.Handler mFpsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mFpsTick = new Runnable() {
        @Override
        public void run() {
            int t = nativeFpsTenths();
            mFps.setText(t > 0 ? (t / 10) + " FPS" : "-- FPS");
            mFpsHandler.postDelayed(this, 500);
        }
    };

    private TouchControlsView mTouch;
    private AlertDialog mQuitDialog;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "SDL2_mixer", "main" };
    }

    @Override
    protected String[] getArguments() {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("--fullscreen");
        if (getIntent().getBooleanExtra(EXTRA_STRETCH, false)) {
            args.add("--stretch");
        }
        int msaa = getIntent().getIntExtra(EXTRA_MSAA, 0);
        if (msaa > 0) {
            args.add("--msaa");
            args.add(Integer.toString(msaa));
        }
        if (getIntent().getBooleanExtra(EXTRA_SMOOTH, false)) {
            args.add("--smooth");
        }
        if (!getIntent().getBooleanExtra(EXTRA_VSYNC, true)) {
            args.add("--novsync");
        }
        String dir = getIntent().getStringExtra(EXTRA_DATA_DIR);
        if (dir != null) {
            args.add(dir);
        }
        return args.toArray(new String[0]);
    }

    /* Resolution setting: render at a fixed height and let the display
     * hardware scale it to the screen. 0 keeps the native resolution. */
    @Override
    protected org.libsdl.app.SDLSurface createSDLSurface(android.content.Context context) {
        org.libsdl.app.SDLSurface surface = super.createSDLSurface(context);
        int height = getIntent().getIntExtra(EXTRA_RESOLUTION, 0);
        if (height > 0) {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(m);
            int longSide = Math.max(m.widthPixels, m.heightPixels);
            int shortSide = Math.min(m.widthPixels, m.heightPixels);
            if (height < shortSide) {
                int width = Math.round(height * (float) longSide / shortSide);
                surface.getHolder().setFixedSize(width, height);
            }
        }
        return surface;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mBrokenLibraries || mLayout == null) {
            return;
        }
        mTouch = new TouchControlsView(this);
        mLayout.addView(mTouch, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (getIntent().getBooleanExtra(EXTRA_FPS, false)) {
            mFps = new android.widget.TextView(this);
            mFps.setTextColor(android.graphics.Color.WHITE);
            mFps.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            mFps.setBackgroundColor(android.graphics.Color.argb(140, 0, 0, 0));
            int p = (int) (6 * getResources().getDisplayMetrics().density);
            mFps.setPadding(p, p / 2, p, p / 2);
            android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
            lp.setMargins(p, p, 0, 0);
            mLayout.addView(mFps, lp);
            mFpsHandler.post(mFpsTick);
        }
    }

    /* A real controller hides the touch controls until the screen is touched.
     * The phone's Back key asks whether to quit; a controller's B/Back still
     * reaches the game as Escape. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean pad = isGamepad(event.getSource());
        if (mTouch != null && pad) {
            mTouch.hideForGamepad();
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !pad) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                askToQuit();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        askToQuit();
    }

    private void askToQuit() {
        if (mQuitDialog != null && mQuitDialog.isShowing()) {
            return;
        }
        setGamePaused(true);
        mQuitDialog = new AlertDialog.Builder(this)
            .setTitle("Quit Sonic R?")
            .setMessage("Are you sure you want to quit? Any unsaved progress will be lost.")
            .setCancelable(true)
            .setOnCancelListener(d -> setGamePaused(false))
            .setPositiveButton("Continue", (d, w) -> setGamePaused(false))
            .setNegativeButton("Exit game", (d, w) -> exitToLauncher())
            .show();
    }

    /* Goes through SDL's own state machine so the pause isn't counted twice if
     * Android pauses the activity while the dialog is up. Resuming waits for
     * window focus, which comes back once the dialog closes. */
    private static void setGamePaused(boolean paused) {
        SDLActivity.mNextNativeState = paused ? NativeState.PAUSED : NativeState.RESUMED;
        SDLActivity.handleNativeState();
    }

    private void exitToLauncher() {
        // The game runs in its own process (":game" in the manifest), so ending
        // it here returns to the launcher and the next Play starts clean.
        Intent i = new Intent(this, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mTouch != null && isGamepad(event.getSource())) {
            mTouch.hideForGamepad();
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
}
