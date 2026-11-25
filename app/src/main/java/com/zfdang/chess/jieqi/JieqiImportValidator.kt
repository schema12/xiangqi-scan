package com.zfdang.chess.jieqi

import android.content.Context
import org.json.JSONObject

/**
 * 揭棋导入前的结构化校验（A+C 方案）。
 *
 * 暗子初始位不在这里硬编码——从 assets/jieqi/dark_start_squares.json 读取，
 * 该资产逐字转录自 AB-JChess `abjchess/src/tables.cpp init_tables()` 的 starts[]。
 * 引擎 `set_fen_impl` 用同一张表判定 `face-down piece off its start point`，
 * 因此 App 与引擎共用一份定义，避免两套表不一致。
 *
 * 校验项与 AB-JChess `set_fen_impl` 对齐：
 *  - FEN 字段数/棋盘 10×9/字符合法
 *  - 暗子必须在初始暗子位（face-down piece off its start point）
 *  - 每方恰好一个将/帅（board must contain exactly one king per side）
 *  - 每方棋子数 ≤ 16（too many pieces）
 *  - 身份池字母合法、无重复、每槽 ≤ URN_INIT（bad pool letter / duplicate / pool count too large）
 *  - 每方池总数 ≥ 该方暗子数（pool smaller than face-down count）
 */
object JieqiImportValidator {

    data class Issue(
        val cell: Int,          // -1 = 全局问题；否则 r*9+c
        val code: String,
        val message: String
    )

    data class Validation(
        val ok: Boolean,
        val issues: List<Issue>,
        val fen: String?        // 校验通过时的引擎方言 FEN
    )

    // 每槽初始上限（引擎 URN_INIT，slot: R N B A C P）
    private val URN_INIT = intArrayOf(2, 2, 2, 2, 2, 5)
    private val SLOT_LETTER = charArrayOf('R', 'N', 'B', 'A', 'C', 'P')

    @Volatile
    private var loaded = false
    private val darkRoleRed = HashMap<Long, Char>()
    private val darkRoleBlack = HashMap<Long, Char>()

    /** 从资产加载引擎暗子位定义（幂等）。加载失败回退到 JieqiRules（并记 warning）。 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open("jieqi/dark_start_squares.json")
                    .bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONArray("starts")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val f = o.getInt("f")
                    val rank = o.getInt("r")    // 引擎 rank 0 = 红底线
                    val role = o.getString("role")[0]
                    val rowRed = 9 - rank       // 红方：引擎 rank → App row
                    val rowBlack = rank         // 黑方：引擎 rank → App row（镜像）
                    darkRoleRed[rowRed * 100L + f] = role
                    darkRoleBlack[rowBlack * 100L + f] = role
                }
                loaded = true
            } catch (e: Exception) {
                // 资产缺失/损坏：回退到 App 内同一份坐标定义，不让校验整体失效
                for (r in 0 until 10) for (c in 0 until 9) {
                    val roleRed = JieqiRules.darkRole(r, c, true)
                    val roleBlack = JieqiRules.darkRole(r, c, false)
                    if (roleRed != '.') darkRoleRed[r * 100L + c] = roleRed
                    if (roleBlack != '.') darkRoleBlack[r * 100L + c] = roleBlack
                }
                loaded = true
            }
        }
    }

    /**
     * 按揭棋规则规范化暗子所属方（校对阶段调用，不是识别阶段）。
     *
     * 依据：未翻暗子永远待在开局初始位；引擎初始位按红黑半场严格分离
     * （红方 row6/7/9，黑方 row0/2/3）。因此位于黑方初始位的暗子只可能是黑暗子，
     * 位于红方初始位的暗子只可能是红暗子——这是规则确定性推论，不是模型猜测。
     * 模型对背面颜色判反时，用位置纠正可彻底消除这类非法局面。
     *
     * 只纠正"位于某方合法初始位、但颜色标成对方"的暗子；位于双方都非初始位的
     * 暗子不动（留给 [validate] 报错并由用户改明子/空位）。
     * @return 被改动的格子索引列表（r*9+c），供 UI 透明提示用户
     */
    fun normalizeDarkSides(grid: Array<CharArray>): List<Int> {
        ensureLoadedInternal()
        val changed = ArrayList<Int>()
        for (r in 0 until 10) for (c in 0 until 9) {
            val ch = grid[r][c]
            if (ch != 'X' && ch != 'x') continue
            val red = ch == 'X'
            if (isDarkStart(r, c, red)) continue          // 颜色与位置一致，不动
            if (isDarkStart(r, c, !red)) {
                grid[r][c] = if (red) 'x' else 'X'        // 位置确定所属方，纠正
                changed += r * 9 + c
            }
            // 双方都不是初始位：不动，交给 validate 报错
        }
        return changed
    }

    private fun ensureLoadedInternal() {
        // ensureLoaded 需要 Context；此处若尚未加载，用 JieqiRules 兜底（两者坐标一致）
        if (loaded) return
        for (r in 0 until 10) for (c in 0 until 9) {
            val rr = JieqiRules.darkRole(r, c, true)
            val rb = JieqiRules.darkRole(r, c, false)
            if (rr != '.') darkRoleRed[r * 100L + c] = rr
            if (rb != '.') darkRoleBlack[r * 100L + c] = rb
        }
        loaded = true
    }

    /** 给定格是否是引擎认可的暗子位 */
    fun isDarkStart(r: Int, c: Int, red: Boolean): Boolean {
        val table = if (red) darkRoleRed else darkRoleBlack
        return table.containsKey(r * 100L + c)
    }

    private fun slotOf(ch: Char): Int {
        val up = ch.uppercaseChar()
        for (s in 0 until 6) if (SLOT_LETTER[s] == up) return s
        return -1
    }

    /** 由 grid 推导池：严格按引擎 URN_INIT 钳制，且保证池总数 ≥ 暗子数 */
    private fun derivePoolsStrict(grid: Array<CharArray>, redToMove: Boolean): Array<IntArray> {
        val pools = Array(2) { intArrayOf(2, 2, 2, 2, 2, 5) }
        for (r in 0 until 10) for (c in 0 until 9) {
            val ch = grid[r][c]
            if (ch == '.' || JieqiRules.isDark(ch)) continue
            val side = if (JieqiRules.isRed(ch)) 0 else 1
            val slot = slotOf(ch)
            if (slot >= 0 && pools[side][slot] > 0) pools[side][slot]--
        }
        for (side in 0..1) {
            var dark = 0
            for (r in 0 until 10) for (c in 0 until 9)
                if (JieqiRules.isDark(grid[r][c]) && JieqiRules.isRed(grid[r][c]) == (side == 0)) dark++
            var total = pools[side].sum()
            // 按最大剩余槽补足，单槽不超 URN_INIT（引擎 pool count too large 约束）
            while (total < dark) {
                var idx = -1; var slack = 0
                for (s in 0 until 6) {
                    val room = URN_INIT[s] - pools[side][s]
                    if (room > slack) { slack = room; idx = s }
                }
                if (idx < 0 || slack == 0) break
                pools[side][idx]++
                total++
            }
        }
        return pools
    }

    private fun fenOf(grid: Array<CharArray>, redToMove: Boolean, pools: Array<IntArray>): String {
        val sb = StringBuilder()
        for (r in 0 until 10) {
            var empty = 0
            for (c in 0 until 9) {
                val ch = grid[r][c]
                if (ch == '.') empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 9) sb.append('/')
        }
        sb.append(if (redToMove) " w " else " b ")
        val pool = StringBuilder()
        val order = listOf(0, 3, 4, 5, 1, 2)  // 引擎池输出顺序 R A C P N B
        for (cc in 0..1) for (slot in order) {
            val n = pools[cc][slot]
            if (n > 0) {
                val letter = if (cc == 0) SLOT_LETTER[slot] else SLOT_LETTER[slot].lowercaseChar()
                pool.append(letter).append(n)
            }
        }
        sb.append(if (pool.isEmpty()) "-" else pool.toString())
        sb.append(" 0 ").append(if (redToMove) 1 else 2)
        return sb.toString()
    }

    /**
     * 校验扫描校对后的 grid，返回结构化结果。
     * @param grid 10×9，'.'=空，X/x=暗子，其余 FEN 字符=明子
     * @param redToMove 引擎方言先手方
     */
    fun validate(grid: Array<CharArray>, redToMove: Boolean): Validation {
        val issues = ArrayList<Issue>()

        if (grid.size != 10 || grid.any { it.size != 9 }) {
            return Validation(false, listOf(Issue(-1, "BOARD_SIZE", "棋盘必须是 10 行 × 9 列")), null)
        }

        var kings = intArrayOf(0, 0)
        var pieces = intArrayOf(0, 0)
        var darks = intArrayOf(0, 0)
        for (r in 0 until 10) for (c in 0 until 9) {
            val ch = grid[r][c]
            if (ch == '.') continue
            val red = JieqiRules.isRed(ch)
            val side = if (red) 0 else 1
            pieces[side]++
            when {
                ch == 'K' || ch == 'k' -> kings[side]++
                JieqiRules.isDark(ch) -> {
                    darks[side]++
                    if (!isDarkStart(r, c, red)) {
                        // 区分两种失败：
                        //  (a) 该格是「对方」暗子的合法初始位 → 几乎肯定是所属方识别错（红/黑背面判反），
                        //      应提示改成对方暗子；这是揭棋扫描最常见的错误。
                        //  (b) 该格双方都不是初始位 → 位置真的错了（模型把明子/空位误判成暗子），
                        //      应提示改成明子或空位。
                        val opponentSide = isDarkStart(r, c, !red)
                        val msg = if (opponentSide) {
                            "第${r + 1}行第${c + 1}列的${if (red) "红" else "黑"}暗子所属方可能识别反了：" +
                                "这个位置只可能是${if (red) "黑" else "红"}暗子，请从候选栏选${if (red) "黑暗子" else "红暗子"}重放此格"
                        } else {
                            "第${r + 1}行第${c + 1}列的${if (red) "红" else "黑"}暗子不在任何初始暗子位" +
                                "（未翻暗子只能待在开局原位；请改为明子或清空此格）"
                        }
                        issues += Issue(r * 9 + c, "DARK_OFF_START", msg)
                    }
                }
                else -> {
                    if (slotOf(ch) < 0) {
                        issues += Issue(r * 9 + c, "BAD_PIECE",
                            "第${r + 1}行第${c + 1}列出现未知棋子 '$ch'")
                    }
                }
            }
        }
        if (kings[0] != 1) issues += Issue(-1, "RED_KING", "红方必须恰好有 1 个帅（当前 ${kings[0]} 个）")
        if (kings[1] != 1) issues += Issue(-1, "BLACK_KING", "黑方必须恰好有 1 个将（当前 ${kings[1]} 个）")
        for (side in 0..1) {
            if (pieces[side] > 16) {
                issues += Issue(-1, if (side == 0) "TOO_MANY_RED" else "TOO_MANY_BLACK",
                    "${if (side == 0) "红" else "黑"}方棋子数 ${pieces[side]} 超过 16")
            }
        }

        val pools = derivePoolsStrict(grid, redToMove)
        for (side in 0..1) {
            val total = pools[side].sum()
            if (total < darks[side]) {
                issues += Issue(-1, "POOL_TOO_SMALL",
                    "${if (side == 0) "红" else "黑"}方身份池总数 $total 少于暗子数 ${darks[side]}")
            }
        }

        val fen = if (issues.isEmpty()) fenOf(grid, redToMove, pools) else null
        return Validation(issues.isEmpty(), issues, fen)
    }

    /** 供测试使用的注入式加载（无需 org.json，避免 JVM 单测的 android.jar stub 问题） */
    data class StartDef(val f: Int, val rank: Int, val role: Char)

    fun loadFromStarts(starts: List<StartDef>) {
        darkRoleRed.clear(); darkRoleBlack.clear()
        for (s in starts) {
            darkRoleRed[(9 - s.rank) * 100L + s.f] = s.role
            darkRoleBlack[s.rank * 100L + s.f] = s.role
        }
        loaded = true
    }

    /** 供运行时从 JSON 资产加载（保留，供 ensureLoaded 复用解析） */
    fun loadFromJson(json: String) {
        val arr = JSONObject(json).getJSONArray("starts")
        val starts = ArrayList<StartDef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            starts += StartDef(o.getInt("f"), o.getInt("r"), o.getString("role")[0])
        }
        loadFromStarts(starts)
    }
}
