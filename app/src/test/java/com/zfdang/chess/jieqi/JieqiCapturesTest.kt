package com.zfdang.chess.jieqi

import org.junit.Assert.assertEquals
import org.junit.Test

class JieqiCapturesTest {
    @Test
    fun parse_and_format_roundtrip() {
        // 红方吃掉黑子(小写)；黑方吃掉红子(大写)。暗子红组=x 黑组=X
        val (red, black) = JieqiCaptures.parse("rrnp,2;NP,1")
        assertEquals(2, red['r'])
        assertEquals(1, red['n'])
        assertEquals(1, red['p'])
        assertEquals(2, red[JieqiCaptures.DARK_BLACK])   // 红方吃的暗子用 x
        assertEquals(1, black['N'])
        assertEquals(1, black['P'])
        assertEquals(1, black[JieqiCaptures.DARK_RED])   // 黑方吃的暗子用 X
        assertEquals("rrnp,2;NP,1", JieqiCaptures.format(red, black))
    }

    @Test
    fun parse_ignores_kings_and_wrong_case() {
        // 红组只能放小写，黑组只能放大写；K/k 是被吃不到的将帅
        val (red, black) = JieqiCaptures.parse("Rr,0;nN,0")
        assertEquals(0, red.size)   // R 大写不该出现在红组；r 数量0被移除
        assertEquals(0, black.size) // n 小写不该出现在黑组；N 数量0被移除
    }

    @Test
    fun caps_apply_to_parse() {
        val (red, black) = JieqiCaptures.parse("rrrrrppppp,9;PPPPP,9")
        assertEquals(JieqiCaptures.MAX_NORMAL, red['r'])   // 常规子封顶 2
        assertEquals(JieqiCaptures.MAX_PAWN, red['p'])      // 兵封顶 5
        assertEquals(JieqiCaptures.MAX_DARK, red[JieqiCaptures.DARK_BLACK])
        assertEquals(JieqiCaptures.MAX_PAWN, black['P'])
        assertEquals(JieqiCaptures.MAX_DARK, black[JieqiCaptures.DARK_RED])
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
        // captured[0]=红方吃掉：黑车 r×2 + 未知暗子；captured[1]=黑方吃掉：红兵 P
        val captured = arrayOf(
            mutableListOf(CapturedPiece('r', false), CapturedPiece('r', false), CapturedPiece('?', true)),
            mutableListOf(CapturedPiece('P', false))
        )
        val spec = JieqiCaptures.specOf(captured)
        assertEquals("rr,1;P,0", spec)
    }
}
