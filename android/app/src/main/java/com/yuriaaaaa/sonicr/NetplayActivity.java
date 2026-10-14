package com.yuriaaaaa.sonicr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Connects to a netplay room before the game starts, in the game's process so
 * the tunnel lives and ends with the game. Hosting waits until the hub has the
 * room, joining until the local tunnel is up; then SonicRActivity starts with
 * --autohost or --autojoin and goes straight to the network lobby.
 */
public class NetplayActivity extends Activity {
    static final String EXTRA_MODE = "netplayMode";   // "host" or "join"
    static final String EXTRA_HUB = "netplayHub";
    static final String EXTRA_ROOM = "netplayRoom";   // room id to join
    static final String EXTRA_ROOM_NAME = "netplayRoomName";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private AlertDialog mDialog;
    private boolean mHost;
    private boolean mDone;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mHost = "host".equals(getIntent().getStringExtra(EXTRA_MODE));
        String hub = getIntent().getStringExtra(EXTRA_HUB);
        String room = getIntent().getStringExtra(EXTRA_ROOM);
        String roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);

        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TouchLayoutEditor.BLUE);
        bg.setCornerRadius(18 * d);
        bg.setStroke((int) (3 * d), TouchLayoutEditor.GOLD);
        box.setBackground(bg);
        TextView title = new TextView(this);
        title.setText(mHost ? "Hosting " + roomName : "Joining " + roomName);
        title.setTextColor(TouchLayoutEditor.GOLD);
        title.setTextSize(18);
        box.addView(title);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TouchLayoutEditor.GOLD));
        box.addView(spin);
        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setText("Connecting to the hub…");
        box.addView(mStatus);

        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel", (dlg, w) -> quit())
            .create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();

        if (!Netplay.available()) {
            fail("Online rooms aren't included in this build.");
            return;
        }
        if (mHost)
            Netplay.nativeStartHost(hub, roomName, Netplay.GAME_PORT);
        else
            Netplay.nativeStartJoin(hub, room, Netplay.GAME_PORT);
        mHandler.post(mPoll);
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (mDone) return;
            String e;
            while ((e = Netplay.nativePollEvent()) != null) {
                try {
                    JSONObject j = new JSONObject(e);
                    String type = j.optString("type");
                    switch (type) {
                        case "status":
                            mStatus.setText(j.optString("text"));
                            break;
                        case "peer":
                            mStatus.setText("Found the other player, connecting…");
                            break;
                        case "tunnel":
                            mStatus.setText(j.optBoolean("relay") ? "Connected through the hub's relay."
                                                                  : "Connected directly.");
                            break;
                        case "registered":
                            if (mHost) { startGame(); return; }
                            break;
                        case "proxy":
                            if (!mHost) { startGame(); return; }
                            break;
                        case "error":
                            fail(j.optString("text"));
                            return;
                        case "stopped":
                            fail("The room closed.");
                            return;
                    }
                } catch (Exception ignored) {
                }
            }
            mHandler.postDelayed(this, 150);
        }
    };

    private void startGame() {
        mDone = true;
        Intent game = new Intent(getIntent());
        game.setClass(this, SonicRActivity.class);
        game.putExtra(SonicRActivity.EXTRA_NET_PORT, Netplay.GAME_PORT);
        game.putExtra(SonicRActivity.EXTRA_NET_AUTO, mHost ? "host" : "join");
        game.putExtra(SonicRActivity.EXTRA_NET_HOST, mHost ? "" : "127.0.0.1");
        mDialog.dismiss();
        startActivity(game);
        finish();
    }

    private void fail(String message) {
        mDone = true;
        if (Netplay.available()) Netplay.nativeStop();
        mDialog.dismiss();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(mHost ? "Couldn't host" : "Couldn't join")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK", (dlg, w) -> quit())
            .show();
    }

    /* Back to the launcher; ending this process also ends the tunnel. */
    private void quit() {
        mDone = true;
        if (Netplay.available()) Netplay.nativeStop();
        startActivity(new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onBackPressed() {
        quit();
    }
}
