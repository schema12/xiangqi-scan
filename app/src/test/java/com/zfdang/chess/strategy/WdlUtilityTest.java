package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import org.junit.Test;

/** Direction-correctness of the win-seeking utility (design C.3, six cases). */
public class WdlUtilityTest {

    private static final double EPS = 1e-9;

    @Test public void candidateLosingWinRateMustHaveNegativeDelta() {
        // 基线 w=.50 d=.40 l=.10;候选 W 降到 .10(旧 abs 公式会误奖励)
        int[] best = {500, 400, 100};
        int[] cand = {100, 400, 500};
        Double du = WdlUtility.deltaU(best, cand, 0.5 * (1 - 0.6));
        assertNotNull(du);
        assertEquals(-0.40, du, EPS);
        assertTrue("utility loss must be negative", du < 0);
    }

    @Test public void candidateGainingWinRateHasPositiveDelta() {
        int[] best = {500, 400, 100};
        int[] cand = {650, 200, 150};
        Double du = WdlUtility.deltaU(best, cand, 0.2);
        assertNotNull(du);
        assertEquals(0.65 + 0.2 * 0.20 - (0.50 + 0.2 * 0.40), du, EPS);
        assertTrue(du > 0);
    }

    @Test public void lowRiskRewardsDrawishRoutes() {
        int[] best = {300, 400, 300};
        int[] drawish = {300, 600, 100};
        // 稳健 alpha=0 -> q=0.5:和棋化路线效用更高
        Double du = WdlUtility.deltaU(best, drawish, 0.5);
        assertNotNull(du);
        assertEquals(0.10, du, EPS);
        assertTrue(du > 0);
    }

    @Test public void highRiskNoLongerRewardsDrawishRoutes() {
        int[] best = {300, 400, 300};
        int[] drawish = {300, 600, 100};
        // 极端 alpha=1 -> q=0:不再奖励和棋
        Double du = WdlUtility.deltaU(best, drawish, 0.0);
        assertNotNull(du);
        assertEquals(0.0, du, EPS);
    }

    @Test public void losingLinesNeverGainUtilityEvenAtMaxRisk() {
        int[] best = {300, 400, 300};
        int[] doomed = {50, 150, 800};
        Double du = WdlUtility.deltaU(best, doomed, 0.0);
        assertNotNull(du);
        assertTrue("高风险也不得奖励败势", du < 0);
    }

    @Test public void lossProbabilityNeverAddsPositiveUtility() {
        // 输棋概率在效用中的系数恒为 0:仅 l 增大而 w/d 不变时,效用不变(不奖励)
        int[] base = {500, 400, 100};
        int[] moreLoss = {500, 300, 200};
        Double du = WdlUtility.deltaU(base, moreLoss, 0.25);
        assertNotNull(du);
        assertEquals(-0.1 * 0.25, du, EPS); // 只损失和棋份额,无任何正向奖励
    }

    @Test public void malformedWdlIsRejected() {
        assertFalse(WdlUtility.wellFormed(null));
        assertFalse(WdlUtility.wellFormed(new int[]{500, 400}));
        assertFalse(WdlUtility.wellFormed(new int[]{500, 400, 200})); // sum != 1000
        assertFalse(WdlUtility.wellFormed(new int[]{-5, 500, 505}));
        assertTrue(WdlUtility.wellFormed(new int[]{500, 400, 100}));
    }

    @Test public void deltaUIsNullWhenEitherSideUnavailable() {
        assertNull(WdlUtility.deltaU(null, new int[]{500, 400, 100}, 0.5));
        assertNull(WdlUtility.deltaU(new int[]{500, 400, 100}, new int[]{1, 1, 1}, 0.5));
    }
}
