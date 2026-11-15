package com.zfdang.chess.strategy;

import org.petero.droidfish.player.SearchOutcome;

/**
 * Normalizes UCI scores into a decision-safe representation (design C.4).
 *
 * Responsibilities:
 *  - keep "score cp" and "score mate N" strictly apart (mate distance is NOT cp);
 *  - keep everything in the mover (side-to-move) point of view. The baseline and
 *    every searchmoves candidate share the same root position, so all searches in
 *    one decision have the identical mover; deltas are computed in mover POV and
 *    never need a red/black sign flip. Red-POV conversion is provided only for
 *    logging/opening-book comparisons.
 */
public final class UciScoreNormalizer {

    /** Normalized engine evaluation for one search outcome. */
    public static final class DecisionScore {
        public final int cp;            // mover POV centipawns (internal units); 0 when mate
        public final int mateIn;        // >0 mover mates in N, <0 mover gets mated, 0 none
        public final int[] wdl;         // {W,D,L} per-mille mover POV, or null
        public final boolean valid;     // false -> unusable for decisions (conf gate rejects)
        public final boolean inexact;   // upper/lower bound present

        DecisionScore(int cp, int mateIn, int[] wdl, boolean valid, boolean inexact) {
            this.cp = cp;
            this.mateIn = mateIn;
            this.wdl = wdl;
            this.valid = valid;
            this.inexact = inexact;
        }
    }

    private UciScoreNormalizer() {
    }

    public static DecisionScore from(SearchOutcome o) {
        if (o == null || !o.hasBestMove()) {
            return new DecisionScore(0, 0, null, false, false);
        }
        boolean inexact = o.upperBound || o.lowerBound;
        return new DecisionScore(o.cpScore, o.mateIn, o.wdl, true, inexact);
    }

    /** cp delta (best - candidate) in mover POV; only meaningful when both are non-mate. */
    public static int deltaCp(DecisionScore best, DecisionScore cand) {
        if (!best.valid || !cand.valid) return Integer.MAX_VALUE;
        if (best.mateIn != 0 || cand.mateIn != 0) return Integer.MAX_VALUE;
        return best.cp - cand.cp;
    }

    /**
     * Convert a mover-POV score to red point of view (for logs and opening-book
     * obj_eval comparisons). Decision deltas never need this.
     */
    public static int toRedPov(int cpMoverPov, boolean redToMove) {
        return redToMove ? cpMoverPov : -cpMoverPov;
    }
}
