package com.yuriaaaaa.sonicr;

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

    private TouchControlsView mTouch;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "SDL2_mixer", "main" };
    }

    @Override
    protected String[] getArguments() {
        String dir = getIntent().getStringExtra(EXTRA_DATA_DIR);
        if (dir == null) {
            return new String[] { "--fullscreen" };
        }
        return new String[] { "--fullscreen", dir };
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
    }

    /* A real controller hides the touch controls until the screen is touched. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mTouch != null && isGamepad(event.getSource())) {
            mTouch.hideForGamepad();
        }
        return super.dispatchKeyEvent(event);
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
