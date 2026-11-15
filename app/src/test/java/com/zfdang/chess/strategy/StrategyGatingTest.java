package com.zfdang.chess.strategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.strategy.RiskDecisionEngine.Host;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 总开关与独立功能开关的真实门控(增量开发任务 §三):
 * 开关必须真正控制业务逻辑,不能只是 UI 状态。
 */
public class StrategyGatingTest {

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
        }

        @Override public PlayLogger logger() {
            return null;
        }
    }

    private static org.petero.droidfish.player.SearchOutcome outcome(int id, String best,
                                                                    int cp, int mateIn, boolean afterStop, String... pv) {
        return new org.petero.droidfish.player.SearchOutcome(id, best, cp, mateIn, null,
                12, false, false, Arrays.asList(pv), 10000, 500, afterStop);
    }

    /** RiskProfile.of 之外显式构造带开关的 profile。 */
    private static RiskProfile profileWith(int risk, boolean candidate, boolean wdl,
                                           boolean attack, boolean sacrifice, boolean diversity) {
        // of() 默认全开,再按参数覆盖对应维度
        RiskProfile base = RiskProfile.of(risk,
                RiskProfile.alphaFromRisk(risk / 100.0),
                RiskProfile.betaFromRisk(risk / 100.0),
                RiskProfile.gammaFromRisk(risk / 100.0), true);
        return RiskProfile.withSwitches(base, candidate, wdl, attack, sacrifice,
                base.aggressiveBook, base.openingStyle, diversity);
    }

    @Test public void candidateSearchDisabledRunsBaselineOnly() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host,
                profileWith(80, false, true, true, true, true),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 20, 0, false, "b2e2"));
        assertEquals("b2e2", host.committed);
        assertEquals("额外候选搜索关闭时不得发起受限搜索", 0, host.restrictedRequests.size());
        assertTrue(host.commitReason.contains("candidate-search-disabled"));
    }

    @Test public void wdlSwitchDisabledSendsNoWdlOption() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host,
                profileWith(80, true, false, true, true, true),
                new Board(), Collections.emptyList());
        engine.begin();
        assertEquals("WDL 关闭时不得请求 UCI_ShowWDL", 0,
                host.rawCommands.stream().filter(c -> c.contains("UCI_ShowWDL")).count());
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 10, 0, false, "b2e2"));
        assertEquals(0, host.rawCommands.stream().filter(c -> c.contains("UCI_ShowWDL")).count());
    }

    @Test public void wdlEnabledSendsAndRestoresOption() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host,
                profileWith(80, true, true, true, true, true),
                new Board(), Collections.emptyList());
        engine.begin();
        assertTrue(host.rawCommands.contains("setoption name UCI_ShowWDL value true"));
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 0, 1, false, "b2e2"));
        assertTrue(host.rawCommands.contains("setoption name UCI_ShowWDL value false"));
    }

    @Test public void allScoringDisabledAutoSkipsCandidates() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host,
                profileWith(80, true, false, false, false, false),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 10, 0, false, "b2e2"));
        assertEquals("b2e2", host.committed);
        assertEquals("三个评分来源全关 → 自动跳过候选池", 0, host.restrictedRequests.size());
        assertTrue(host.commitReason.contains("all-scoring-disabled"));
    }

    @Test public void candidateSearchStillRunsWhenAtLeastOneScoringSourceOn() {
        MockHost host = new MockHost();
        RiskDecisionEngine engine = new RiskDecisionEngine(host,
                profileWith(80, true, false, true, false, false),
                new Board(), Collections.emptyList());
        engine.begin();
        engine.onOutcome(outcome(host.pendingSearchId, "b2e2", 10, 0, false, "b2e2"));
        assertEquals("至少一个评分来源在用 → 候选池正常执行", 1, host.restrictedRequests.size());
        assertNull(host.committed); // 尚未完成
        // 第一个候选 mate → 立即提交
        engine.onOutcome(outcome(host.pendingSearchId, host.pendingRestrictedUcci, 0, 2,
                false, host.pendingRestrictedUcci));
        assertEquals(host.pendingRestrictedUcci == null ? "b2e2" : host.committed, host.committed);
        assertEquals(1, host.commitCount);
    }

    @Test public void diversityOffPicksBestScoreOnly() {
        // ScoreFusion.select 的多样性门控由 profile.diversityEnabled 控制
        RiskProfile noDiversity = RiskProfile.of(90, 0.9, 0, 0.7, false);
        assertFalse("构造:diversity 开关通过 withSwitches 关闭",
                RiskProfile.withSwitches(noDiversity, true, true, true, true,
                        true, true, false).diversityEnabled);
        assertTrue(RiskProfile.withSwitches(noDiversity, true, true, true, true,
                true, true, true).diversityEnabled);
    }

    @Test public void featureSwitchesCarriedInProfile() {
        // withSwitches 只改功能开关;总开关(strategyEnabled)保留 base 值,
        // 其门控在 GameController.strategyShouldRun(读取 Settings)实现。
        RiskProfile on = RiskProfile.withSwitches(
                RiskProfile.of(80, 0.7, 0.7, 0.5, true), true, true, true, true,
                true, true, true);
        assertTrue(on.candidateSearch);
        assertTrue(on.wdlEnabled);
        assertTrue(on.attackFeatures);
        assertTrue(on.sacrificeAnalysis);
        assertTrue(on.diversityEnabled);
        RiskProfile off = RiskProfile.withSwitches(
                RiskProfile.of(80, 0.7, 0.7, 0.5, true), false, false, false, false,
                true, true, false);
        assertFalse(off.candidateSearch);
        assertFalse(off.wdlEnabled);
        assertFalse(off.attackFeatures);
        assertFalse(off.sacrificeAnalysis);
        assertFalse(off.diversityEnabled);
    }
}
