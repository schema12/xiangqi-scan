package com.zfdang.chess.strategy;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Piece;
import com.zfdang.chess.gamelogic.Position;
import com.zfdang.chess.gamelogic.Rule;

import java.util.List;

/**
 * Real material accounting on the physical board. This module is the ONLY place
 * where piece values are used, and its numbers are never compared with, or derived
 * from, engine centipawns (design C.4: CP / material / WDL are separate concepts).
 */
public final class MaterialBalance {

    private MaterialBalance() {
    }

    /** Net material (red minus black) on the board, in auxiliary value units. */
    public static int balance(Board b) {
        int sum = 0;
        for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                int p = b.getPieceByPosition(x, y);
                if (!Piece.isValid(p)) continue;
                int v = StrategyParams.materialValue(p);
                sum += Piece.isRed(p) ? v : -v;
            }
        }
        return sum;
    }

    /** True when some enemy piece can legally move to (x, y) (i.e. capture what sits there). */
    public static boolean squareReachableBy(Board b, int x, int y, boolean byRed) {
        for (int py = 0; py < Board.BOARD_PIECE_HEIGHT; py++) {
            for (int px = 0; px < Board.BOARD_PIECE_WIDTH; px++) {
                int p = b.getPieceByPosition(px, py);
                if (!Piece.isValid(p) || Piece.isRed(p) != byRed) continue;
                List<Position> targets = Rule.PossibleToPositions(p, px, py, b);
                for (Position t : targets) {
                    if (t.x == x && t.y == y) return true;
                }
            }
        }
        return false;
    }

    /**
     * Worst-case material outcome for the mover over all opponent captures on the
     * given board (opponent to move). Returns the minimum net from the mover's point
     * of view (negative = mover loses material). Approximation of a static exchange
     * evaluation: considers one capture plus one recapture, marked heuristic.
     */
    public static int worstOpponentCaptureNet(Board boardAfterMove, boolean moverRed) {
        boolean oppRed = !moverRed;
        int worst = 0;
        for (int py = 0; py < Board.BOARD_PIECE_HEIGHT; py++) {
            for (int px = 0; px < Board.BOARD_PIECE_WIDTH; px++) {
                int p = boardAfterMove.getPieceByPosition(px, py);
                if (!Piece.isValid(p) || Piece.isRed(p) != oppRed) continue;
                List<Position> targets = Rule.PossibleToPositions(p, px, py, boardAfterMove);
                for (Position t : targets) {
                    int victim = boardAfterMove.getPieceByPosition(t.x, t.y);
                    if (!Piece.isValid(victim) || Piece.isRed(victim) != moverRed) continue;
                    int lost = StrategyParams.materialValue(victim);
                    int attacker = StrategyParams.materialValue(p);
                    // Recapture approximation: can any mover piece land back on t afterwards?
                    Board s2 = new Board(boardAfterMove);
                    s2.setPieceByPosition(px, py, Piece.EMPTY);
                    s2.setPieceByPosition(t.x, t.y, p);
                    boolean recapturable = squareReachableBy(s2, t.x, t.y, moverRed);
                    int net = -lost + (recapturable ? attacker : 0);
                    if (net < worst) worst = net;
                }
            }
        }
        return worst;
    }
}
