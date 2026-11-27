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
    fun caps_apply_to_parse() {
        val (red, black) = JieqiCaptures.parse("RRRRRPPPPP,9;ppppp,9")
        assertEquals(JieqiCaptures.MAX_NORMAL, red['R'])   // 常规子封顶 2
        assertEquals(JieqiCaptures.MAX_PAWN, red['P'])      // 兵封顶 5
        assertEquals(JieqiCaptures.MAX_DARK, red['X'])      // 暗子封顶 9
        assertEquals(JieqiCaptures.MAX_PAWN, black['p'])
        assertEquals(JieqiCaptures.MAX_DARK, black['x'])
    }

    @Test
    fun max_of_pieces() {
        assertEquals(2, JieqiCaptures.maxOf('R'))
        assertEquals(5, JieqiCaptures.maxOf('P'))
        assertEquals(5, JieqiCaptures.maxOf('p'))
        assertEquals(9, JieqiCaptures.maxOf('X'))
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

    @Test
    fun spec_of_captured_roundtrips() {
        val captured = arrayOf(
            mutableListOf(CapturedPiece('R', false), CapturedPiece('R', false), CapturedPiece('X', true)),
            mutableListOf(CapturedPiece('p', false), CapturedPiece('?', true))
        )
        val spec = JieqiCaptures.specOf(captured)
        assertEquals("RR,1;p,1", spec)
    }
}
