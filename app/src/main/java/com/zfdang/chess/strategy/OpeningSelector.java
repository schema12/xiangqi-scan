package com.zfdang.chess.strategy;

import com.zfdang.chess.openbook.BookCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Opening-book candidate filtering (design H.4/H.5). The selector NEVER decides a
 * move by itself: everything it returns must pass the same restricted-search
 * verification and layer gates as ordinary legal moves (unified decision flow).
 *
 * Cheap pre-search filters:
 *  - refuted lines are dropped forever;
 *  - offbeat_loss class is dropped (plain offbeat losing lines);
 *  - trap class only when the profile allows traps;
 *  - objectively bad objEval (when present) is dropped against the delta envelope.
 */
public final class OpeningSelector {

    private OpeningSelector() {
    }

    /**
     * @param raw       book candidates for the queried position (may carry absent metadata)
     * @param profile   active profile (trap admissibility)
     * @param deltaEff  effective objective-loss envelope, mover POV cp
     * @param moverRed  mover color, used to convert red-POV objEval into mover POV
     */
    public static List<BookCandidate> filterForMover(List<BookCandidate> raw, RiskProfile profile,
                                                     double deltaEff, boolean moverRed) {
        List<BookCandidate> out = new ArrayList<>();
        if (raw == null) return out;
        for (BookCandidate bc : raw) {
            if (bc == null || bc.ucci == null || bc.ucci.length() != 4) continue;
            if (bc.isRefuted()) continue;
            if ("offbeat_loss".equals(bc.klass)) continue;
            if ("trap".equals(bc.klass) && !profile.allowsTraps()) continue;
            if (bc.objEval != null) {
                int objMoverPov = moverRed ? bc.objEval : -bc.objEval;
                if (-objMoverPov > deltaEff) continue;
            }
            out.add(bc);
        }
        return out;
    }
}
