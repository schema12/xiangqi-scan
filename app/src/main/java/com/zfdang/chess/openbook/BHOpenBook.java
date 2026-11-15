package com.zfdang.chess.openbook;


import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.zfdang.chess.gamelogic.Zobrist;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BHOpenBook extends OpenBookBase {

    private Connection connection;

    private String name;
    private BHDatabase bhDB = null;
    SQLiteDatabase db = null;
    /** False when the database asset is missing: explicit "no book" mode, no silent retries. */
    private boolean available = false;

    // 首次资产拷贝跨实例串行化：GameController 与 GameActivity 各持一个实例，
    // 并发拷贝同一文件会互相破坏（B 看到半成品→判损坏→删除 A 正在写的文件）
    private static final Object sOpenLock = new Object();

    public BHOpenBook(Context context){
        // database asset file is managed by BHDatabase class
        bhDB = new BHDatabase(context);

        name = bhDB.OPENBOOK_NAME;
        // 修复静默失败:资产缺失时直接进入"无库模式"(查询返回空,UI 可明示),
        // 不再后台无限懒重试。
        if (!BHDatabase.assetExists(context)) {
            available = false;
            Log.w("BHOpenBook", "openbook asset missing -> running in no-book mode");
            return;
        }
        available = true;
        // 首次打开需从 assets 解压拷贝 120MB 资产，耗时可达数分钟；
        // 必须放在后台线程，否则主线程阻塞 5s 即 ANR 被系统杀死（拷贝中断还会造成库文件损坏循环）
        // 拷贝完成前查询走 dbOrNull() 懒重试，未就绪时返回空列表正常跳过
        new Thread(() -> {
            synchronized (sOpenLock) {
                try {
                    long t0 = System.currentTimeMillis();
                    db = bhDB.getReadableDatabase();
                    Log.d("BHOpenBook", "openbook ready in " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable t) {
                    Log.e("BHOpenBook", "open failed, will retry lazily", t);
                    return;
                }
                try {
                    // 大库（120MB）vkey 无索引，查询为全表扫描；后台一次性建索引（IF NOT EXISTS 幂等）
                    long t1 = System.currentTimeMillis();
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bhobk_vkey ON bhobk(vkey, vvalid)");
                    Log.d("BHOpenBook", "vkey index ready in " + (System.currentTimeMillis() - t1) + "ms");
                } catch (Throwable t) {
                    Log.e("BHOpenBook", "create index failed", t);
                }
            }
        }, "BHOpenBookInit").start();
    }

    /** True when a database asset exists and opening is possible. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Enriched query for the unified strategy flow: returns multiple candidates with
     * optional quality metadata (tag/objEval/refuted/class). When the shipped database
     * predates the enriched schema the query degrades to legacy columns and every
     * candidate is marked unverified (metadata null) — it then faces the same
     * restricted-search verification as ordinary moves. Ordering is deterministic
     * (score desc, win desc); no random comparator is involved.
     */
    public java.util.List<BookCandidate> queryDetailed(long vkey, boolean redGo) {
        java.util.List<BookCandidate> list = new java.util.ArrayList<>();
        if (!available) return list;
        SQLiteDatabase d = dbOrNull();
        if (d == null) return list;
        String sql = "select vmove,vscore,vwin,vdraw,vlost,tag,obj_eval,refuted,class,source"
                + " from bhobk where vvalid = 1 and vkey = " + vkey
                + " order by vscore desc, vwin desc limit " + 8;
        try (Cursor cursor = d.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String move = Zobrist.getMoveFromVmove(cursor.getInt(cursor.getColumnIndexOrThrow("vmove")));
                int score = cursor.getInt(cursor.getColumnIndexOrThrow("vscore"));
                double winRate = cursor.getDouble(cursor.getColumnIndexOrThrow("vwin"));
                String tag = cursor.getString(cursor.getColumnIndexOrThrow("tag"));
                int objEvalIdx = cursor.getColumnIndex("obj_eval");
                Integer objEval = objEvalIdx >= 0 && !cursor.isNull(objEvalIdx) ? cursor.getInt(objEvalIdx) : null;
                int refutedIdx = cursor.getColumnIndex("refuted");
                boolean refuted = refutedIdx >= 0 && cursor.getInt(refutedIdx) != 0;
                String klass = cursor.getString(cursor.getColumnIndexOrThrow("class"));
                String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                list.add(new BookCandidate(move, score, winRate, tag, objEval, refuted, klass, source));
            }
            return list;
        } catch (Exception enrichedFailed) {
            Log.d("BHOpenBook", "enriched query failed, falling back to legacy columns: " + enrichedFailed.getMessage());
        }
        // Legacy schema fallback: statistics columns only, metadata stays unverified.
        String legacy = "select * from bhobk where vvalid = 1 and vkey = " + vkey
                + " order by vscore desc, vwin desc limit " + 8;
        try (Cursor cursor = d.rawQuery(legacy, null)) {
            while (cursor.moveToNext()) {
                String move = Zobrist.getMoveFromVmove(cursor.getInt(cursor.getColumnIndexOrThrow("vmove")));
                int score = cursor.getInt(cursor.getColumnIndexOrThrow("vscore"));
                double winRate = cursor.getDouble(cursor.getColumnIndexOrThrow("vwin"));
                list.add(new BookCandidate(move, score, winRate, null, null, false, null, this.name));
            }
        } catch (Exception e) {
            Log.d("BHOpenBook", "get: " + e.getMessage());
        }
        return list;
    }


    /** 懒重试获取数据库（构造失败时）；sOpenLock 防止与后台首次拷贝并发 */
    private synchronized SQLiteDatabase dbOrNull() {
        if (db != null) return db;
        synchronized (sOpenLock) {
            if (db != null) return db;
            try { db = bhDB.getReadableDatabase(); } catch (Throwable t) { return null; }
            return db;
        }
    }

    @Override
    protected List<BookData> get(long vkey) {
        List<BookData> list = new ArrayList<>();
        SQLiteDatabase d = dbOrNull();
        if (d == null) return list;
        try {
            String sql = String.format("select * from bhobk where vvalid = 1 and vkey = %d order by vscore desc, vwin desc limit 5", vkey);
            Log.d("BHOpenBook", "get: " + sql);
            try (Cursor cursor = d.rawQuery(sql, null)) {
                // Missing columns are reported by the query failure handler; the cursor always closes.
                while (cursor.moveToNext()) {
                    BookData bd = new BookData();
                    int vmove = cursor.getInt(cursor.getColumnIndexOrThrow("vmove"));
                    String move = Zobrist.getMoveFromVmove(vmove);
                    bd.setMove(move);
                    bd.setScore(cursor.getInt(cursor.getColumnIndexOrThrow("vscore")));
                    bd.setWinRate(cursor.getDouble(cursor.getColumnIndexOrThrow("vwin")));
                    bd.setDrawNum(cursor.getInt(cursor.getColumnIndexOrThrow("vdraw")));
                    bd.setLoseNum(cursor.getInt(cursor.getColumnIndexOrThrow("vlost")));
                    bd.setNote(cursor.getString(cursor.getColumnIndexOrThrow("vmemo")));
                    bd.setSource(this.name);
                    list.add(bd);
                }
            }
        } catch (Exception e) {
            Log.d("BHOpenBook", "get: " + e.getMessage());
            Log.d("BHOpenBook", "get: " + e.getStackTrace());
        }
        return list;
    }

    @Override
    protected List<BookData> get(String fenCode, boolean onlyFinalPhase) {
        return Collections.emptyList();
    }


    @Override
    public void close() {
        bhDB.close();
    }
}
