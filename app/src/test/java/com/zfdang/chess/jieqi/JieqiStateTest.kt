package com.zfdang.chess.jieqi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 揭棋核心规则与状态测试。断言与 AB-JChess 引擎行为对齐（协议经引擎冒烟实测）。
 */
class JieqiStateTest {

    @Test
    fun `start position has correct pools and dark counts`() {
        val s = JieqiGameState.newGame(Random(42))
        assertEquals(15, s.darkCount(true))
        assertEquals(15, s.darkCount(false))
        assertEquals(15, s.poolTotal(true))
        assertEquals(15, s.poolTotal(false))
        assertTrue(s.redToMove)
        val fen = s.fen()
        assertTrue(fen.startsWith(JieqiGameState.START_FEN_BOARD + " w "))
        // 池字段与引擎 START_FEN 一致
        assertTrue(fen.contains("R2A2C2P5N2B2r2a2c2p5n2b2"))
    }

    @Test
    fun `newGame assignment is a perfect matching of pools`() {
        val s = JieqiGameState.newGame(Random(7))
        val a = s.assignment!!
        val counts = HashMap<Char, Int>()
        for (r in 0 until 10) for (c in 0 until 9) {
            val id = a[r][c]
            if (id == '.') {
                assertTrue("non-dark square has assignment", !JieqiRules.isDark(s.grid[r][c]))
            } else {
                assertTrue(JieqiRules.isDark(s.grid[r][c]))
                assertTrue(JieqiRules.isRed(id) == JieqiRules.isRed(s.grid[r][c]))
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        for (slot in 0 until 6) {
            assertEquals(
                "identity count mismatch for ${JieqiGameState.SLOT_LETTER[slot]}",
                JieqiGameState.URN_INIT[slot],
                counts[JieqiGameState.SLOT_LETTER[slot]] ?: 0
            )
            assertEquals(JieqiGameState.URN_INIT[slot], counts[jieqiBlackLetter(slot)])
        }
    }

    private fun jieqiBlackLetter(slot: Int) =
        JieqiGameState.SLOT_LETTER[slot].lowercaseChar()

    @Test
    fun `dark pawn at e-row advances with automatic reveal from assignment`() {
        val s = JieqiGameState.newGame(Random(1))
        // 红暗兵 e3 (row6,col4) 前进到 e4 (row5)
        val fr = 6; val fc = 4; val tr = 5; val tc = 4
        assertTrue(JieqiRules.isDark(s.grid[fr][fc]))
        val err = s.applyMove(fr, fc, tr, tc, '.', '.')
        assertNull(err, err)
        // 引擎 token：e6e5 + 揭晓身份（rank3→坐标 e6）
        val token = s.tokens.single()
        assertEquals("e3e4", token.substring(0, 4))
        assertTrue(token.length == 5)
        // 池扣减 + 暗子数变化
        val revealed = token[4]
        val slot = JieqiGameState.slotOf(revealed)
        assertEquals(JieqiGameState.URN_INIT[slot] - 1, s.pools[0][slot])
        assertEquals(14, s.darkCount(true))
        assertEquals(15, s.darkCount(false))
        // 揭晓后由黑方行棋
        assertFalse(s.redToMove)
    }

    @Test
    fun `scan mode dark move requires explicit reveal`() {
        val fen = "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX w R2A2C2P5N2B2r2a2c2p5n2b2 0 1"
        val parsed = JieqiGameState.parseFen(fen)
        assertNotNull(parsed)
        val (grid, redToMove, pools) = parsed!!
        val s = JieqiGameState.fromImported(grid, redToMove, pools)
        // 红暗炮 b2 (row7,col1) 平移三格到 e2：无揭晓应失败
        assertNotNull(s.applyMove(7, 1, 7, 4, '.', '.'))
        // 揭晓为红车成功（池 R2→1）
        assertNull(s.applyMove(7, 1, 7, 4, 'R', '.'))
        assertEquals("b2e2R", s.tokens.single())
        assertEquals(1, s.pools[0][0])
        assertFalse(s.redToMove)
    }

    @Test
    fun `unknown capture keeps victim pool intact`() {
        // 红明兵 e5 吃 e6 黑暗子（victim 未知）
        val fen = "xxxxkxxxx/9/9/4x4/4P4/9/4K4/9/9/9 w R2A2C2P4N2B2r2a2c2p5n2b2 0 1"
        val (grid, redToMove, pools) = JieqiGameState.parseFen(fen)!!
        val s = JieqiGameState.fromImported(grid, redToMove, pools)
        assertNull(s.applyMove(4, 4, 3, 4, '.', '.'))
        assertEquals("e5e6", s.tokens.single())
        // 黑池不扣减（引擎 urn 语义）
        assertEquals(5, s.pools[1][5])
        // 身份不明被吃计数（红方暗子被吃 → 记在黑方一侧视角）
        assertEquals(1, s.darkCapturedUnknown[1])
        // 黑暗子消失（该 FEN 本有 9 个黑暗子）
        assertEquals(8, s.darkCount(false))
    }

    @Test
    fun `known capture decrements victim pool`() {
        val fen = "xxxxkxxxx/9/9/4x4/4P4/9/4K4/9/9/9 w R2A2C2P4N2B2r2a2c2p5n2b2 0 1"
        val (grid, redToMove, pools) = JieqiGameState.parseFen(fen)!!
        val s = JieqiGameState.fromImported(grid, redToMove, pools)
        assertNull(s.applyMove(4, 4, 3, 4, '.', 'p'))
        assertEquals("e5e6p", s.tokens.single())
        assertEquals(4, s.pools[1][5])
        assertEquals(0, s.darkCapturedUnknown[1])
    }

    @Test
    fun `undo restores full snapshot including pools and assignment`() {
        val s = JieqiGameState.newGame(Random(3))
        val snap = s.snapshot()
        // 红暗兵 e3 前进
        assertNull(s.applyMove(6, 4, 5, 4, '.', '.'))
        assertEquals(14, s.darkCount(true))
        assertEquals(14, s.poolTotal(true))
        // 悔棋
        s.restoreFrom(snap)
        assertEquals(15, s.darkCount(true))
        assertEquals(15, s.poolTotal(true))
        assertTrue(s.redToMove)
        assertEquals(0, s.tokens.size)
        // 分配恢复：揭晓过的身份回到池与分配
        val a = s.assignment!!
        var assigned = 0
        for (r in 0 until 10) for (c in 0 until 9) if (a[r][c] != '.') assigned++
        assertEquals(30, assigned)
    }

    @Test
    fun `facing generals is illegal`() {
        // 两王同列无遮挡：红帅 e0 (row9,col4)，黑将 e9 (row0,col4)
        val fen = "4k4/9/9/9/9/9/9/9/9/4K4 w R2A2C2P5N2B2r2a2c2p5n2b2 0 1"
        val (grid, redToMove, pools) = JieqiGameState.parseFen(fen)!!
        val s = JieqiGameState.fromImported(grid, redToMove, pools)
        // 红帅 e0→d0 合法（脱离照面）
        assertNull(s.applyMove(9, 4, 9, 3, '.', '.'))
    }

    @Test
    fun `parseFen rejects face-down piece off its start point`() {
        // 黑暗子放在 rank4（非法位置）
        val fen = "4k4/9/9/9/4x4/9/4K4/9/9/9 w R2A2C2P5N2B2r2a2c2p5n2b2 0 1"
        assertNull(JieqiGameState.parseFen(fen))
    }

    @Test
    fun `parseFen rejects pool smaller than dark count`() {
        val fen = "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX w R2A2C2P5N2B2r2a2c2p5n2b1 0 1"
        assertNull(JieqiGameState.parseFen(fen))
    }


    @Test
    fun `captured pieces are tracked with correct face`() {
        // 红明兵 e5 吃 黑暗子(未知) -> red captured 显示背面 'x'
        val fen = "xxxxkxxxx/9/9/4x4/4P4/9/4K4/9/9/9 w R2A2C2P4N2B2r2a2c2p5n2b2 0 1"
        val (grid, red, pools) = JieqiGameState.parseFen(fen)!!
        val s = JieqiGameState.fromImported(grid, red, pools)
        assertNull(s.applyMove(4, 4, 3, 4, '.', '.'))
        // 红方吃掉一枚未知身份黑暗子 -> 背面
        assertTrue(s.captured[0].size == 1)
        assertEquals(CapturedPiece('?', true), s.captured[0][0])
        assertEquals('X', s.capturedViewList(0, 1)[0])
        // 序列化往返保留
        val restored = JieqiGameState.newGame(java.util.Random(5)).deserialize(s.serialize())
        assertEquals(s.captured[0], restored.captured[0])
        assertEquals(s.captured[1], restored.captured[1])
        // 快照恢复保留
        val snap = s.snapshot()
        s.captured[0].clear()
        s.restoreFrom(snap)
        assertEquals(1, s.captured[0].size)
    }

    @Test
    fun `json round trip preserves state`() {
        val s = JieqiGameState.newGame(Random(9))
        assertNull(s.applyMove(6, 4, 5, 4, '.', '.'))
        val restored = JieqiGameState.newGame(Random(99)).let { it.deserialize(s.serialize()) }
        assertEquals(s.fen(), restored.fen())
        assertEquals(s.tokens, restored.tokens)
        assertEquals(s.redToMove, restored.redToMove)
        assertEquals(s.darkCapturedUnknown.toList(), restored.darkCapturedUnknown.toList())
        for (r in 0 until 10) for (c in 0 until 9)
            assertEquals(s.assignment!![r][c], restored.assignment!![r][c])
    }

    @Test
    fun `standard rules - horse destinations respect leg`() {
        val fen = "4k4/9/9/9/9/9/9/4N4/9/4K4 w R2A2C2P5N1B2r2a2c2p5n2b2 0 1"
        val (grid, redToMove, pools) = JieqiGameState.parseFen(fen)!!
        val s = JieqiGameState.fromImported(grid, redToMove, pools)
        val dests = JieqiRules.destinations(grid, 7, 4).map { it.first * 9 + it.second }.toSet()
        // e2 马：8 个方向全无蹩腿/阻挡
        for (d in listOf(5 * 9 + 3, 5 * 9 + 5, 9 * 9 + 3, 9 * 9 + 5, 6 * 9 + 2, 6 * 9 + 6, 8 * 9 + 2, 8 * 9 + 6))
            assertTrue("dest $d missing", d in dests)
        // 蹩腿：加一个子挡住上方马腿 (row6,col4)
        grid[6][4] = 'P'
        val blocked = JieqiRules.destinations(grid, 7, 4).map { it.first * 9 + it.second }.toSet()
        assertFalse(5 * 9 + 3 in blocked)
        assertFalse(5 * 9 + 5 in blocked)
        assertTrue(8 * 9 + 2 in blocked)
    }

    @Test
    fun `revealed advisor may leave palace diagonally`() {
        // 翻明的红仕放在九宫外(b2=row7,col1)，应能斜走；暗仕则不行
        // grid: row7 col1 放 'A'(明仕)，其余空
        val g = Array(10) { CharArray(9) { '.' } }
        g[0][4] = 'k'; g[9][4] = 'K'
        g[7][1] = 'A'   // 明仕在九宫外
        val dests = JieqiRules.destinations(g, 7, 1).map { it.first * 9 + it.second }.toSet()
        // 明仕斜走一格：可到 (6,0)(6,2)(8,0)(8,2) —— 不受九宫限制
        assertTrue("明仕应能斜走出九宫到6,0", 6 * 9 + 0 in dests)
        assertTrue("明仕应能斜走6,2", 6 * 9 + 2 in dests)
        assertTrue("明仕应能斜走8,0", 8 * 9 + 0 in dests)
        assertTrue("明仕应能斜走8,2", 8 * 9 + 2 in dests)
    }

    @Test
    fun `dark advisor confined to palace but revealed one not`() {
        // 暗仕只能在本方九宫内斜走；用起始位(row9,col3=仕位)的暗子
        val g = Array(10) { CharArray(9) { '.' } }
        g[0][4] = 'k'; g[9][4] = 'K'
        g[9][3] = 'X'   // 红暗仕(起始位 col3)
        val d = JieqiRules.destinations(g, 9, 3).map { it.first * 9 + it.second }.toSet()
        // 暗仕在 row9,col3 只能斜走到 row8,col4 (九宫内)
        assertTrue("暗仕应能走到九宫内8,4", 8 * 9 + 4 in d)
        assertFalse("暗仕不能出九宫到8,2", 8 * 9 + 2 in d)
    }

    @Test
    fun `revealed elephant may cross river but dark cannot`() {
        val g = Array(10) { CharArray(9) { '.' } }
        g[0][4] = 'k'; g[9][4] = 'K'
        // 明相在 row5(红半场靠河)，田字过河到 row3
        g[5][2] = 'B'
        val d = JieqiRules.destinations(g, 5, 2).map { it.first * 9 + it.second }.toSet()
        assertTrue("明相应能过河到3,0", 3 * 9 + 0 in d)
        assertTrue("明相应能过河到3,4", 3 * 9 + 4 in d)
        // 暗相在合法起始位(row9,col2)可走田字，但只在己方半场(row>=5)
        val g2 = Array(10) { CharArray(9) { '.' } }
        g2[0][4] = 'k'; g2[9][4] = 'K'; g2[9][2] = 'X'
        val d2 = JieqiRules.destinations(g2, 9, 2)
        assertTrue("暗相应能走田字到7,0", d2.any { it.first == 7 && it.second == 0 })
        assertTrue("暗相应能走田字到7,4", d2.any { it.first == 7 && it.second == 4 })
        assertTrue("暗相所有落点都在己方半场", d2.all { it.first >= 5 })
    }

    @Test
    fun `captured hidden piece visibility by observer`() {
        // 红方(0)吃了一个黑暗子，获知是黑卒 p；黑方观察者应看背面 X
        val s = JieqiGameState.newGame(java.util.Random(1))
        s.captured[0].add(CapturedPiece('p', true))
        s.captured[1].add(CapturedPiece('P', true))
        // 红吃子：红方(吃子方)看到真实 p
        assertEquals('p', s.capturedViewList(0, 0)[0])
        // 黑方看红方吃的暗子 -> 背面
        assertEquals('X', s.capturedViewList(0, 1)[0])
        // 明子双方可见：加一个明车
        s.captured[0].add(CapturedPiece('r', false))
        assertEquals('r', s.capturedViewList(0, 0)[1])
        assertEquals('r', s.capturedViewList(0, 1)[1])
        // 序列化往返(wasHidden 保留)
        val restored = JieqiGameState.newGame(java.util.Random(9)).deserialize(s.serialize())
        assertEquals(s.capturedViewList(0, 1), restored.capturedViewList(0, 1))
    }

    @Test
    fun `new game capturing dark piece records true identity from assignment`() {
        // 新局：红兵吃掉黑暗子，App 持有秘密分配，应能看到真身
        val s = JieqiGameState.newGame(java.util.Random(3))
        s.captured[0].clear(); s.captured[1].clear()
        // 找一个红兵暗子位(row6)与相邻黑暗子：直接构造 grid
        for (r in 0 until 10) for (c in 0 until 9) { s.grid[r][c] = '.' }
        s.grid[0][4] = 'k'; s.grid[9][4] = 'K'
        s.grid[4][4] = 'P'      // 红兵 e5
        s.grid[3][4] = 'x'      // 黑暗子 e6
        val assign = s.assignment!!
        for (r in 0 until 10) for (c in 0 until 9) assign[r][c] = '.'
        assign[3][4] = 'n'  // 该黑暗子真身=黑马
        // 重算池/FEN 起点
        s.startFen = JieqiGameState.fenOf(s.grid, true, s.pools)
        s.tokens.clear()

        val err = s.applyMove(4, 4, 3, 4, '.', '.')
        assertNull(err, err)
        // 吃子方红(0)看到真身 'n'
        assertEquals('n', s.capturedViewList(0, 0)[0])
        // 对方黑(1)看到统一背面
        assertEquals('X', s.capturedViewList(0, 1)[0])
        assertTrue(s.captured[0][0].wasHidden)
    }

    @Test
    fun `captured view differs between new game and scan mode`() {
        // 场景：黑方(eater=1)吃了红方一个暗子
        val s = JieqiGameState.newGame(java.util.Random(1))

        // --- 新局：App 全知，玩家是红方(observer=0) ---
        // 自动揭晓真实身份(如 'p' 红兵... 用黑方吃的红暗子 R 举例)
        s.captured[1].add(CapturedPiece('R', true))
        // 红方玩家看黑方吃的暗子 -> 背面(不知道)
        assertEquals('X', s.capturedViewList(1, 0)[0])
        // 黑方视角(若玩家执黑) -> 正面
        assertEquals('R', s.capturedViewList(1, 1)[0])

        // --- 扫描局：observer=null，直接按 truth 显示 ---
        val s2 = JieqiGameState.newGame(java.util.Random(2))
        s2.captured[0].clear(); s2.captured[1].clear()
        // AI(黑)吃玩家暗子：用户录入了身份 -> truth='R' -> 正面
        s2.captured[1].add(CapturedPiece('R', true))
        assertEquals('R', s2.capturedViewList(1, null)[0])
        // 玩家(红)吃AI暗子：看不到 -> truth='?' -> 背面
        s2.captured[0].add(CapturedPiece('?', true))
        assertEquals('X', s2.capturedViewList(0, null)[0])
        // 明子双方都可见
        s2.captured[0].add(CapturedPiece('r', false))
        assertEquals('r', s2.capturedViewList(0, 1)[1])
    }

    @Test
    fun `soldier crossing river gains sideways moves`() {
        val fen = "4k4/9/9/9/4P4/9/9/4K4/9/9 w R2A2C2P4N2B2r2a2c2p5n2b2 0 1"
        val (grid, redToMove, pools) = JieqiGameState.parseFen(fen)!!
        // 红兵 e5 (row5,rank4=未过河？rank4 属红方本土，未过河 → 只前进)
        val dests = JieqiRules.destinations(grid, 4, 4)
        // 过河兵：前进 + 左右横移
        assertEquals(3, dests.size)
    }
}
