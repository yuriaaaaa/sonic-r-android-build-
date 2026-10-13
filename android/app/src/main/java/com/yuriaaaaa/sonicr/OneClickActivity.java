package com.yuriaaaaa.sonicr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles GameBanana's 1-click install links (sonicrmm:https://gamebanana.com/mmdl/FILE,Mod,MOD),
 * and GameBanana mod pages shared to the app, by opening the mod's page in the launcher.
 */
public class OneClickActivity extends Activity {
    private static final Pattern ONE_CLICK = Pattern.compile("mmdl/(\\d+),Mod,(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOD_PAGE = Pattern.compile("gamebanana\\.com/mods/(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModManager.init(this);
        Intent intent = getIntent();
        String text = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            text = data != null ? Uri.decode(data.toString()) : null;
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        }

        int modId = 0, fileId = 0;
        if (text != null) {
            Matcher oneClick = ONE_CLICK.matcher(text);
            Matcher page = MOD_PAGE.matcher(text);
            try {
                if (oneClick.find()) {
                    fileId = Integer.parseInt(oneClick.group(1));
                    modId = Integer.parseInt(oneClick.group(2));
                } else if (page.find()) {
                    modId = Integer.parseInt(page.group(1));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (modId == 0) {
            Toast.makeText(this, "That isn't a GameBanana mod link", Toast.LENGTH_LONG).show();
        } else {
            Intent details = new Intent(this, ModDetailsActivity.class);
            details.putExtra(ModDetailsActivity.EXTRA_MOD_ID, modId);
            if (fileId != 0)
                details.putExtra(ModDetailsActivity.EXTRA_FILE_ID, fileId);
            // Open in the launcher's own task, not the browser's.
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(details);
        }
        finish();
    }
}
