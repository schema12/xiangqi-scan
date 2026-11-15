package com.zfdang.chess.openbook;

/**
 * Opening-book routing (增量开发任务 §四). Exactly one book is queried per move:
 *
 *   总开关关闭            -> ORIGINAL(原版开局库)
 *   Risk = 0              -> ORIGINAL
 *   策略开、激进库关       -> ORIGINAL
 *   策略开、Risk>0、激进库开 -> AGGRESSIVE(激进专用开局库)
 *
 * Both books are separate assets, loaded independently and never merged.
 * An unavailable AGGRESSIVE source safely degrades to ORIGINAL (router only
 * routes when the aggressive loader reports availability).
 */
public final class OpeningBookRouter {

    public enum Book { ORIGINAL, AGGRESSIVE }

    private OpeningBookRouter() {
    }

    public static Book select(boolean strategyEnabled, int riskLevel,
                              boolean aggressiveBookEnabled, boolean aggressiveBookAvailable) {
        if (!strategyEnabled) return Book.ORIGINAL;
        if (riskLevel <= 0) return Book.ORIGINAL;
        if (!aggressiveBookEnabled) return Book.ORIGINAL;
        if (!aggressiveBookAvailable) return Book.ORIGINAL; // 安全回退:库缺失/损坏
        return Book.AGGRESSIVE;
    }
}
