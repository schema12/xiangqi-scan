package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import org.junit.Test;

/** Continuous mapping + guard orthogonality (design B). */
public class RiskProfileTest {

    @Test public void alphaIsZeroBelowTwenty() {
        assertEquals(0.0, RiskProfile.alphaFromRisk(0.0), 1e-9);
        assertEquals(0.0, RiskProfile.alphaFromRisk(0.19), 1e-9);
        assertEquals(0.0, RiskProfile.alphaFromRisk(0.20), 1e-9);
    }

    @Test public void alphaIsMonotonicAndContinuous() {
        double prev = -1;
        for (int r = 0; r <= 100; r += 5) {
            double a = RiskProfile.alphaFromRisk(r / 100.0);
            assertTrue("alpha must be non-decreasing", a >= prev - 1e-12);
            assertTrue(a >= 0.0 && a <= 1.0);
            prev = a;
        }
        // 连续性:r=61 与 r=85 必须产生不同 alpha(旧五档设计的核心缺陷)
        assertNotEquals(RiskProfile.alphaFromRisk(0.61), RiskProfile.alphaFromRisk(0.85), 1e-6);
        assertTrue(RiskProfile.alphaFromRisk(0.85) > RiskProfile.alphaFromRisk(0.61));
        assertEquals(1.0, RiskProfile.alphaFromRisk(1.0), 1e-9);
    }

    @Test public void betaAndGammaMonotonicWithBounds() {
        assertEquals(0.0, RiskProfile.betaFromRisk(0.14), 1e-9);
        assertTrue(RiskProfile.betaFromRisk(0.5) > 0 && RiskProfile.betaFromRisk(0.5) < 1);
        assertEquals(1.0, RiskProfile.betaFromRisk(1.0), 1e-9);
        assertEquals(0.0, RiskProfile.gammaFromRisk(0.0), 1e-9);
        assertTrue(RiskProfile.gammaFromRisk(1.0) > 0 && RiskProfile.gammaFromRisk(1.0) <= 0.7);
        assertTrue(RiskProfile.gammaFromRisk(0.5) < RiskProfile.gammaFromRisk(0.8));
    }

    @Test public void candidateKSpansOneToFive() {
        assertEquals(0, RiskProfile.of(10, 0, 0, 0, true).candidateK());
        assertEquals(1, RiskProfile.of(30, 0.05, 0, 0, true).candidateK());
        assertTrue(RiskProfile.of(80, 0.7, 0, 0, false).candidateK() >= 3);
        assertEquals(5, RiskProfile.of(100, 1.0, 0, 0, false).candidateK());
    }

    @Test public void deltaEnvelopesRespectHardCapAndGuardIsOrthogonal() {
        RiskProfile full = RiskProfile.of(100, 1.0, 0, 0, false);
        assertEquals(200.0, full.deltaMaxCp(), 1e-9);
        // guard 只改变 delta 上限,不改变 alpha/beta/gamma
        RiskProfile guarded = RiskProfile.of(100, 1.0, 0, 0, true);
        assertEquals(50.0, guarded.deltaGuardCp(), 1e-9);
        assertEquals(full.alpha, guarded.alpha, 1e-9);
        assertEquals(full.beta, guarded.beta, 1e-9);
        assertEquals(full.gamma, guarded.gamma, 1e-9);
        // guard 上限必须显著小于非 guard 上限(不与风险参数重复限制)
        assertTrue(guarded.deltaGuardCp() < full.deltaMaxCp());
    }

    @Test public void guardDeltaGrowsWithAlphaButStaysBounded() {
        RiskProfile mid = RiskProfile.of(60, 0.4, 0, 0, true);
        RiskProfile high = RiskProfile.of(90, 0.9, 0, 0, true);
        assertTrue(high.deltaGuardCp() > mid.deltaGuardCp());
        assertTrue(high.deltaGuardCp() <= 50.0);
    }

    @Test public void drawWeightDecreasesWithRisk() {
        assertEquals(0.5, RiskProfile.of(0, 0, 0, 0, true).qDraw(), 1e-9);
        assertEquals(0.0, RiskProfile.of(100, 1.0, 0, 0, false).qDraw(), 1e-9);
        assertTrue(RiskProfile.of(60, 0.4, 0, 0, true).qDraw()
                > RiskProfile.of(90, 0.9, 0, 0, false).qDraw());
    }

    @Test public void trapsNeedHighRiskAndGuardOff() {
        assertFalse(RiskProfile.of(60, 0.4, 0, 0, false).allowsTraps());
        assertFalse(RiskProfile.of(95, 0.94, 0, 0, true).allowsTraps());
        assertTrue(RiskProfile.of(95, 0.94, 0, 0, false).allowsTraps());
    }

    @Test public void presetNamesCoverFiveBands() {
        assertEquals("稳健", RiskProfile.presetName(0));
        assertEquals("稳健", RiskProfile.presetName(20));
        assertEquals("均衡", RiskProfile.presetName(30));
        assertEquals("主动进攻", RiskProfile.presetName(50));
        assertEquals("激进争胜", RiskProfile.presetName(73));
        assertEquals("极端冒险", RiskProfile.presetName(95));
    }
}
