package com.zfdang.chess.jieqi

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 揭棋扫描导入校验测试（A+C 方案）。
 *
 * 校验器从 assets/jieqi/dark_start_squares.json（转录自 AB-JChess tables.cpp）
 * 读取暗子初始位；单测用 loadFromJson 注入同一份定义以脱离 Android 环境。
 *
 * 覆盖：
 *  1) 合法中局中仍在初始位的暗子可正常导入
 *  2) 非初始位出现暗子 → 校验拦截并定位到具体格
 *  3) 棋盘旋转/方向切换后合法暗子不被误判
 *  4) 修正错误暗子后可成功导入
 *  5) FEN/将帅/池数量等其他失败原因有明确错误、且不丢状态
 */
class JieqiImportValidatorTest {

    // 与 assets/jieqi/dark_start_squares.json 内容一致（引擎 tables.cpp starts[] 的 (f,rank,role)）
    private val engineStarts = listOf(
        0 to 0 to 'R', 8 to 0 to 'R',
        1 to 0 to 'N', 7 to 0 to 'N',
        2 to 0 to 'B', 6 to 0 to 'B',
        3 to 0 to 'A', 5 to 0 to 'A',
        1 to 2 to 'C', 7 to 2 to 'C',
        0 to 3 to 'P', 2 to 3 to 'P',
        4 to 3 to 'P', 6 to 3 to 'P',
        8 to 3 to 'P'
    ).map { (fr, role) -> JieqiImportValidator.StartDef(fr.first, fr.second, role) }

    @Before
    fun setup() {
        JieqiImportValidator.loadFromStarts(engineStarts)
    }

    private fun emptyGrid(): Array<CharArray> = Array(10) { CharArray(9) { '.' } }

    /** 构造合法中局：双方帅将 + 若干明子 + 尚在原位的暗子 */
    private fun legalMidgame(): Array<CharArray> {
        val g = emptyGrid()
        g[0][4] = 'k'                 // 黑将（原位）
        g[9][4] = 'K'                 // 红帅（原位）
        g[0][0] = 'x'                 // 黑暗车：初始位（引擎红车 f0,r0 → 黑方 row0,f0）
        g[0][8] = 'x'                 // 黑暗车：初始位
        g[3][4] = 'p'                 // 黑卒过河（明子，翻明后可过河）
        g[9][0] = 'X'                 // 红暗车：初始位（引擎红车 f0,r0 → 红方 row9,f0）
        g[6][4] = 'P'                 // 红兵（初始暗兵位也是合法明子位，此处仅明子不影响）
        g[5][1] = 'a'                 // 明黑士出宫（揭棋允许）
        return g
    }

    @Test
    fun `legal midgame with darks on start squares imports successfully`() {
        val r = JieqiImportValidator.validate(legalMidgame(), redToMove = true)
        assertTrue("合法中局应通过校验，实际问题：${r.issues}", r.ok)
        assertNotNull(r.fen)
        assertTrue(r.fen!!.contains(" w "))
        // 生成的 FEN 应能被引擎方言解析器接受（App 侧 parseFen 对齐引擎）
        val parsed = JieqiGameState.parseFenWithError(r.fen!!)
        assertNull("App 解析应成功：${parsed.error}", parsed.error)
        assertNotNull(parsed.value)
    }

    @Test
    fun `dark piece off start square is rejected and located`() {
        val g = legalMidgame()
        // 在河中心 (r4,c4) 放一个黑暗子 —— 非初始位
        g[4][4] = 'x'
        val r = JieqiImportValidator.validate(g, redToMove = true)
        assertFalse(r.ok)
        val issue = r.issues.firstOrNull { it.code == "DARK_OFF_START" && it.cell == 4 * 9 + 4 }
        assertNotNull("应精确定位到第 5 行第 5 列的非法暗子", issue)
        assertTrue(issue!!.message.contains("暗子"))
        assertNull(r.fen)
    }

    @Test
    fun `rotated view and side flip keep legal darks valid`() {
        val base = legalMidgame()
        // 视图翻转（180°）只改变显示坐标，不改变 grid 数据 —— 校验应仍通过
        val r1 = JieqiImportValidator.validate(base, redToMove = true)
        assertTrue(r1.ok)
        // 改变先手方（b）不影响暗子位合法性
        val r2 = JieqiImportValidator.validate(base, redToMove = false)
        assertTrue(r2.ok)
        assertTrue(r2.fen!!.contains(" b "))
        // 红方暗子在红方初始位（row9,f0）合法；把它挪到黑方初始位仍是黑暗子则非法
        val g = legalMidgame()
        g[9][0] = '.'                 // 清掉红暗车
        g[0][0] = 'X'                 // 红暗车放到黑方半场初始位 → 红方暗子位表不允许
        val r3 = JieqiImportValidator.validate(g, redToMove = true)
        assertFalse("红暗子放在黑暗子位应被拒绝（红黑方向敏感）", r3.ok)
        assertTrue(r3.issues.any { it.code == "DARK_OFF_START" && it.cell == 0 })
    }

    @Test
    fun `fixing illegal dark makes import succeed`() {
        val g = legalMidgame()
        g[4][4] = 'x'                 // 非法暗子
        assertFalse(JieqiImportValidator.validate(g, true).ok)
        // 用户修正：把河中心暗子改成明黑卒（翻明后可在此）
        g[4][4] = 'p'
        val fixed = JieqiImportValidator.validate(g, true)
        assertTrue("修正后应通过：${fixed.issues}", fixed.ok)
        assertNotNull(fixed.fen)
        assertNull(JieqiGameState.parseFenWithError(fixed.fen!!).error)
    }

    @Test
    fun `other import failures carry explicit reasons without losing state`() {
        // 将帅数量错误
        val g1 = legalMidgame(); g1[9][4] = '.'
        val r1 = JieqiImportValidator.validate(g1, true)
        assertFalse(r1.ok)
        assertTrue(r1.issues.any { it.code == "RED_KING" && it.message.contains("1 个帅") })

        // 双将（红方多一个帅）
        val g2 = legalMidgame(); g2[9][0] = 'K'
        val r2 = JieqiImportValidator.validate(g2, true)
        assertFalse(r2.ok)
        assertTrue(r2.issues.any { it.code == "RED_KING" })

        // 非法棋子字符
        val g3 = legalMidgame(); g3[5][5] = 'Q'
        val r3 = JieqiImportValidator.validate(g3, true)
        assertFalse(r3.ok)
        assertTrue(r3.issues.any { it.code == "BAD_PIECE" && it.cell == 5 * 9 + 5 })

        // parseFenWithError：FEN 字段错误给出具体原因（不静默返回 null）
        val bad = JieqiGameState.parseFenWithError("4k4/9/9 w - - 0 1")
        assertNull(bad.value)
        assertTrue(bad.error!!.contains("10 行"))
    }

    @Test
    fun `pool clamp never exceeds engine urn init`() {
        // 大量明车导致车池扣到 0，暗子多时钳制补足但单槽不得超过 2
        val g = emptyGrid()
        g[0][4] = 'k'; g[9][4] = 'K'
        // 红方 15 个暗子全部在初始位
        val redDarkSquares = listOf(9 to 0, 9 to 8, 9 to 1, 9 to 7, 9 to 2, 9 to 6,
            9 to 3, 9 to 5, 7 to 1, 7 to 7, 6 to 0, 6 to 2, 6 to 4, 6 to 6, 6 to 8)
        redDarkSquares.forEach { (r, c) -> g[r][c] = 'X' }
        val r = JieqiImportValidator.validate(g, true)
        assertTrue("15 红暗子+空池应可钳制导入：${r.issues}", r.ok)
        // 引擎方言解析器（App 侧对齐）必须接受该池
        assertNull(JieqiGameState.parseFenWithError(r.fen!!).error)
    }

    @Test
    fun `dark side is determined solely by start square position`() {
        // 暗子背面无红黑，X/x 只是引擎 FEN 编码；2 个暗子虽被标成 X，
        // 但位置在黑方初始位，按规则其归属必为黑（小写 x）
        val g = legalMidgame()
        g[0][0] = 'X'   // 黑车初始位(row0,c0)却标成红暗子 → 应为 x
        g[3][6] = 'X'   // 黑炮初始位(row3,c6)却标成红暗子 → 应为 x
        val before = JieqiImportValidator.validate(g, true)
        assertFalse("纠正前应被拦截", before.ok)
        val changed = JieqiImportValidator.normalizeDarkSides(g)
        assertEquals(2, changed.size)
        assertEquals('x', g[0][0])
        assertEquals('x', g[3][6])
        val after = JieqiImportValidator.validate(g, true)
        assertTrue("按位置纠正后应可导入：${after.issues}", after.ok)
        assertNull(JieqiGameState.parseFenWithError(after.fen!!).error)
        // 幂等：再纠正一次不应再改动
        assertTrue(JieqiImportValidator.normalizeDarkSides(g).isEmpty())
    }

    @Test
    fun `captured revealed pieces are deducted from identity pool`() {
        // 盘面无红马，无补充时红马池=初始2；补充"黑方吃了2个红马"后红马池应扣到0
        val g = emptyGrid()
        g[0][4] = 'k'; g[9][4] = 'K'
        g[9][0] = 'X'   // 红暗车（初始位）
        val without = JieqiImportValidator.validate(g, true, null)
        assertTrue(without.ok)
        val fenWithout = without.fen!!

        // 新约定：黑方吃掉红子放黑组(大写)。"黑方吃了2个红马"→ 黑组 NN
        val with = JieqiImportValidator.validate(g, true, ";NN,0")
        assertTrue("补充吃子后仍应合法：${with.issues}", with.ok)
        val fenWith = with.fen!!
        // FEN 第 3 字段（身份池）：无补充含 N2，补充后红马池应被扣掉
        fun poolField(fen: String) = fen.split(' ')[2]
        assertTrue("无补充应含 N2：${poolField(fenWithout)}", poolField(fenWithout).contains("N2"))
        assertFalse("补充后红马池应减少：${poolField(fenWith)}",
            poolField(fenWith).contains("N2"))
    }

    @Test
    fun `captured dark unknown does not deduct pool`() {
        // 补充的暗子数（x/X）不应扣任何身份池槽（未知身份引擎概率处理）
        // 新约定：红方吃黑子小写、黑方吃红子大写
        val slots = JieqiCaptures.capturedRevealedSlots("rr,3;PP,2")
        assertEquals(2, slots[1][0])  // 红方吃掉2个黑车(r) → 黑车槽(side1,slot0) +2
        assertEquals(0, slots[1][5])  // 黑兵槽不受"3个红方吃的暗子"影响
        assertEquals(2, slots[0][5])  // 黑方吃掉2个红兵(P) → 红兵槽(side0,slot5) +2
    }
}
