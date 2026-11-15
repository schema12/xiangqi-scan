package com.zfdang.chess.strategy;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Five-level credibility grading (design G.2). */
public class ConfidenceTest {

    private static org.petero.droidfish.player.SearchOutcome o(
            String best, int mateIn, int depth, boolean bound, boolean afterStop, String... pv) {
        return new org.petero.droidfish.player.SearchOutcome(7, best, 0, mateIn, null,
                depth, bound, bound, Arrays.asList(pv), 1000, 100, afterStop);
    }

    @Test public void noBestMoveIsLevelNone() {
        assertEquals(Confidence.NONE, Confidence.classify(null));
        assertEquals(Confidence.NONE, Confidence.classify(o(null, 0, 12, false, false)));
        assertEquals(Confidence.NONE, Confidence.classify(o("0000", 0, 12, false, false)));
    }

    @Test public void mateAnnouncementIsAlwaysFull() {
        assertEquals(Confidence.FULL, Confidence.classify(o("h2e2", 3, 6, false, false, "h2e2")));
        assertEquals(Confidence.FULL, Confidence.classify(o("h2e2", -3, 4, false, true, "h2e2")));
    }

    @Test public void stopInducedResultsAreDowngraded() {
        assertEquals(Confidence.INTERRUPTED, Confidence.classify(o("h2e2", 0, 2, false, true, "h2e2")));
        assertEquals(Confidence.LIMITED, Confidence.classify(o("h2e2", 0, 6, false, true, "h2e2")));
    }

    @Test public void boundedResultsNeverExceedLimited() {
        assertEquals(Confidence.LIMITED, Confidence.classify(o("h2e2", 0, 20, true, false, "h2e2")));
    }

    @Test public void depthThresholdsSeparateBasicAndFull() {
        assertEquals(Confidence.BASIC, Confidence.classify(o("h2e2", 0, 8, false, false, "h2e2", "e6e5")));
        assertEquals(Confidence.FULL, Confidence.classify(o("h2e2", 0, 12, false, false, "h2e2", "e6e5")));
        // depth >= CONF_DEPTH_MIN 但无 PV:不足以进入排序
        assertEquals(Confidence.INTERRUPTED,
                Confidence.classify(o("h2e2", 0, 9, false, false)));
    }

    @Test public void shallowCompleteSearchIsInterrupted() {
        assertEquals(Confidence.INTERRUPTED, Confidence.classify(o("h2e2", 0, 3, false, false, "h2e2")));
    }

    @Test public void emptyPvListIsAcceptedForNull() {
        // pv 为空列表时 depth>=8 仍算 BASIC(列表非空由 pvUcci.isEmpty 判定)
        assertEquals(Confidence.BASIC, Confidence.classify(
                new org.petero.droidfish.player.SearchOutcome(7, "h2e2", 0, 0, null, 8,
                        false, false, Collections.emptyList(), 1000, 100, false)));
    }
}
