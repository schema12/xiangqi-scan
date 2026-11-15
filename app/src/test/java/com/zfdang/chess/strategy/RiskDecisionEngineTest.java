package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.openbook.BookCandidate;
import com.zfdang.chess.strategy.RiskDecisionEngine.Host;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * State-machine behavior: baseline lock, single commit, stale-id rejection,
 * cancel fallback, engine-error fallback, forced-mate commit.
 */
public class RiskDecisionEngineTest {

    private static class MockHost implements Host {
        int nextId = 1;
        final List<String> rawCommands = new ArrayList<>();
        final List<String> restrictedRequests = new ArrayList<>();
        int pendingSearchId = -1;
        String pendingRestrictedUcci = null;
        String committed = null;
        String commitReason = null;
        int commitCount = 0;
        boolean stopCalled = false;
        final List<String> statusMessages = new ArrayList<>();

        @Override public int startBaselineSearch() {
            pendingSearchId = nextId++;
            pendingRestrictedUcci = null;
            return pendingSearchId;
        }

        @Override public int startRestrictedSearch(String ucci, int movetimeMs) {
            pendingSearchId = nextId++;
            pendingRestrictedUcci = ucci;
            restrictedRequests.add(ucci);
            return pendingSearchId;
        }

        @Override public void sendRawCommand(String uciCommand) {
            rawCommands.add(uciCommand);
        }

        @Override public void stopEngineSearch() {
            stopCalled = true;
        }

        @Override public void commitMove(String ucci, String reason) {
            committed = ucci;
            commitReason = reason;
            commitCount++;
        }

        @Override public void status(String message) {
            statusMessages.add(message);
        }

        @Override public PlayLogger logger() {
            return null;
        }
    }

    private static org.petero.droidfish.player.SearchOutcome outcome(int id, String best,
                                                                    int cp, int mateIn, boolean afterStop, String... pv) {
        return new org.petero.droidfish.player.SearchOutcome(id, best, cp, mateIn, null,
                mateIn != 0 ? 20 : 12, false, false, Arrays.asList(pv), 10000, 500, afterStop);
    }

    private static RiskProfile aggressive() {
        return RiskProfile.of(95, 1.0, 0.9, 0.7, false);
    }

    @Test public void alphaZeroCommitsBaselineImmediately() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, RiskProfile.of(10, 0, 0, 0, true),
                new Board(), Collections.emptyList());
        engine.begin();
        int baselineId = host.pendingSearchId;
        engine.onOutcome(outcome(baselineId, "b2e2", 20, 0, false, "b2e2"));
        assertEquals("b2e2", host.committed);
        assertEquals(1, host.commitCount);
        assertFalse(engine.isActive());
    }

    @Test public void staleSearchIdIsIgnored() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        int baselineId = host.pendingSearchId;
        engine.onOutcome(outcome(baselineId + 100, "x9x0", 999, 0, false, "x9x0"));
        assertNull(host.committed);
        assertTrue(engine.isActive());
        engine.onOutcome(outcome(baselineId, "b2e2", 20, 0, false, "b2e2"));
        // baseline accepted; candidates launched
        assertTrue(engine.isActive() || host.commitCount == 1);
    }

    @Test public void forcedMateInBaselineCommitsWithoutCandidates() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "h2e2", 0, 1, false, "h2e2"));
        assertEquals("h2e2", host.committed);
        assertTrue(host.commitReason.contains("baseline-mate"));
        assertEquals(0, host.restrictedRequests.size());
    }

    @Test public void candidateForcedMateIsCommittedOverBaseline() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        int baselineId = host.pendingSearchId;
        engine.onOutcome(outcome(baselineId, "b2e2", 0, 0, false, "b2e2"));
        assertTrue("candidates must be launched", host.pendingRestrictedUcci != null);
        String firstCandidate = host.pendingRestrictedUcci;
        assertNotEquals("b2e2", firstCandidate);
        // 第一个候选宣布强制杀棋 → 直接提交该候选
        engine.onOutcome(outcome(host.pendingSearchId, firstCandidate, 0, 2, false,
                firstCandidate, "e6e5"));
        assertEquals(firstCandidate, host.committed);
        assertEquals(1, host.commitCount);
    }

    @Test public void rejectedCandidatesFallBackToBaseline() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 0, false, "b2e2"));
        // 每个候选都给出大幅退化的评估(Δ=500 > Δeff) → 全部拒绝 → 回退基线
        int guard = 0;
        while (engine.isActive() && host.pendingRestrictedUcci != null && guard++ < 10) {
            engine.onOutcome(outcome(host.pendingSearchId, host.pendingRestrictedUcci,
                    -500, 0, false, host.pendingRestrictedUcci));
        }
        assertTrue("engine must terminate", !engine.isActive());
        assertEquals("b2e2", host.committed);
        assertTrue(host.commitReason.startsWith("fallback") || host.commitReason.contains("no-valid"));
    }

    @Test public void cancelDuringCandidatesCommitsLockedBaseline() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 10, 0, false, "b2e2"));
        assertTrue(host.pendingRestrictedUcci != null);
        engine.cancel();
        assertTrue(host.stopCalled);
        engine.onOutcome(outcome(host.pendingSearchId, host.pendingRestrictedUcci,
                -30, 0, true, host.pendingRestrictedUcci));
        assertEquals("b2e2", host.committed);
        assertEquals(1, host.commitCount);
    }

    @Test public void engineErrorAfterBaselineCommitsFallback() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 10, 0, false, "b2e2"));
        assertTrue(engine.onEngineError());
        assertEquals("b2e2", host.committed);
        assertEquals(1, host.commitCount);
    }

    @Test public void engineErrorWithoutBaselineIsNotHandled() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        assertFalse(engine.onEngineError());
        assertFalse(engine.isActive());
        assertEquals(0, host.commitCount);
    }

    @Test public void onlyOneMoveIsEverCommitted() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 1, false, "b2e2"));
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 1, false, "b2e2"));
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 1, false, "b2e2"));
        assertEquals(1, host.commitCount);
    }

    @Test public void wdlOptionToggledAroundDecision() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.emptyList());
        engine.begin();
        assertTrue(host.rawCommands.contains("setoption name UCI_ShowWDL value true"));
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 1, false, "b2e2"));
        assertTrue(host.rawCommands.contains("setoption name UCI_ShowWDL value false"));
    }

    @Test public void bookCandidatesAreVerifiedNotPlayedDirectly() {
        MockHost host = new MockHost();
        BookCandidate book = new BookCandidate("h2e2", 300, 0.6, "aggressive", null, false,
                "sound_attack", "test");
        RiskDecisionEngine engine = new RiskDecisionEngine(host, aggressive(),
                new Board(), Collections.singletonList(book));
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 0, false, "b2e2"));
        // 开局候选排在最前(带 class 信息),必须被受限搜索复验而不是直接走
        assertEquals("开局候选必须先进 CandidateAnalyzer", "h2e2", host.restrictedRequests.get(0));
    }
}
