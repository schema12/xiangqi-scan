package com.zfdang.chess.openbook;

import android.content.Context;

import com.readystatesoftware.sqliteasset.SQLiteAssetHelper;

// https://github.com/jgilfelt/android-sqlite-asset-helper
public class BHDatabase extends SQLiteAssetHelper {

//    CREATE TABLE bhobk([id] INTEGER PRIMARY KEY AUTOINCREMENT,
//                       [vkey] INTEGER,
//                       [vmove] INTEGER,
//                       [vscore] INTEGER,
//                       [vwin] INTEGER,
//                       [vdraw] INTEGER,
//                       [vlost] INTEGER,
//                       [vvalid] INTEGER,
//                       [vmemo] BLOB,
//                       [vindex] INTEGER)

    public static final String OPENBOOK_NAME = "桔库09.09.2023精修库";
    private static final String DATABASE_NAME = "桔库09.09.2023精修库.obk";
    private static final int DATABASE_VERSION = 15;

    public BHDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);

        // Upgrades via overwrite
        setForcedUpgrade();
    }

    /**
     * SQLiteAssetHelper loads the database from assets/databases/<name>. When the
     * asset is absent (e.g. a fork that stripped the 74MB binary), getReadableDatabase()
     * throws and the old code silently retried forever. Callers must check this first
     * and run in an explicit "no book" mode instead of failing silently.
     *
     * Detection uses a direct AssetManager.open() probe rather than list(): some ROMs
     * mangle non-ASCII asset names in list() output (observed on vivo), while open()
     * with the exact name always works — upstream's production app relies on open().
     */
    public static boolean assetExists(Context context) {
        if (context == null) return false;
        try (java.io.InputStream ignored = context.getAssets().open("databases/" + DATABASE_NAME)) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}