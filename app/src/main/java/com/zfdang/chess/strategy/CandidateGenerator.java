package com.zfdang.chess.strategy;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.gamelogic.Position;
import com.zfdang.chess.gamelogic.Rule;
import com.zfdang.chess.openbook.BookCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Candidate generation (design E.1). The candidate universe is ALWAYS the full set
 * of legal moves for the side to move, generated with Rule.PossibleToPositions.
 * The baseline PV contributes only its FIRST move (PV ply 2 belongs to the opponent
 * and must never become one of our candidates). Opening-book moves are merged only
 * when they exist in the legal set for this exact position. Dedup key = UCCI string.
 */
public final class CandidateGenerator {

    /** One candidate ready for restricted verification. */
    public static final class Candidate {
        public final Move move;
        public final String ucci;
        public final BookCandidate book; // null for plain legal moves
        public final double quickScore;

        Candidate(Move move, String ucci, BookCandidate book, double quickScore) {
            this.move = move;
            this.ucci = ucci;
            this.book = book;
            this.quickScore = quickScore;
        }
    }

    private CandidateGenerator() {
    }

    /**
     * @param rootBoard        position with the mover to move (copy owned by the caller)
     * @param baselineBestUcci baseline bestmove (PV first move only)
     * @param bookCands        opening-book candidates for this exact position
     * @param profile          active risk profile (determinates K)
     * @return up to K candidates EXCLUDING the baseline move, ordered by quick score
     */
    public static List<Candidate> generate(Board rootBoard, String baselineBestUcci,
                                           List<BookCandidate> bookCands, RiskProfile profile) {
        int k = profile.candidateK();
        List<Candidate> out = new ArrayList<>();
        if (k <= 0) return out;

        boolean moverRed = rootBoard.bRedGo;

        // 1) full legal universe, dedup by UCCI
        Map<String, Move> legal = new LinkedHashMap<>();
        for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                int p = rootBoard.getPieceByPosition(x, y);
                if (!com.zfdang.chess.gamelogic.Piece.isValid(p) || com.zfdang.chess.gamelogic.Piece.isRed(p) != moverRed) {
                    continue;
                }
                for (Position to : Rule.PossibleToPositions(p, x, y, rootBoard)) {
                    Move mv = new Move(new Position(x, y), to, rootBoard);
                    String u = mv.getUCCIString();
                    if (!legal.containsKey(u)) legal.put(u, mv);
                }
            }
        }
        if (legal.isEmpty()) return out;

        // 2) opening-book candidates merge (only if present in the legal set)
        Map<String, BookCandidate> bookByUcci = new LinkedHashMap<>();
        if (bookCands != null) {
            for (BookCandidate bc : bookCands) {
                if (bc != null && bc.ucci != null && legal.containsKey(bc.ucci)) {
                    bookByUcci.put(bc.ucci, bc);
                }
            }
        }

        // 3) baseline PV contributes ONLY its first move; the baseline move itself is the
        //    permanent fallback and is therefore excluded from the candidate pool.
        // 4) order: verified opening-book entries first (they carry class information),
        //    then by quick attack score.
        List<Candidate> pool = new ArrayList<>();
        for (Map.Entry<String, Move> e : legal.entrySet()) {
            if (e.getKey().equals(baselineBestUcci)) continue;
            double qs = AttackFeatureEngine.quickScore(rootBoard, e.getValue());
            pool.add(new Candidate(e.getValue(), e.getKey(), bookByUcci.get(e.getKey()), qs));
        }
        pool.sort((a, b) -> {
            boolean ba = a.book != null, bb = b.book != null;
            if (ba != bb) return ba ? -1 : 1;
            return Double.compare(b.quickScore, a.quickScore);
        });

        for (int i = 0; i < pool.size() && out.size() < k; i++) {
            out.add(pool.get(i));
        }
        return out;
    }
}
