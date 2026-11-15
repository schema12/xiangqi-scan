package com.zfdang.chess.openbook;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.readystatesoftware.sqliteasset.SQLiteAssetHelper;
import com.zfdang.chess.gamelogic.Zobrist;

import java.util.ArrayList;
import java.util.List;

/**
 * 激进专用开局库(独立资产 aggressive_book_v1.obk,独立加载,与原版桔库互不覆盖)。
 * 结构与 bhobk 兼容并附加质量列(tag/obj_eval/refuted/class/source/license/risk)。
 * 命中的候选仍然进入统一候选分析流程(绝不直接走棋,也不绕过 CandidateAnalyzer)。
 */
public class AggressiveOpenBook extends OpenBookBase {

    public static final String BOOK_NAME = "aggressive_book_v1";
    private static final String DATABASE_NAME = BOOK_NAME + ".obk";

    private final BHHelper helper;
    private SQLiteDatabase db = null;
    private final boolean available;

    private static final class BHHelper extends SQLiteAssetHelper {
        BHHelper(Context context) {
            super(context, DATABASE_NAME, null, 1);
            setForcedUpgrade();
        }
    }

    public AggressiveOpenBook(Context context) {
        if (!assetExists(context)) {
            this.helper = null;
            this.available = false;
            Log.w("AggressiveOpenBook", "aggressive book asset missing -> no-book mode");
            return;
        }
        this.helper = new BHHelper(context);
        this.available = true;
        // 1MB 级小库,拷贝/打开很快;仍放后台,避免任何首帧卡顿。
        new Thread(() -> {
            try {
                db = helper.getReadableDatabase();
                Log.d("AggressiveOpenBook", "aggressive book ready");
            } catch (Throwable t) {
                Log.e("AggressiveOpenBook", "open failed", t);
            }
        }, "AggressiveBookInit").start();
    }

    /** 资产是否存在(无资产 → 显式无库模式,安全回退到引擎搜索)。
     *  与 BHDatabase 相同:直接 open() 探测,规避 list() 对中文资产名的乱码。 */
    public static boolean assetExists(Context context) {
        if (context == null) return false;
        try (java.io.InputStream ignored = context.getAssets().open("databases/" + DATABASE_NAME)) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private synchronized SQLiteDatabase dbOrNull() {
        if (!available) return null;
        if (db != null) return db;
        try {
            db = helper.getReadableDatabase();
        } catch (Throwable t) {
            return null;
        }
        return db;
    }

    /**
     * 与 BHOpenBook.queryDetailed 同构:富查询 + 旧结构降级,确定性排序,
     * 返回多候选(统一决策流程里仍要经受限搜索复验)。
     */
    @Override
    protected List<BookData> get(long vkey) {
        List<BookData> list = new ArrayList<>();
        SQLiteDatabase d = dbOrNull();
        if (d == null) return list;
        String sql = "select vmove,vscore,vwin,vdraw,vlost,tag,obj_eval,refuted,class,source"
                + " from bhobk where vvalid = 1 and vkey = " + vkey
                + " order by vscore desc, vwin desc limit " + 8;
        try (Cursor cursor = d.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                BookData bd = new BookData();
                bd.setMove(Zobrist.getMoveFromVmove(cursor.getInt(cursor.getColumnIndexOrThrow("vmove"))));
                bd.setScore(cursor.getInt(cursor.getColumnIndexOrThrow("vscore")));
                bd.setWinRate(cursor.getDouble(cursor.getColumnIndexOrThrow("vwin")));
                bd.setDrawNum(cursor.getInt(cursor.getColumnIndexOrThrow("vdraw")));
                bd.setLoseNum(cursor.getInt(cursor.getColumnIndexOrThrow("vlost")));
                bd.setNote(cursor.getString(cursor.getColumnIndexOrThrow("tag")));
                bd.setSource(cursor.getString(cursor.getColumnIndexOrThrow("source")));
                list.add(bd);
            }
            return list;
        } catch (Exception enrichedFailed) {
            Log.d("AggressiveOpenBook", "enriched query failed -> legacy columns: " + enrichedFailed.getMessage());
        }
        String legacy = "select * from bhobk where vvalid = 1 and vkey = " + vkey
                + " order by vscore desc, vwin desc limit " + 8;
        try (Cursor cursor = d.rawQuery(legacy, null)) {
            while (cursor.moveToNext()) {
                BookData bd = new BookData();
                bd.setMove(Zobrist.getMoveFromVmove(cursor.getInt(cursor.getColumnIndexOrThrow("vmove"))));
                bd.setScore(cursor.getInt(cursor.getColumnIndexOrThrow("vscore")));
                bd.setWinRate(cursor.getDouble(cursor.getColumnIndexOrThrow("vwin")));
                bd.setDrawNum(cursor.getInt(cursor.getColumnIndexOrThrow("vdraw")));
                bd.setLoseNum(cursor.getInt(cursor.getColumnIndexOrThrow("vlost")));
                bd.setSource(BOOK_NAME);
                list.add(bd);
            }
        } catch (Exception e) {
            Log.d("AggressiveOpenBook", "get: " + e.getMessage());
        }
        return list;
    }

    @Override
    protected List<BookData> get(String fenCode, boolean onlyFinalPhase) {
        return new ArrayList<>();
    }

    @Override
    public void close() {
        if (helper != null) helper.close();
    }

    /** 富查询:多候选 + 质量元数据(供统一决策流程打分)。 */
    public List<BookCandidate> queryDetailed(long vkey, boolean redGo) {
        List<BookCandidate> list = new ArrayList<>();
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
                int objIdx = cursor.getColumnIndex("obj_eval");
                Integer objEval = objIdx >= 0 && !cursor.isNull(objIdx) ? cursor.getInt(objIdx) : null;
                int refIdx = cursor.getColumnIndex("refuted");
                boolean refuted = refIdx >= 0 && cursor.getInt(refIdx) != 0;
                String klass = cursor.getString(cursor.getColumnIndexOrThrow("class"));
                String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                list.add(new BookCandidate(move, score, winRate, tag, objEval, refuted, klass, source));
            }
            return list;
        } catch (Exception enrichedFailed) {
            Log.d("AggressiveOpenBook", "enriched query failed: " + enrichedFailed.getMessage());
        }
        try (Cursor cursor = d.rawQuery(
                "select * from bhobk where vvalid = 1 and vkey = " + vkey
                        + " order by vscore desc, vwin desc limit " + 8, null)) {
            while (cursor.moveToNext()) {
                String move = Zobrist.getMoveFromVmove(cursor.getInt(cursor.getColumnIndexOrThrow("vmove")));
                int score = cursor.getInt(cursor.getColumnIndexOrThrow("vscore"));
                double winRate = cursor.getDouble(cursor.getColumnIndexOrThrow("vwin"));
                list.add(new BookCandidate(move, score, winRate, null, null, false, null, BOOK_NAME));
            }
        } catch (Exception e) {
            Log.d("AggressiveOpenBook", "get: " + e.getMessage());
        }
        return list;
    }
}
