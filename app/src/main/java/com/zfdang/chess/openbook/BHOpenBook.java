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

    // 首次资产拷贝跨实例串行化：GameController 与 GameActivity 各持一个实例，
    // 并发拷贝同一文件会互相破坏（B 看到半成品→判损坏→删除 A 正在写的文件）
    private static final Object sOpenLock = new Object();

    public BHOpenBook(Context context){
        // database asset file is managed by BHDatabase class
        bhDB = new BHDatabase(context);

        name = bhDB.OPENBOOK_NAME;
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
