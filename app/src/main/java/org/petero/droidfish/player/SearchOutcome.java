package org.petero.droidfish.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of one completed UCI search, emitted when the engine prints bestmove.
 * Score fields keep Pikafish's "side to move" point of view:
 * cp/mate values are positive when the mover is better, wdl is {W,D,L} per-mille
 * for the mover. Consumers must not reinterpret them as red-side values.
 */
public final class SearchOutcome {
    public final int searchId;
    /** Engine bestmove in ICCS notation (a0-i9), or null/empty when unavailable. */
    public final String bestMove;
    /** "score cp" value, mover point of view. 0 when the score was mate. */
    public final int cpScore;
    /** "score mate N": >0 mover mates in N moves, <0 mover gets mated, 0 = no mate score. */
    public final int mateIn;
    /** {win,draw,loss} per-mille for the mover, or null when UCI_ShowWDL was off/absent. */
    public final int[] wdl;
    public final int depth;
    public final boolean upperBound;
    public final boolean lowerBound;
    /** PV in ICCS notation; first entry is bestMove when present. */
    public final List<String> pvUcci;
    public final long nodes;
    public final long timeMs;
    /** True when this outcome was produced after a GUI "stop" (interrupted search). */
    public final boolean afterStop;

    public SearchOutcome(int searchId, String bestMove, int cpScore, int mateIn, int[] wdl,
                         int depth, boolean upperBound, boolean lowerBound,
                         List<String> pvUcci, long nodes, long timeMs, boolean afterStop) {
        this.searchId = searchId;
        this.bestMove = bestMove;
        this.cpScore = cpScore;
        this.mateIn = mateIn;
        this.wdl = wdl == null ? null : wdl.clone();
        this.depth = depth;
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
        this.pvUcci = pvUcci == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(pvUcci));
        this.nodes = nodes;
        this.timeMs = timeMs;
        this.afterStop = afterStop;
    }

    public boolean hasBestMove() {
        return bestMove != null && bestMove.length() >= 4 && !"0000".equals(bestMove)
                && !"(none)".equals(bestMove);
    }
}
