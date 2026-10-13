package com.yuriaaaaa.sonicr;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * Where the game data lives: Internal storage/SonicR when the app has all files
 * access, else the app's own external files folder.
 */
final class Paths {
    static final String FOLDER_NAME = "SonicR";

    private static File sAppDir;

    static void init(Context context) {
        if (sAppDir == null)
            sAppDir = context.getApplicationContext().getExternalFilesDir(null);
    }

    static boolean hasStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30)
            return Environment.isExternalStorageManager();
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static File shared() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER_NAME);
    }

    static File appDir() {
        return sAppDir;
    }

    static boolean looksLikeData(File dir) {
        return dir != null && new File(dir, "GENERAL/SONICR.BIT").isFile();
    }

    /** The data folder in use: the shared one if it's readable and has data (or the app one doesn't). */
    static File data(Context context) {
        init(context);
        boolean access = hasStorageAccess(context);
        if (access && looksLikeData(shared()))
            return shared();
        if (looksLikeData(sAppDir))
            return sAppDir;
        return access || sAppDir == null ? shared() : sAppDir;
    }

    static File mods(Context context) {
        return new File(data(context), "mods");
    }
}
