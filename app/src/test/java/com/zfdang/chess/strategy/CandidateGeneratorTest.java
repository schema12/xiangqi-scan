package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.openbook.BookCandidate;
import com.zfdang.chess.strategy.CandidateGenerator.Candidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Candidate generation rules (design E.1 / fix A1). */
public class CandidateGeneratorTest {

    private static Move findLegal(Board b, String ucci) {
        Move m = new Move(b);
        assertTrue("move must parse: " + ucci, m.fromUCCIString(ucci));
        return m;
    }

    @Test public void startposRedHasCandidatesAndBaselineExcluded() {
        Board board = new Board(); // 标准开局,红先
        RiskProfile profile = RiskProfile.of(100, 1.0, 0, 0, false);
        List<Candidate> cands = CandidateGenerator.generate(board, "b2e2", null, profile);
        assertEquals("K=5 at alpha=1", 5, cands.size());
        for (Candidate c : cands) {
            assertNotEquals("baseline move must never be re-searched", "b2e2", c.ucci);
        }
        // 每个候选都是合法着法:用合法性 API 交叉验证
        for (Candidate c : cands) {
            Move m = findLegal(board, c.ucci);
            assertTrue(com.zfdang.chess.gamelogic.Rule.isValidMove(m, board));
        }
    }

    @Test public void opponentPvReplyIsNeverACandidate() {
        Board board = new Board();
        RiskProfile profile = RiskProfile.of(100, 1.0, 0, 0, false);
        // PV: b2e2(我方) + h9g9(对方应手)。候选生成只允许收到第 1 着。
        List<Candidate> cands = CandidateGenerator.generate(board, "b2e2", null, profile);
        for (Candidate c : cands) {
            assertNotEquals("PV[1] 是对方的应手,不得成为我方候选", "h9g9", c.ucci);
        }
    }

    @Test public void alphaZeroYieldsNoCandidates() {
        Board board = new Board();
        assertTrue(CandidateGenerator.generate(board, "b2e2", null,
                RiskProfile.of(10, 0, 0, 0, true)).isEmpty());
    }

    @Test public void bookCandidatesMergeOnlyWhenLegal() {
        Board board = new Board();
        RiskProfile profile = RiskProfile.of(80, 0.7, 0, 0, false);
        BookCandidate legal = new BookCandidate("h2e2", 100, 0.5, "aggressive", null, false, "sound_attack", "test");
        BookCandidate illegal = new BookCandidate("zz99", 500, 0.9, "trap", null, false, "trap", "test");
        List<Candidate> cands = CandidateGenerator.generate(board, "b3c3", Arrays.asList(legal, illegal), profile);
        boolean sawBook = false;
        for (Candidate c : cands) {
            assertNotEquals("非法开局数据必须被丢弃", "zz99", c.ucci);
            if (c.book != null) {
                sawBook = true;
                assertEquals("h2e2", c.ucci);
            }
        }
        assertTrue("合法开局候选应优先进入受限验证", sawBook);
    }

    @Test public void ucciEncodingIsConsistentFourChars() {
        Board board = new Board();
        RiskProfile profile = RiskProfile.of(60, 0.4, 0, 0, false);
        List<Candidate> cands = CandidateGenerator.generate(board, null, null, profile);
        for (Candidate c : cands) {
            assertEquals(4, c.ucci.length());
            assertTrue(c.ucci.charAt(0) >= 'a' && c.ucci.charAt(0) <= 'i');
            assertTrue(c.ucci.charAt(2) >= 'a' && c.ucci.charAt(2) <= 'i');
        }
    }
}
