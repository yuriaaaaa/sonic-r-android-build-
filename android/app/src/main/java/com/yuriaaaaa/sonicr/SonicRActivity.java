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
    static final String EXTRA_DEBUG_LOG = "debugLog";
    static final String EXTRA_NET_NAME = "netName";
    static final String EXTRA_NET_HOST = "netHost";

    /* Many phones drop Wi-Fi broadcast packets unless an app holds this, which
     * would stop network play from finding hosts on the LAN. */
    private android.net.wifi.WifiManager.MulticastLock mMulticastLock;
    static final int KEEP_LOGS = 10;

    private String mEngineLog;
    private Process mLogcat;

    /** Frames per second times ten, counted at each buffer swap (platform_sdl.c). */
    private static native int nativeFpsTenths();
    /** Switches stretch-to-fill while playing (platform_sdl.c). */
    private static native void nativeSetStretch(boolean on);
    private static native void nativeSetSmooth(boolean on);
    private static native void nativeSetVsync(boolean on);

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
    private AlertDialog mMenuDialog;

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
        String netName = getIntent().getStringExtra(EXTRA_NET_NAME);
        if (netName != null && !netName.isEmpty()) {
            args.add("--username");
            args.add(netName);
        }
        String netHost = getIntent().getStringExtra(EXTRA_NET_HOST);
        if (netHost != null && !netHost.isEmpty()) {
            args.add("--host");
            args.add(netHost);
        }
        if (mEngineLog != null) {
            args.add("--log");
            args.add(mEngineLog);
        }
        String dir = getIntent().getStringExtra(EXTRA_DATA_DIR);
        if (dir != null) {
            args.add(dir);
        }
        String[] out = args.toArray(new String[0]);
        if (mEngineLog != null) {
            appendLog(mEngineLog, "Arguments: " + String.join(" ", out) + "\n");
        }
        return out;
    }

    /* Resolution setting: render at a fixed height and let the display
     * hardware scale it to the screen. 0 keeps the native resolution. */
    @Override
    protected org.libsdl.app.SDLSurface createSDLSurface(android.content.Context context) {
        org.libsdl.app.SDLSurface surface = super.createSDLSurface(context);
        applyResolution(surface, getIntent().getIntExtra(EXTRA_RESOLUTION, 0));
        return surface;
    }

    private void applyResolution(android.view.SurfaceView surface, int height) {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int longSide = Math.max(m.widthPixels, m.heightPixels);
        int shortSide = Math.min(m.widthPixels, m.heightPixels);
        if (height > 0 && height < shortSide) {
            int width = Math.round(height * (float) longSide / shortSide);
            surface.getHolder().setFixedSize(width, height);
        } else {
            surface.getHolder().setSizeFromLayout();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        startDebugLogs();
        super.onCreate(savedInstanceState);
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(WIFI_SERVICE);
            mMulticastLock = wifi.createMulticastLock("sonicr-lan");
            mMulticastLock.setReferenceCounted(false);
            mMulticastLock.acquire();
        } catch (Exception e) {
            mMulticastLock = null;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mBrokenLibraries || mLayout == null) {
            return;
        }
        mTouch = new TouchControlsView(this);
        mLayout.addView(mTouch, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        android.content.SharedPreferences prefs = LauncherActivity.prefs(this);
        mTouch.setEnabledControls(prefs.getBoolean(LauncherActivity.KEY_TOUCH, true));
        mTouch.setAlpha(prefs.getInt(LauncherActivity.KEY_TOUCH_OPACITY, 100) / 100f);
        mTouch.setOnMenu(this::openGameMenu);

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
        showFps(getIntent().getBooleanExtra(EXTRA_FPS, false));
    }

    private void showFps(boolean on) {
        mFpsHandler.removeCallbacks(mFpsTick);
        mFps.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        if (on) {
            mFpsHandler.post(mFpsTick);
        }
    }

    /* Two-finger tap away from the touch controls: settings that
     * apply straight away, saved for next time too. */
    private void openGameMenu() {
        if ((mMenuDialog != null && mMenuDialog.isShowing())
                || (mQuitDialog != null && mQuitDialog.isShowing())) {
            return;
        }
        android.content.SharedPreferences prefs = LauncherActivity.prefs(this);
        setGamePaused(true);

        // A modern theme so switches and sliders draw properly over the game.
        android.content.Context ui = new android.view.ContextThemeWrapper(this, MENU_THEME);
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(ui);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);

        box.addView(menuSwitch(ui, "Touch controls", prefs.getBoolean(LauncherActivity.KEY_TOUCH, true), on -> {
            prefs.edit().putBoolean(LauncherActivity.KEY_TOUCH, on).apply();
            mTouch.setEnabledControls(on);
        }));

        android.widget.TextView opacityLabel = new android.widget.TextView(ui);
        opacityLabel.setPadding(0, (int) (8 * d), 0, 0);
        opacityLabel.setTextColor(android.graphics.Color.WHITE);
        box.addView(opacityLabel);
        android.widget.SeekBar opacity = new android.widget.SeekBar(ui);
        opacity.setMax(80);   // 20% to 100%
        opacity.setProgressTintList(android.content.res.ColorStateList.valueOf(MENU_GOLD));
        opacity.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x60FFFFFF));
        opacity.setThumbTintList(android.content.res.ColorStateList.valueOf(MENU_GOLD));
        int current = prefs.getInt(LauncherActivity.KEY_TOUCH_OPACITY, 100);
        opacity.setProgress(current - 20);
        opacityLabel.setText("Touch opacity: " + current + "%");
        opacity.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int v, boolean user) {
                int pct = v + 20;
                opacityLabel.setText("Touch opacity: " + pct + "%");
                mTouch.setAlpha(pct / 100f);
                prefs.edit().putInt(LauncherActivity.KEY_TOUCH_OPACITY, pct).apply();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) { }
        });
        box.addView(opacity);

        android.widget.TextView resLabel = new android.widget.TextView(ui);
        resLabel.setText("Resolution");
        resLabel.setPadding(0, (int) (8 * d), 0, 0);
        resLabel.setTextColor(android.graphics.Color.WHITE);
        box.addView(resLabel);
        final int[] heights = { 0, 1080, 720, 480 };
        final String[] resNames = { "Native", "1080p", "720p", "480p" };
        android.widget.RadioGroup res = new android.widget.RadioGroup(ui);
        res.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        int resNow = prefs.getInt(LauncherActivity.KEY_RESOLUTION, 0);
        for (int i = 0; i < heights.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(ui);
            rb.setId(i + 1);
            rb.setText(resNames[i]);
            rb.setTextColor(android.graphics.Color.WHITE);
            rb.setButtonTintList(checkedTint(MENU_GOLD, 0xB0FFFFFF));
            res.addView(rb);
            if (heights[i] == resNow) {
                res.check(i + 1);
            }
        }
        res.setOnCheckedChangeListener((g, id) -> {
            int h = heights[Math.max(0, id - 1)];
            prefs.edit().putInt(LauncherActivity.KEY_RESOLUTION, h).apply();
            if (mSurface != null) {
                applyResolution(mSurface, h);
            }
        });
        box.addView(res);

        box.addView(menuSwitch(ui, "Stretch to fill (16:9)", prefs.getBoolean(LauncherActivity.KEY_STRETCH, false), on -> {
            prefs.edit().putBoolean(LauncherActivity.KEY_STRETCH, on).apply();
            nativeSetStretch(on);
        }));
        box.addView(menuSwitch(ui, "Smooth textures", prefs.getBoolean(LauncherActivity.KEY_SMOOTH, false), on -> {
            prefs.edit().putBoolean(LauncherActivity.KEY_SMOOTH, on).apply();
            nativeSetSmooth(on);
        }));
        box.addView(menuSwitch(ui, "VSync", prefs.getBoolean(LauncherActivity.KEY_VSYNC, true), on -> {
            prefs.edit().putBoolean(LauncherActivity.KEY_VSYNC, on).apply();
            nativeSetVsync(on);
        }));
        box.addView(menuSwitch(ui, "Show FPS counter", mFps.getVisibility() == android.view.View.VISIBLE, on -> {
            prefs.edit().putBoolean(LauncherActivity.KEY_FPS, on).apply();
            showFps(on);
        }));

        android.widget.TextView note = new android.widget.TextView(ui);
        note.setText("Anti-aliasing needs a restart, so it's only in the launcher's Settings.");
        note.setPadding(0, (int) (10 * d), 0, 0);
        note.setTextColor(0xD0FFFFFF);
        box.addView(note);

        android.widget.ScrollView scroll = new android.widget.ScrollView(ui);
        scroll.addView(box);
        mMenuDialog = new AlertDialog.Builder(this, MENU_THEME)
            .setView(scroll)
            .setOnCancelListener(dlg -> setGamePaused(false))
            .setPositiveButton("Resume", (dlg, w) -> setGamePaused(false))
            .setNegativeButton("Exit game", (dlg, w) -> {
                mMenuDialog.dismiss();
                askToQuit();
            })
            .create();
        styleDialog(mMenuDialog, "Game menu");
        showImmersive(mMenuDialog);
    }

    private static final int MENU_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private static final int MENU_BLUE = 0xFF0A3CE6;   // electric blue
    private static final int MENU_GOLD = 0xFFFFC21A;   // yellowy gold
    private static final int MENU_GOLD_DIM = 0x80FFC21A;
    private static final int MENU_OFF = 0xFFB8C4E8;    // switch thumb when off

    private static android.content.res.ColorStateList checkedTint(int on, int off) {
        return new android.content.res.ColorStateList(
            new int[][] { { android.R.attr.state_checked }, { } }, new int[] { on, off });
    }

    /* Electric blue panel, gold ring, white text and buttons. */
    private void styleDialog(AlertDialog dialog, String title) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(MENU_BLUE);
        bg.setCornerRadius(22 * d);
        bg.setStroke((int) (4 * d), MENU_GOLD);
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(title);
        t.setTextColor(android.graphics.Color.WHITE);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding((int) (24 * d), (int) (18 * d), (int) (24 * d), (int) (4 * d));
        dialog.setCustomTitle(t);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(bg);
        }
        dialog.setOnShowListener(x -> {
            for (int which : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE }) {
                android.widget.Button b = dialog.getButton(which);
                if (b != null) {
                    b.setTextColor(android.graphics.Color.WHITE);
                }
            }
            android.widget.TextView msg = dialog.findViewById(android.R.id.message);
            if (msg != null) {
                msg.setTextColor(android.graphics.Color.WHITE);
            }
        });
    }

    private android.widget.Switch menuSwitch(android.content.Context ui, String label, boolean on,
                                             java.util.function.Consumer<Boolean> change) {
        android.widget.Switch sw = new android.widget.Switch(ui);
        sw.setText(label);
        sw.setChecked(on);
        sw.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        sw.setPadding(0, pad, 0, pad);
        sw.setTextColor(android.graphics.Color.WHITE);
        sw.setThumbTintList(checkedTint(MENU_GOLD, MENU_OFF));
        sw.setTrackTintList(checkedTint(MENU_GOLD_DIM, 0x60FFFFFF));
        sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
        return sw;
    }

    /* Shows a dialog without bringing back the status and navigation bars:
     * it starts unfocusable with the game's full-screen flags, then takes focus. */
    @SuppressWarnings("deprecation")
    private void showImmersive(AlertDialog dialog) {
        android.view.Window w = dialog.getWindow();
        if (w == null) {
            dialog.show();
            return;
        }
        w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
        dialog.show();
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    /* A real controller hides the touch controls until the screen is touched.
     * The phone's Back key asks whether to quit; a controller's B/Back still
     * reaches the game as Escape. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean pad = isGamepad(event);
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
        mQuitDialog = new AlertDialog.Builder(this, MENU_THEME)
            .setMessage("Are you sure you want to quit? Any unsaved progress will be lost.")
            .setCancelable(true)
            .setOnCancelListener(d -> setGamePaused(false))
            .setPositiveButton("Continue", (d, w) -> setGamePaused(false))
            .setNegativeButton("Exit game", (d, w) -> exitToLauncher())
            .create();
        styleDialog(mQuitDialog, "Quit Sonic R?");
        showImmersive(mQuitDialog);
    }

    /* Goes through SDL's own state machine so the pause isn't counted twice if
     * Android pauses the activity while the dialog is up. Resuming waits for
     * window focus, which comes back once the dialog closes. */
    private static void setGamePaused(boolean paused) {
        SDLActivity.mNextNativeState = paused ? NativeState.PAUSED : NativeState.RESUMED;
        SDLActivity.handleNativeState();
    }

    /* Debug logging: <data>/logs/game-<time>.log gets the engine's own output
     * (with device details on top) and logcat-<time>.txt gets this process's
     * Android log, which includes SDL messages and crash reports. */
    private void startDebugLogs() {
        String dataDir = getIntent().getStringExtra(EXTRA_DATA_DIR);
        if (!getIntent().getBooleanExtra(EXTRA_DEBUG_LOG, false) || dataDir == null) {
            return;
        }
        java.io.File logs = new java.io.File(dataDir, "logs");
        logs.mkdirs();
        pruneLogs(logs);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(new java.util.Date());
        mEngineLog = new java.io.File(logs, "game-" + stamp + ".log").getAbsolutePath();
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        appendLog(mEngineLog, "Sonic R for Android " + version + "\n"
            + "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            + " (" + android.os.Build.HARDWARE + "), Android " + android.os.Build.VERSION.RELEASE
            + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
            + "Resolution setting: " + getIntent().getIntExtra(EXTRA_RESOLUTION, 0) + "\n");
        try {
            String logcat = new java.io.File(logs, "logcat-" + stamp + ".txt").getAbsolutePath();
            mLogcat = new ProcessBuilder("logcat", "-v", "time",
                "--pid=" + android.os.Process.myPid(), "-f", logcat).start();
        } catch (java.io.IOException e) {
            appendLog(mEngineLog, "Could not start logcat: " + e + "\n");
        }
    }

    private static void pruneLogs(java.io.File logs) {
        java.io.File[] files = logs.listFiles();
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        // Two files per run; keep the newest runs.
        for (int i = KEEP_LOGS * 2; i < files.length; i++) {
            files[i].delete();
        }
    }

    private static void appendLog(String path, String text) {
        try (java.io.FileWriter w = new java.io.FileWriter(path, true)) {
            w.write(text);
        } catch (java.io.IOException ignored) {
        }
    }

    private void exitToLauncher() {
        // The game runs in its own process (":game" in the manifest), so ending
        // it here returns to the launcher and the next Play starts clean.
        Intent i = new Intent(this, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finishAndRemoveTask();
        if (mLogcat != null) {
            mLogcat.destroy();
        }
        if (mMulticastLock != null) {
            mMulticastLock.release();
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mTouch != null && isGamepad(event)) {
            mTouch.hideForGamepad();
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static boolean isGamepad(android.view.InputEvent event) {
        int source = event.getSource();
        boolean pad = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        InputDevice device = event.getDevice();
        return pad && device != null && org.libsdl.app.SDLControllerManager.looksLikeController(device);
    }
}
