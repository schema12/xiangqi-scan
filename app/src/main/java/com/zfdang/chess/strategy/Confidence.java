package com.zfdang.chess.strategy;

import org.petero.droidfish.player.SearchOutcome;

/**
 * Five-level credibility grading of a candidate search result (design G.2).
 * Only level >= BASIC may enter the final ranking; level FULL additionally unlocks
 * the WDL utility layer and B/C-class sacrifice replacement (guard mode).
 */
public final class Confidence {

    public static final int NONE = 1;         // timeout / no bestmove / parse failure
    public static final int INTERRUPTED = 2;  // stop-induced bestmove with a shallow line
    public static final int LIMITED = 3;      // complete but bounded or shallow; diagnostics only
    public static final int BASIC = 4;        // complete, unbounded, depth >= CONF_DEPTH_MIN
    public static final int FULL = 5;         // complete, unbounded, depth >= CONF_DEPTH_FULL

    private Confidence() {
    }

    public static int classify(SearchOutcome o) {
        if (o == null || !o.hasBestMove()) return NONE;
        // Mate announcements are proven within the search, at any depth.
        if (o.mateIn != 0) return FULL;
        if (o.afterStop) {
            return o.depth >= StrategyParams.CONF_INTERRUPTED_MIN_DEPTH ? LIMITED : INTERRUPTED;
        }
        if (o.upperBound || o.lowerBound) return LIMITED;
        if (o.depth >= StrategyParams.CONF_DEPTH_FULL && !o.pvUcci.isEmpty()) return FULL;
        if (o.depth >= StrategyParams.CONF_DEPTH_MIN && !o.pvUcci.isEmpty()) return BASIC;
        if (o.depth >= 1) return INTERRUPTED;
        return NONE;
    }

    public static String name(int level) {
        switch (level) {
            case FULL: return "FULL";
            case BASIC: return "BASIC";
            case LIMITED: return "LIMITED";
            case INTERRUPTED: return "INTERRUPTED";
            default: return "NONE";
        }
    }
}
