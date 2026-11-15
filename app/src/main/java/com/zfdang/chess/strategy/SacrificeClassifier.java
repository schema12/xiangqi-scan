package com.zfdang.chess.strategy;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.openbook.BookCandidate;

import java.util.List;

/**
 * Sacrifice classification (design F). Four classes:
 *   A BLUNDER   - no compensation: hard-rejected by the layer-1 gate.
 *   B TACTICAL  - objective tactical compensation visible in the candidate's PV
 *                 (captures back / forced sequence) within the first 4 plies.
 *   C PRESSURE  - no immediate forced win but sustained pressure: open lines,
 *                 palace invasion, tempo, discovered play; never requires checks.
 *   D TRAP      - only from an opening-book entry tagged trap, only when the risk
 *                 profile allows traps (high alpha, guard off). Explicitly labeled
 *                 as "depends on opponent mistakes" and never sold as a sound
 *                 opening or a "flying knife".
 *
 * The engine's own restricted-search result is the primary evidence; board material
 * accounting is only an auxiliary signal (design C.4 separation).
 */
public final class SacrificeClassifier {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_A_BLUNDER = 1;
    public static final int TYPE_B_TACTICAL = 2;
    public static final int TYPE_C_PRESSURE = 3;
    public static final int TYPE_D_TRAP = 4;

    public static final class Result {
        public int type = TYPE_NONE;
        /** [0,1] compensation strength (PV evidence); 0 for non-sacrifices. */
        public double compensation = 0.0;
        public boolean sacrifice = false;
        /** Worst-case material loss over opponent captures (auxiliary units). */
        public int netLoss = 0;
        /** True when the opponent can capture and safely withdraw the capturer. */
        public boolean opponentSafeExit = false;
        public String note = "";
    }

    private SacrificeClassifier() {
    }

    /**
     * @param preBoard      root position (mover to move)
     * @param m             candidate move (already known legal on preBoard)
     * @param pvUcci        candidate's own PV (mover's moves at even plies)
     * @param attackTotal   layer-2 attack score A(m)
     * @param book          opening-book entry when the candidate came from the book
     * @param profile       active profile (decides trap admissibility)
     */
    public static Result classify(Board preBoard, Move m, List<String> pvUcci,
                                  double attackTotal, BookCandidate book, RiskProfile profile) {
        Result r = new Result();
        boolean moverRed = preBoard.bRedGo;

        Board after = new Board(preBoard);
        after.doMove(m);

        r.netLoss = MaterialBalance.worstOpponentCaptureNet(after, moverRed);
        r.sacrifice = r.netLoss <= -StrategyParams.SACRIFICE_LOSS_MIN;
        if (!r.sacrifice) {
            r.note = "no-sacrifice";
            return r;
        }

        r.opponentSafeExit = opponentCanCaptureAndWithdraw(after, moverRed);

        if (book != null && "trap".equals(book.klass)) {
            if (profile.allowsTraps()) {
                r.type = TYPE_D_TRAP;
                r.compensation = 0.3;
                r.note = "book-trap;depends-on-opponent-mistake";
                return r;
            }
            r.type = TYPE_A_BLUNDER;
            r.note = "trap-not-allowed-by-profile";
            return r;
        }

        // B: objective tactical compensation inside the candidate's own PV.
        double recovered = moverCapturesInPv(preBoard, after, pvUcci, moverRed);
        double lossMagnitude = -r.netLoss;
        if (lossMagnitude > 0 && recovered >= StrategyParams.SAC_COMPENSATION_MIN * lossMagnitude) {
            r.type = TYPE_B_TACTICAL;
            r.compensation = Math.min(1.0, 0.5 + 0.5 * recovered / lossMagnitude);
            r.note = "pv-recovery";
            return r;
        }

        // C: sustained pressure without forced win. Checks are a bonus, never a requirement.
        boolean pressure = attackTotal >= 0.5 && hasOngoingPressure(pvUcci, moverRed);
        if (pressure && !r.opponentSafeExit) {
            r.type = TYPE_C_PRESSURE;
            r.compensation = Math.min(1.0, 0.4 + 0.3 * attackTotal);
            r.note = "sustained-pressure";
            return r;
        }

        r.type = TYPE_A_BLUNDER;
        r.note = r.opponentSafeExit ? "opponent-captures-and-withdraws" : "no-compensation-found";
        return r;
    }

    /** C-class needs visible ongoing play in the PV, not necessarily checks. */
    private static boolean hasOngoingPressure(List<String> pvUcci, boolean moverRed) {
        if (pvUcci == null || pvUcci.size() < 3) return false;
        return true; // a deep engine PV at limited loss is itself pressure evidence
    }

    /** Value (auxiliary units) the mover captures back within the first 4 PV plies. */
    static double moverCapturesInPv(Board preBoard, Board after, List<String> pvUcci, boolean moverRed) {
        Board cur = new Board(after);
        double sum = 0;
        int n = Math.min(4, pvUcci.size());
        for (int i = 0; i < n; i++) {
            Move mv = new Move(cur);
            if (!mv.fromUCCIString(pvUcci.get(i))) break;
            int victim = cur.getPieceByPosition(mv.toPosition.x, mv.toPosition.y);
            boolean ourPly = (i % 2) == 0;
            if (ourPly && com.zfdang.chess.gamelogic.Piece.isValid(victim)
                    && com.zfdang.chess.gamelogic.Piece.isRed(victim) != moverRed) {
                sum += StrategyParams.materialValue(victim);
            }
            cur.doMove(mv);
        }
        return sum;
    }

    /**
     * Opponent captures our hanging piece and the capturer is NOT reachable by any of
     * our pieces afterwards -> they win material "for free" (safety exit exists).
     */
    static boolean opponentCanCaptureAndWithdraw(Board afterMove, boolean moverRed) {
        boolean oppRed = !moverRed;
        for (int py = 0; py < Board.BOARD_PIECE_HEIGHT; py++) {
            for (int px = 0; px < Board.BOARD_PIECE_WIDTH; px++) {
                int p = afterMove.getPieceByPosition(px, py);
                if (!com.zfdang.chess.gamelogic.Piece.isValid(p) || com.zfdang.chess.gamelogic.Piece.isRed(p) != oppRed) {
                    continue;
                }
                for (com.zfdang.chess.gamelogic.Position t : com.zfdang.chess.gamelogic.Rule.PossibleToPositions(p, px, py, afterMove)) {
                    int victim = afterMove.getPieceByPosition(t.x, t.y);
                    if (!com.zfdang.chess.gamelogic.Piece.isValid(victim)
                            || com.zfdang.chess.gamelogic.Piece.isRed(victim) != moverRed) {
                        continue;
                    }
                    if (StrategyParams.materialValue(victim) < StrategyParams.materialValue(p)) {
                        continue; // bad trade for the opponent, not a free meal
                    }
                    Board s2 = new Board(afterMove);
                    s2.setPieceByPosition(px, py, com.zfdang.chess.gamelogic.Piece.EMPTY);
                    s2.setPieceByPosition(t.x, t.y, p);
                    boolean recapturable = MaterialBalance.squareReachableBy(s2, t.x, t.y, moverRed);
                    if (!recapturable) return true;
                }
            }
        }
        return false;
    }
}
