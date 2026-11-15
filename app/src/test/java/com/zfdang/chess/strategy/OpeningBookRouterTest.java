package com.zfdang.chess.strategy;

import static org.junit.Assert.assertEquals;

import com.zfdang.chess.openbook.OpeningBookRouter;
import com.zfdang.chess.openbook.OpeningBookRouter.Book;

import org.junit.Test;

/** 两套开局库路由表(增量开发任务 §四)。 */
public class OpeningBookRouterTest {

    @Test public void masterOffUsesOriginal() {
        assertEquals(Book.ORIGINAL, OpeningBookRouter.select(false, 80, true, true));
    }

    @Test public void riskZeroUsesOriginal() {
        assertEquals(Book.ORIGINAL, OpeningBookRouter.select(true, 0, true, true));
    }

    @Test public void aggressiveBookSwitchOffUsesOriginal() {
        assertEquals(Book.ORIGINAL, OpeningBookRouter.select(true, 80, false, true));
    }

    @Test public void aggressiveBookUnavailableFallsBackToOriginal() {
        assertEquals(Book.ORIGINAL, OpeningBookRouter.select(true, 80, true, false));
    }

    @Test public void aggressiveModeRoutesToAggressive() {
        assertEquals(Book.AGGRESSIVE, OpeningBookRouter.select(true, 1, true, true));
        assertEquals(Book.AGGRESSIVE, OpeningBookRouter.select(true, 80, true, true));
        assertEquals(Book.AGGRESSIVE, OpeningBookRouter.select(true, 100, true, true));
    }
}
