package com.zfdang.chess.jieqi

import org.junit.Assert.assertEquals
import org.junit.Test

class JieqiCapturesTest {
    @Test
    fun parse_and_format_roundtrip() {
        val (red, black) = JieqiCaptures.parse("RRNP,2;np,1")
        assertEquals(2, red['R'])
        assertEquals(1, red['N'])
        assertEquals(1, red['P'])
        assertEquals(2, red['X'])
        assertEquals(1, black['n'])
        assertEquals(1, black['p'])
        assertEquals(1, black['x'])
        assertEquals("RRNP,2;np,1", JieqiCaptures.format(red, black))
    }

    @Test
    fun parse_ignores_kings_and_wrong_case() {
        val (red, black) = JieqiCaptures.parse("Kr,0;kR,0")
        assertEquals(0, red.size)
        assertEquals(0, black.size)
    }

    @Test
    fun collapse_deduplicates_and_counts() {
        val groups = JieqiCaptures.collapse(listOf('R', 'R', 'n', 'X', 'n', 'R'))
        assertEquals(listOf('R' to 3, 'n' to 2, 'X' to 1), groups)
    }

    @Test
    fun format_empty_when_no_captures() {
        assertEquals("", JieqiCaptures.format(emptyMap(), emptyMap()))
    }
}
