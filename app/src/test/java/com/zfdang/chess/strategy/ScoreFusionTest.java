package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.openbook.BookCandidate;
import com.zfdang.chess.strategy.CandidateGenerator.Candidate;
import com.zfdang.chess.strategy.ScoreFusion.Scored;
import com.zfdang.chess.strategy.ScoreFusion.Selection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Layer-1 hard gates + five-layer scoring + near-tie diversity (design D). */
public class ScoreFusionTest {

    private static Candidate cand(String ucci) {
        Board b = new Board();
        Move m = new Move(b);
        if (ucci != null && m.fromUCCIString(ucci)) {
            return new Candidate(m, ucci, null, 0.5);
        }
        return new Candidate(m, ucci, null, 0.0);
    }

    private static AttackFeatureEngine.Features features(double attackTotal) {
        // 构造特征:总攻击分由 weighted sum 决定,测试里直接把每项乘好
        AttackFeatureEngine.Features f = new AttackFeatureEngine.Features();
        double unit = attackTotal; // 每项都设为同一值时,weighted sum == 该值(权重和为1)
        f.checkChain = unit; f.mateThreat = unit; f.kingZone = unit; f.doubleAttack = unit;
        f.pinSkewer = unit; f.discovered = unit; f.openFile = unit; f.tempo = unit;
        f.defenderPull = unit;
        f.complexity = 0.5;
        return f;
    }

    private static UciScoreNormalizer.DecisionScore ds(int cp) {
        return new UciScoreNormalizer.DecisionScore(cp, 0, null, true, false);
    }

    private static Scored evaluate(String ucci, int candCp, int netLoss, double attack,
                                   RiskProfile profile, double deltaEff) {
        UciScoreNormalizer.DecisionScore s = new UciScoreNormalizer.DecisionScore(candCp, 0, null, true, false);
        AttackFeatureEngine.Features f = features(attack);
        SacrificeClassifier.Result sac = new SacrificeClassifier.Result();
        sac.netLoss = netLoss;
        sac.sacrifice = netLoss <= -100;
        sac.type = sac.sacrifice ? SacrificeClassifier.TYPE_C_PRESSURE : SacrificeClassifier.TYPE_NONE;
        sac.compensation = sac.sacrifice ? 0.5 : 0.0;
        return ScoreFusion.evaluate(cand(ucci), s, f, sac, Confidence.FULL, profile,
                deltaEff, 0.0, null, 0.0);
    }

    @Test public void deltaAboveEnvelopeIsRejected() {
        RiskProfile guarded = RiskProfile.of(90, 0.9, 0, 0, true); // deltaGuard = 47
        Scored s = evaluate("h2e2", -60, 0, 0.8, guarded, guarded.deltaGuardCp());
        assertNotNull(s.rejectReason, s.rejectReason);
        assertTrue(s.rejectReason.contains("delta"));
    }

    @Test public void deltaInsideEnvelopeIsScored() {
        RiskProfile open = RiskProfile.of(90, 0.9, 0, 0, false); // deltaMax*phi = 180
        Scored s = evaluate("h2e2", -60, 0, 0.8, open, open.deltaMaxCp());
        assertNull(s.rejectReason);
        assertTrue(s.softScore > 0);
    }

    @Test public void materialGateRejectsUncompensatedBigLosses() {
        RiskProfile profile = RiskProfile.of(95, 0.94, 0, 0, false);
        Scored s = evaluate("h2e2", -100, -500, 0.2, profile, 200);
        assertNotNull(s.rejectReason);
        assertEquals("material-gate", s.rejectReason);
    }

    @Test public void blunderSacrificesAreRejected() {
        RiskProfile profile = RiskProfile.of(95, 0.94, 0, 0, false);
        SacrificeClassifier.Result sacA = new SacrificeClassifier.Result();
        sacA.netLoss = -450;
        sacA.sacrifice = true;
        sacA.type = SacrificeClassifier.TYPE_A_BLUNDER;
        sacA.note = "no-compensation";
        Scored s = ScoreFusion.evaluate(cand("h2e2"), ds(-200), features(0.9), sacA,
                Confidence.FULL, profile, 200.0, 0.0, null, 0.0);
        assertNotNull(s.rejectReason);
        assertTrue(s.rejectReason.startsWith("sacrifice-class-A"));
    }

    @Test public void forcedMateBypassesSoftScore() {
        RiskProfile profile = RiskProfile.of(90, 0.9, 0, 0, false);
        UciScoreNormalizer.DecisionScore mate = new UciScoreNormalizer.DecisionScore(0, 3, null, true, false);
        Scored s = ScoreFusion.evaluate(cand("h2e2"), mate, features(0.1),
                new SacrificeClassifier.Result(), Confidence.FULL, profile, 20.0, 0.0, null, 0.0);
        assertNull(s.rejectReason);
        assertEquals(1_000_000.0, s.softScore, 1e-6);
    }

    @Test public void cpNeverReentersSoftScore() {
        // 相同特征/补偿/效用,不同 CP 损失(都在容忍带内) → 软分完全相同(设计 D:CP 只做硬过滤)
        RiskProfile profile = RiskProfile.of(90, 0.9, 0, 0, false);
        double deltaEff = profile.deltaMaxCp();
        Scored small = evaluate("h2e2", -5, 0, 0.5, profile, deltaEff);
        Scored large = evaluate("b3c3", -45, 0, 0.5, profile, deltaEff);
        assertNull(small.rejectReason);
        assertNull(large.rejectReason);
        assertEquals(small.softScore, large.softScore, 1e-9);
    }

    @Test public void winUtilityShiftsScoreWithCorrectSign() {
        RiskProfile profile = RiskProfile.of(90, 0.9, 0, 0, false);
        int[] best = {500, 400, 100};
        int[] good = {650, 250, 100};
        int[] bad = {100, 400, 500};
        double q = profile.qDraw();
        double duGood = WdlUtility.deltaU(best, good, q);
        double duBad = WdlUtility.deltaU(best, bad, q);
        Scored sGood = ScoreFusion.evaluate(cand("h2e2"), ds(0), features(0.5),
                new SacrificeClassifier.Result(), Confidence.FULL, profile, 200.0, 0.0, duGood, 0.0);
        Scored sBad = ScoreFusion.evaluate(cand("b3c3"), ds(0), features(0.5),
                new SacrificeClassifier.Result(), Confidence.FULL, profile, 200.0, 0.0, duBad, 0.0);
        assertNull(sGood.rejectReason);
        assertNull(sBad.rejectReason);
        assertTrue(sGood.softScore > sBad.softScore);
        assertTrue(sBad.softScore < sGood.softScore);
    }

    @Test public void allRejectedMeansKeepBaseline() {
        RiskProfile guarded = RiskProfile.of(90, 0.9, 0, 0, true);
        List<Scored> all = new ArrayList<>();
        all.add(evaluate("h2e2", -90, 0, 0.8, guarded, guarded.deltaGuardCp()));
        all.add(evaluate("b3c3", -95, 0, 0.8, guarded, guarded.deltaGuardCp()));
        Selection sel = ScoreFusion.select(all, guarded);
        assertNull(sel.chosen);
        assertNotNull(sel.reason);
    }

    @Test public void lowScoresKeepBaseline() {
        RiskProfile profile = RiskProfile.of(90, 0.9, 0, 0, false);
        // attack 分为 0 的平凡候选:softScore = 0 → 回退基线
        Scored flat = evaluate("h2e2", 0, 0, 0.0, profile, 200);
        assertNull(flat.rejectReason);
        assertEquals(0.0, flat.softScore, 1e-9);
        Selection sel = ScoreFusion.select(new ArrayList<>(Collections.singletonList(flat)), profile);
        assertNull(sel.chosen);
    }

    @Test public void diversityOnlyTouchesNearTiedTopCandidates() {
        RiskProfile diverse = RiskProfile.of(90, 0.9, 0, 0.7, false);
        Scored a = evaluate("h2e2", 0, 0, 0.6, diverse, 200);
        Scored b = evaluate("b3c3", 0, 0, 0.6, diverse, 200);
        Scored weak = evaluate("e3e4", 0, 0, 0.05, diverse, 200);
        List<Scored> all = new ArrayList<>(Arrays.asList(a, b, weak));
        // 近并列的 a/b 分数相同,弱着法分数低 → 选择必须落在 a/b 内
        for (int i = 0; i < 20; i++) {
            Selection sel = ScoreFusion.select(all, diverse);
            assertNotNull(sel.chosen);
            assertTrue("多样性不得让弱着法胜出",
                    sel.chosen.candidate.ucci.equals("h2e2") || sel.chosen.candidate.ucci.equals("b3c3"));
        }
    }

    @Test public void diversityDisabledKeepsBestScore() {
        RiskProfile noDiversity = RiskProfile.of(90, 0.9, 0, 0.0, false);
        Scored a = evaluate("h2e2", 0, 0, 0.6, noDiversity, 200);
        Scored b = evaluate("b3c3", 0, 0, 0.6, noDiversity, 200);
        Selection sel = ScoreFusion.select(new ArrayList<>(Arrays.asList(a, b)), noDiversity);
        assertNotNull(sel.chosen);
        assertEquals("h2e2", sel.chosen.candidate.ucci); // 列表序中先出现者保持
    }

    @Test public void openingBonusOnlyThroughBeta() {
        RiskProfile profile = RiskProfile.of(90, 0.9, 0.0, 0, false);
        BookCandidate book = new BookCandidate("h2e2", 100, 0.5, "aggressive", null, false, "sound_attack", "t");
        Scored withBook = ScoreFusion.evaluate(cand("h2e2"), ds(0), features(0.5),
                new SacrificeClassifier.Result(), Confidence.FULL, profile, 200.0, 0.0,
                null, ScoreFusion.openingBonus(book));
        // β=0 时开局加分为 0
        Scored withoutBook = ScoreFusion.evaluate(cand("b3c3"), ds(0), features(0.5),
                new SacrificeClassifier.Result(), Confidence.FULL, profile, 200.0, 0.0,
                null, 0.0);
        assertEquals(withBook.softScore, withoutBook.softScore, 1e-9);
    }
}
