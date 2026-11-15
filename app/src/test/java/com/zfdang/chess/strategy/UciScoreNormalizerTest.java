package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Collections;

/** CP/Mate separation and POV handling (design C.4 / fix A2). */
public class UciScoreNormalizerTest {

    private static org.petero.droidfish.player.SearchOutcome outcome(
            String best, int cp, int mateIn, int[] wdl, int depth, boolean bound) {
        return new org.petero.droidfish.player.SearchOutcome(1, best, cp, mateIn, wdl,
                depth, bound, bound, Collections.emptyList(), 1000, 100, false);
    }

    @Test public void mateIsNeverTreatedAsCentipawns() {
        UciScoreNormalizer.DecisionScore s = UciScoreNormalizer.from(outcome("h2e2", 0, 3, null, 12, false));
        assertTrue(s.valid);
        assertEquals(3, s.mateIn);
        assertEquals(0, s.cp);
    }

    @Test public void negativeMateMeansGettingMated() {
        UciScoreNormalizer.DecisionScore s = UciScoreNormalizer.from(outcome("h2e2", 0, -2, null, 10, false));
        assertEquals(-2, s.mateIn);
        assertEquals(0, s.cp);
    }

    @Test public void cpScoresKeepTheirValue() {
        UciScoreNormalizer.DecisionScore s = UciScoreNormalizer.from(outcome("h2e2", 137, 0, null, 14, false));
        assertEquals(137, s.cp);
        assertEquals(0, s.mateIn);
    }

    @Test public void missingBestMoveIsInvalid() {
        assertFalse(UciScoreNormalizer.from(outcome(null, 100, 0, null, 10, false)).valid);
        assertFalse(UciScoreNormalizer.from(outcome("0000", 100, 0, null, 10, false)).valid);
    }

    @Test public void deltaIsBestMinusCandidateInMoverPov() {
        UciScoreNormalizer.DecisionScore best = UciScoreNormalizer.from(outcome("b2e2", 90, 0, null, 12, false));
        UciScoreNormalizer.DecisionScore cand = UciScoreNormalizer.from(outcome("h2e2", 40, 0, null, 12, false));
        assertEquals(50, UciScoreNormalizer.deltaCp(best, cand));
        assertEquals(-50, UciScoreNormalizer.deltaCp(cand, best));
    }

    @Test public void deltaAcrossMateIsSentinel() {
        UciScoreNormalizer.DecisionScore best = UciScoreNormalizer.from(outcome("b2e2", 90, 0, null, 12, false));
        UciScoreNormalizer.DecisionScore mating = UciScoreNormalizer.from(outcome("h2e2", 0, 2, null, 12, false));
        assertEquals(Integer.MAX_VALUE, UciScoreNormalizer.deltaCp(best, mating));
    }

    @Test public void redPovConversionFlipsOnlyForBlack() {
        assertEquals(100, UciScoreNormalizer.toRedPov(100, true));
        assertEquals(-100, UciScoreNormalizer.toRedPov(100, false));
    }
}
