package com.zfdang.chess.strategy;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.gamelogic.Piece;
import com.zfdang.chess.gamelogic.Position;
import com.zfdang.chess.gamelogic.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based attack features (design D layer-2). Every feature is computed from the
 * physical board (Rule/Board/Piece) plus the candidate's own PV; nothing here uses
 * centipawns, WDL or randomness. Each feature is normalized to [0, 1].
 *
 * Honest-heuristic notes: OpenFile (mobility proxy for non-sliders), Discovered
 * (single-step X-ray diff) and Complexity are approximations, marked as such below.
 */
public final class AttackFeatureEngine {

    /** The nine design features plus the layer-5 complexity heuristic. */
    public static final class Features {
        public double checkChain;    // checks within the first 4 PV plies / 4
        public double mateThreat;    // 1.0 when the candidate search announced mate for the mover
        public double kingZone;      // mover pieces in the enemy palace ring / 8
        public double doubleAttack;  // moved piece attacks >= 2 enemy targets
        public double pinSkewer;     // moved slider X-rays two enemy pieces on one line
        public double discovered;    // another slider's line targets increased (heuristic)
        public double openFile;      // open lines / mobility proxy (heuristic)
        public double tempo;         // check, or forces the single legal reply
        public double defenderPull;  // enemy advisors/elephants pulled out of palace over PV
        public double complexity;    // heuristic proxy for fight potential, NOT proven win odds

        public double totalAttack() {
            return StrategyParams.W_CHECK_CHAIN * checkChain
                    + StrategyParams.W_MATE_THREAT * mateThreat
                    + StrategyParams.W_KING_ZONE * kingZone
                    + StrategyParams.W_DOUBLE_ATTACK * doubleAttack
                    + StrategyParams.W_PIN_SKEWER * pinSkewer
                    + StrategyParams.W_DISCOVERED * discovered
                    + StrategyParams.W_OPEN_FILE * openFile
                    + StrategyParams.W_TEMPO * tempo
                    + StrategyParams.W_DEFENDER_PULL * defenderPull;
        }
    }

    private AttackFeatureEngine() {
    }

    /** Cheap prescreen used for candidate ordering, without any search. */
    public static double quickScore(Board board, Move m) {
        Board after = new Board(board);
        after.doMove(m);
        double s = 0;
        if (givesCheck(after, !board.bRedGo)) s += 0.5;
        int victim = board.getPieceByPosition(m.toPosition.x, m.toPosition.y);
        if (Piece.isValid(victim)) s += 0.4 * Math.min(1.0, StrategyParams.materialValue(victim) / 900.0);
        if (inEnemyPalaceRing(m.toPosition.x, m.toPosition.y, board.bRedGo)) s += 0.3;
        if (countTargetsFrom(after, m.piece, m.toPosition.x, m.toPosition.y) >= 2) s += 0.3;
        if ((m.piece == Piece.WMA || m.piece == Piece.BMA) && crossesRiver(m, board.bRedGo)) s += 0.15;
        return Math.min(1.0, s);
    }

    /** Full feature vector for one candidate, given its own restricted-search PV. */
    public static Features compute(Board preBoard, String candidateUcci, List<String> pvUcci) {
        Features f = new Features();
        Move m = new Move(preBoard);
        if (!m.fromUCCIString(candidateUcci)) return f;
        boolean moverRed = preBoard.bRedGo;
        boolean moverWasRedToMove = moverRed;

        Board after = new Board(preBoard);
        after.doMove(m);

        // --- direct features on the resulting position ---
        f.mateThreat = 0.0; // filled by the caller from the search outcome mateIn
        f.kingZone = kingZoneOccupancy(after, moverRed);
        f.doubleAttack = countTargetsFrom(after, m.piece, m.toPosition.x, m.toPosition.y) >= 2 ? 1.0 : 0.0;
        f.pinSkewer = sliderXray(after, m.piece, m.toPosition.x, m.toPosition.y);
        f.discovered = discoveredGain(preBoard, after, moverRed, m);
        f.openFile = openLines(after, m);
        f.tempo = givesCheck(after, moverWasRedToMove) ? 1.0
                : (legalMoveCount(after, !moverRed) == 1 ? 0.7 : 0.0);

        // --- PV-derived features (first 4 plies) ---
        List<Board> trace = replay(after, pvUcci, 4);
        f.checkChain = Math.min(1.0, checksByMoverInPv(trace, moverRed) / 4.0);
        f.defenderPull = defenderPullInPv(trace, moverRed);
        f.complexity = complexityDelta(preBoard, after);

        return f;
    }

    // ---------------------------------------------------------------- helpers

    /** Replay up to maxPlies PV moves; returns snapshots AFTER each applied ply. */
    public static List<Board> replay(Board start, List<String> pv, int maxPlies) {
        List<Board> out = new ArrayList<>();
        Board cur = new Board(start);
        int n = Math.min(maxPlies, pv.size());
        for (int i = 0; i < n; i++) {
            Move mv = new Move(cur);
            if (!mv.fromUCCIString(pv.get(i))) break;
            cur.doMove(mv);
            Board snap = new Board(cur);
            out.add(snap);
        }
        return out;
    }

    /** True when the king of {@code redKing} color stands attacked on {@code board}. */
    public static boolean kingInDanger(Board board, boolean redKing) {
        int king = redKing ? Piece.WSHUAI : Piece.BJIANG;
        Position kp = Rule.findJiangShuaiPos(king, board);
        if (kp == null) return false;
        return Rule.isJiangShuaiInDanger(king, kp, board);
    }

    static boolean givesCheck(Board boardAfterMove, boolean moverRed) {
        // After the mover's move it is the opponent's turn; check = enemy king in danger.
        return kingInDanger(boardAfterMove, !moverRed);
    }

    static boolean inEnemyPalaceRing(int x, int y, boolean moverRed) {
        if (x < 2 || x > 6) return false;
        // Red mover attacks the black palace at the top (y 0..2); ring adds one row.
        return moverRed ? (y >= 0 && y <= 3) : (y >= 6 && y <= 9);
    }

    private static boolean crossesRiver(Move m, boolean moverRed) {
        return moverRed ? m.toPosition.y <= 4 : m.toPosition.y >= 5;
    }

    static int legalMoveCount(Board b, boolean sideRed) {
        int count = 0;
        for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                int p = b.getPieceByPosition(x, y);
                if (!Piece.isValid(p) || Piece.isRed(p) != sideRed) continue;
                count += Rule.PossibleToPositions(p, x, y, b).size();
            }
        }
        return count;
    }

    /** Enemy pieces the piece on (x,y) can capture next move (approximation of threats). */
    static int countTargetsFrom(Board b, int piece, int x, int y) {
        if (!Piece.isValid(piece)) return 0;
        boolean red = Piece.isRed(piece);
        int count = 0;
        for (Position t : Rule.PossibleToPositions(piece, x, y, b)) {
            int q = b.getPieceByPosition(t.x, t.y);
            if (Piece.isValid(q) && Piece.isRed(q) != red) count++;
        }
        return count;
    }

    /** 1.0 when the moved rook faces two enemy pieces on an otherwise open line. */
    private static double sliderXray(Board b, int piece, int x, int y) {
        if (piece != Piece.WJU && piece != Piece.BJU) return 0.0;
        boolean red = Piece.isRed(piece);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int cx = x + d[0], cy = y + d[1];
            boolean firstFound = false;
            int firstColor = 0;
            while (cx >= 0 && cx < Board.BOARD_PIECE_WIDTH && cy >= 0 && cy < Board.BOARD_PIECE_HEIGHT) {
                int p = b.getPieceByPosition(cx, cy);
                if (Piece.isValid(p)) {
                    if (!firstFound) {
                        firstFound = true;
                        firstColor = Piece.isRed(p) ? 1 : -1;
                    } else {
                        int secondColor = Piece.isRed(p) ? 1 : -1;
                        if (firstColor != (red ? 1 : -1) && secondColor != (red ? 1 : -1)
                                && firstColor != secondColor) {
                            return 1.0; // enemy piece pinned/skewered against another enemy piece
                        }
                        break;
                    }
                }
                cx += d[0];
                cy += d[1];
            }
        }
        return 0.0;
    }

    /** Line-attack target count for one our rook/cannon (used for discovered diff). */
    private static int lineTargets(Board b, int x, int y, boolean red) {
        int piece = b.getPieceByPosition(x, y);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int count = 0;
        for (int[] d : dirs) {
            int cx = x + d[0], cy = y + d[1];
            boolean screened = false;
            while (cx >= 0 && cx < Board.BOARD_PIECE_WIDTH && cy >= 0 && cy < Board.BOARD_PIECE_HEIGHT) {
                int p = b.getPieceByPosition(cx, cy);
                if (!Piece.isValid(p)) {
                    cx += d[0];
                    cy += d[1];
                    continue;
                }
                if (!screened) {
                    if (Piece.isRed(p) != red) count++;
                    screened = true;
                    if (piece == Piece.WPAO || piece == Piece.BPAO) {
                        cx += d[0];
                        cy += d[1];
                        continue;
                    }
                    break;
                }
                // behind the screen/first piece
                if (Piece.isRed(p) != red) count++;
                break;
            }
        }
        return count;
    }

    private static double discoveredGain(Board pre, Board after, boolean moverRed, Move m) {
        int gain = 0;
        for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                int p = pre.getPieceByPosition(x, y);
                if (!Piece.isValid(p) || Piece.isRed(p) != moverRed) continue;
                if (p != Piece.WJU && p != Piece.BJU && p != Piece.WPAO && p != Piece.BPAO) continue;
                if (x == m.fromPosition.x && y == m.fromPosition.y) continue;
                if (lineTargets(after, x, y, moverRed) > lineTargets(pre, x, y, moverRed)) gain++;
            }
        }
        return gain >= 1 ? 1.0 : 0.0;
    }

    private static double openLines(Board after, Move m) {
        boolean slider = (m.piece == Piece.WJU || m.piece == Piece.BJU
                || m.piece == Piece.WPAO || m.piece == Piece.BPAO);
        if (!slider) {
            return Math.min(1.0, Rule.PossibleToPositions(m.piece, m.toPosition.x, m.toPosition.y, after).size() / 16.0);
        }
        int empty = 0;
        for (int ix = 0; ix < Board.BOARD_PIECE_WIDTH; ix++) {
            if (ix != m.toPosition.x && after.getPieceByPosition(ix, m.toPosition.y) == Piece.EMPTY) empty++;
        }
        for (int iy = 0; iy < Board.BOARD_PIECE_HEIGHT; iy++) {
            if (iy != m.toPosition.y && after.getPieceByPosition(m.toPosition.x, iy) == Piece.EMPTY) empty++;
        }
        return Math.min(1.0, empty / 12.0);
    }

    private static double kingZoneOccupancy(Board b, boolean moverRed) {
        int count = 0;
        for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                int p = b.getPieceByPosition(x, y);
                if (!Piece.isValid(p) || Piece.isRed(p) != moverRed) continue;
                if (inEnemyPalaceRing(x, y, moverRed)) count++;
            }
        }
        return Math.min(1.0, count / 8.0);
    }

    private static double checksByMoverInPv(List<Board> trace, boolean moverRed) {
        // trace[i] is the position after PV ply i. Our plies are even indices (0,2,...).
        int checks = 0;
        for (int i = 0; i < trace.size(); i += 2) {
            if (kingInDanger(trace.get(i), !moverRed)) checks++;
        }
        return checks;
    }

    private static double defenderPullInPv(List<Board> trace, boolean moverRed) {
        int pulls = 0;
        Board prev = null;
        int index = 0;
        for (Board snap : trace) {
            boolean enemyPly = (index % 2) == 1; // odd plies are the opponent's moves
            if (enemyPly && prev != null) {
                // heuristic: enemy defenders that left their palace during their own move
                int before = defendersInPalace(prev, !moverRed);
                int afterN = defendersInPalace(snap, !moverRed);
                if (afterN < before) pulls += before - afterN;
            }
            prev = snap;
            index++;
        }
        return Math.min(1.0, pulls / 2.0);
    }

    private static int defendersInPalace(Board b, boolean red) {
        int count = 0;
        int yLo = red ? 7 : 0, yHi = red ? 9 : 2;
        for (int y = yLo; y <= yHi; y++) {
            for (int x = 3; x <= 5; x++) {
                int p = b.getPieceByPosition(x, y);
                if (!Piece.isValid(p)) continue;
                if (Piece.isRed(p) != red) continue;
                if (p == Piece.WSHI || p == Piece.BSHI || p == Piece.WXIANG || p == Piece.BXIANG) count++;
            }
        }
        return count;
    }

    private static double complexityDelta(Board pre, Board after) {
        // Heuristic: fewer total legal moves after the move means simplification.
        int before = legalMoveCount(pre, pre.bRedGo) + legalMoveCount(pre, !pre.bRedGo);
        int afterN = legalMoveCount(after, after.bRedGo) + legalMoveCount(after, !after.bRedGo);
        double v = 0.5 + (afterN - before) / 12.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
