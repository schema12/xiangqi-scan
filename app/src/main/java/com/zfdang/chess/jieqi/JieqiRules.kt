package com.zfdang.chess.jieqi

/**
 * 揭棋规则引擎（与 AB-JChess position.cpp 精确对齐的 Kotlin 移植）。
 *
 * 坐标约定：grid[row][col]，row 0 = rank 9（黑方底线在上），row 9 = rank 0（红方底线在下）；
 * col 0 = file a。棋子字符：大写红（K A B N R C P），小写黑（k a b n r c p），
 * 'X' 红暗子，'x' 黑暗子，'.' 空。
 *
 * 规则模型（与 jqe/AB-JChess 一致）：
 *  - 暗子只能位于其初始位置；其走法按该位置的初始角色生成（"movement role is fixed by the square"）
 *  - 暗子移动时必须揭晓（揭晓身份 ∈ 该方身份池即可，引擎不校验走法与身份的几何一致性）
 *  - 将/帅永远不是暗子；将/帅不可被吃（目标格排除对方将/帅）
 *  - 合法性：走后己方将/帅不得被攻击（含将帅照面：对方帅沿直线视为攻击子）
 */
object JieqiRules {

    const val DARK_RED = 'X'
    const val DARK_BLACK = 'x'

    /** 各方暗子的初始角色表：暗子位 → 初始角色。红方初始位；黑方按 rank 镜像。 */
    private val darkRoleRed = HashMap<Long, Char>(15)
    private val darkRoleBlack = HashMap<Long, Char>(15)

    init {
        // 红方初始位（row = 9 - rank）：车 a0/i0，马 b0/h0，相 c0/g0，仕 d0/f0，炮 b2/h2，兵 a3/c3/e3/g3/i3
        val redStarts = listOf(
            9 to 0 to 'R', 9 to 8 to 'R',
            9 to 1 to 'N', 9 to 7 to 'N',
            9 to 2 to 'B', 9 to 6 to 'B',
            9 to 3 to 'A', 9 to 5 to 'A',
            7 to 1 to 'C', 7 to 7 to 'C',
            6 to 0 to 'P', 6 to 2 to 'P', 6 to 4 to 'P', 6 to 6 to 'P', 6 to 8 to 'P'
        )
        for ((sq, t) in redStarts) {
            darkRoleRed[sq.first * 100L + sq.second] = t
            darkRoleBlack[(9 - sq.first) * 100L + sq.second] = t
        }
    }

    fun isRed(ch: Char): Boolean = ch != '.' && ch.isUpperCase()
    fun isDark(ch: Char): Boolean = ch == DARK_RED || ch == DARK_BLACK

    /** 暗子在 (r,c) 的走法角色（按其颜色与所在格的初始角色）。非暗子返回 '.'。 */
    fun darkRole(r: Int, c: Int, red: Boolean): Char {
        val table = if (red) darkRoleRed else darkRoleBlack
        return table[r * 100L + c] ?: '.'
    }

    /** 暗子所在格的走法角色（明子返回自身类型）。 */
    private fun roleAt(grid: Array<CharArray>, r: Int, c: Int): Char {
        val p = grid[r][c]
        if (!isDark(p)) return p
        val role = darkRole(r, c, isRed(p))
        require(role != '.') { "dark piece off its start point at $r,$c" }
        return role
    }

    private fun inPalace(r: Int, c: Int): Boolean = c in 3..5 && r in 0..2 || c in 3..5 && r in 7..9
    private fun inPalaceOf(r: Int, c: Int, red: Boolean): Boolean =
        c in 3..5 && (if (red) r in 7..9 else r in 0..2)
    private fun ownHalf(r: Int, c: Int, red: Boolean): Boolean = if (red) r >= 5 else r <= 4
    private fun onBoard(r: Int, c: Int) = r in 0..9 && c in 0..8

    // ------------------------------------------------------------------
    // 攻击检测（attacked_bb 移植）：k 是否被 red 方棋子攻击
    // ------------------------------------------------------------------

    private val DIAG = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val ORTHO = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val HORSE_D = listOf(2 to 1, 2 to -1, -2 to 1, -2 to -1, 1 to 2, 1 to -2, -1 to 2, -1 to -2)

    /** (kr,kc) 是否被 red 方棋子攻击。grid 为当前局面（未走子）。 */
    fun attacked(grid: Array<CharArray>, kr: Int, kc: Int, byRed: Boolean): Boolean {
        fun enemyOf(p: Char) = p != '.' && isRed(p) == byRed
        fun roleAtAbs(r: Int, c: Int): Char {
            val p = grid[r][c]
            if (!isDark(p)) return p
            return darkRole(r, c, isRed(p))
        }

        // 车炮/对将：四方向第一、第二遮挡子
        for ((dr, dc) in ORTHO) {
            var r = kr + dr; var c = kc + dc
            var first = true
            while (onBoard(r, c)) {
                val p = grid[r][c]
                if (p != '.') {
                    val role = roleAtAbs(r, c)
                    if (first) {
                        if (role == (if (byRed) 'R' else 'r') && enemyOf(p)) return true
                        // 将帅照面：纵向第一遮挡子是对方将/帅
                        if (dc == 0 && role == (if (byRed) 'K' else 'k') && enemyOf(p)) return true
                        first = false
                    } else {
                        if (role == (if (byRed) 'C' else 'c') && enemyOf(p)) return true
                        break
                    }
                }
                r += dr; c += dc
            }
        }
        // 马：从 k 的马位攻击（蹩腿在攻击子与 k 之间靠攻击子一侧）
        for ((dr, dc) in HORSE_D) {
            val pr = kr + dr; val pc = kc + dc
            if (!onBoard(pr, pc)) continue
            val p = grid[pr][pc]
            if (p == '.' || !enemyOf(p)) continue
            if (roleAtAbs(pr, pc) != (if (byRed) 'N' else 'n')) continue
            // 腿位：攻击子朝 k 的长轴方向一格
            val lr = pr + if (kotlin.math.abs(dr) == 2) (if (dr > 0) -1 else 1) else 0
            val lc = pc + if (kotlin.math.abs(dc) == 2) (if (dc > 0) -1 else 1) else 0
            if (grid[lr][lc] == '.') return true
        }
        // 兵
        val fr = if (byRed) kr + 1 else kr - 1   // 前进方向上的攻击子
        if (onBoard(fr, kc)) {
            val p = grid[fr][kc]
            if (p != '.' && enemyOf(p) && roleAtAbs(fr, kc) == (if (byRed) 'P' else 'p')) return true
        }
        val crossed = if (byRed) kr <= 4 else kr >= 5   // 攻击子过河后才可横吃
        if (crossed) {
            for (dc in listOf(-1, 1)) {
                val pc = kc + dc
                if (!onBoard(kr, pc)) continue
                val p = grid[kr][pc]
                if (p != '.' && enemyOf(p) && roleAtAbs(kr, pc) == (if (byRed) 'P' else 'p')) return true
            }
        }
        // 仕：斜邻格（暗仕仅当目标在其宫内）
        for ((dr, dc) in DIAG) {
            val pr = kr + dr; val pc = kc + dc
            if (!onBoard(pr, pc)) continue
            val p = grid[pr][pc]
            if (p == '.' || !enemyOf(p)) continue
            if (roleAtAbs(pr, pc) != (if (byRed) 'A' else 'a')) continue
            if (isDark(p) && !inPalaceOf(kr, kc, byRed)) continue
            return true
        }
        // 相/象：田字目标格 + 眼位；暗象仅当目标在其半场
        for ((dr, dc) in listOf(2 to 2, 2 to -2, -2 to 2, -2 to -2)) {
            val pr = kr + dr; val pc = kc + dc
            if (!onBoard(pr, pc)) continue
            val p = grid[pr][pc]
            if (p == '.' || !enemyOf(p)) continue
            if (roleAtAbs(pr, pc) != (if (byRed) 'B' else 'b')) continue
            if (grid[kr + dr / 2][kc + dc / 2] != '.') continue
            if (isDark(p) && !ownHalf(kr, kc, byRed)) continue
            return true
        }
        return false
    }

    /** 找到 red 方将/帅位置；供宿主做终局判断。 */
    fun findKing(grid: Array<CharArray>, red: Boolean): Pair<Int, Int>? {
        val k = if (red) 'K' else 'k'
        for (r in 0 until 10) for (c in 0 until 9) if (grid[r][c] == k) return r to c
        return null
    }

    /** red 方将/帅当前是否被攻击（含将帅照面）。 */
    fun inCheck(grid: Array<CharArray>, red: Boolean): Boolean {
        val (kr, kc) = findKing(grid, red) ?: return false
        return attacked(grid, kr, kc, !red)
    }

    // ------------------------------------------------------------------
    // 走法生成（gen 移植）：伪合法目标格
    // ------------------------------------------------------------------

    /** (fr,fc) 棋子的全部伪合法目标（含吃子；排除吃对方将/帅）。 */
    fun destinations(grid: Array<CharArray>, fr: Int, fc: Int): List<Pair<Int, Int>> {
        val p = grid[fr][fc]
        if (p == '.') return emptyList()
        val red = isRed(p)
        val out = ArrayList<Pair<Int, Int>>(18)
        fun canLand(r: Int, c: Int): Boolean {
            if (!onBoard(r, c)) return false
            val t = grid[r][c]
            if (t == '.') return true
            if (isRed(t) == red) return false                      // 己方子
            if (t == 'K' || t == 'k') return false                 // 将/帅不可被吃
            return true
        }

        val role = roleAt(grid, fr, fc)
        val dark = isDark(p)   // 暗子(未翻明)：按起始位兵种走，且受九宫/河界限制；明子:真实兵种，不受限
        when (role.uppercaseChar()) {
            'K' -> for ((dr, dc) in ORTHO) {
                val r = fr + dr; val c = fc + dc
                if (inPalace(fr, fc) && inPalace(r, c) && canLand(r, c)) out.add(r to c)
            }
            'A' -> for ((dr, dc) in DIAG) {
                val r = fr + dr; val c = fc + dc
                // 仅暗士受九宫限制；翻明的真士可出九宫、可从九宫外斜走
                if (!onBoard(r, c) || !canLand(r, c)) continue
                if (dark && !inPalaceOf(r, c, red)) continue
                out.add(r to c)
            }
            'B' -> for ((dr, dc) in listOf(2 to 2, 2 to -2, -2 to 2, -2 to -2)) {
                val r = fr + dr; val c = fc + dc
                if (!onBoard(r, c) || !canLand(r, c)) continue
                if (grid[fr + dr / 2][fc + dc / 2] != '.') continue   // 塞象眼(明暗都有效)
                // 仅暗象受河界限制；翻明的真象可过河
                if (dark && !ownHalf(r, c, red)) continue
                out.add(r to c)
            }
            'N' -> for ((dr, dc) in HORSE_D) {
                val r = fr + dr; val c = fc + dc
                if (!onBoard(r, c) || !canLand(r, c)) continue
                val lr = fr + if (kotlin.math.abs(dr) == 2) dr / 2 else 0
                val lc = fc + if (kotlin.math.abs(dc) == 2) dc / 2 else 0
                if (grid[lr][lc] == '.') out.add(r to c)
            }
            'R' -> for ((dr, dc) in ORTHO) {
                var r = fr + dr; var c = fc + dc
                while (onBoard(r, c) && grid[r][c] == '.') { out.add(r to c); r += dr; c += dc }
                if (onBoard(r, c) && canLand(r, c)) out.add(r to c)
            }
            'C' -> for ((dr, dc) in ORTHO) {
                var r = fr + dr; var c = fc + dc
                while (onBoard(r, c) && grid[r][c] == '.') { out.add(r to c); r += dr; c += dc }
                var screen = false
                while (onBoard(r, c)) {
                    if (!screen) { if (grid[r][c] != '.') screen = true }
                    else { if (grid[r][c] != '.' && canLand(r, c)) { out.add(r to c); break } }
                    r += dr; c += dc
                }
            }
            'P' -> {
                val fwd = if (red) fr - 1 else fr + 1
                if (onBoard(fwd, fc) && canLand(fwd, fc)) out.add(fwd to fc)
                if (!ownHalf(fr, fc, red)) {
                    for (dc in listOf(-1, 1)) {
                        val c = fc + dc
                        if (onBoard(fr, c) && canLand(fr, c)) out.add(fr to c)
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 合法性（legal 移植）：走后己方将/帅不被攻击（含照面）
    // ------------------------------------------------------------------

    /** 走这步后己方帅/将是否会被攻击（送将/照面）。供 UI 区分非法原因。 */
    fun wouldExposeKing(grid: Array<CharArray>, red: Boolean, fr: Int, fc: Int, tr: Int, tc: Int): Boolean {
        if (fr !in 0..9 || fc !in 0..8 || tr !in 0..9 || tc !in 0..8) return false
        // 构造走后局面（仅影响攻击检测；暗子按原字符留在目标格）
        val piece = grid[fr][fc]
        val victim = grid[tr][tc]
        grid[fr][fc] = '.'; grid[tr][tc] = piece
        val bad = inCheck(grid, red)
        grid[tr][tc] = victim; grid[fr][fc] = piece
        return bad
    }

    /** (fr,fc)→(tr,tc) 是否为完全合法走法（伪合法 + 走后不被将军/照面）。 */
    fun isLegal(grid: Array<CharArray>, redToMove: Boolean, fr: Int, fc: Int, tr: Int, tc: Int): Boolean {
        if (fr !in 0..9 || fc !in 0..8) return false
        val p = grid[fr][fc]
        if (p == '.') return false
        if (isRed(p) != redToMove) return false
        if (tr !in 0..9 || tc !in 0..8) return false
        val t = grid[tr][tc]
        if (t != '.') {
            if (isRed(t) == redToMove) return false
            if (t == 'K' || t == 'k') return false
        }
        if ((tr to tc) !in destinations(grid, fr, fc)) return false
        return !wouldExposeKing(grid, redToMove, fr, fc, tr, tc)
    }

    /** 当前局面全部合法走法（引擎 gen_legal 对齐）。 */
    fun allLegalMoves(grid: Array<CharArray>, redToMove: Boolean): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>(40)
        for (r in 0 until 10) for (c in 0 until 9) {
            val p = grid[r][c]
            if (p == '.' || isRed(p) != redToMove) continue
            for ((tr, tc) in destinations(grid, r, c)) {
                if (!wouldExposeKing(grid, redToMove, r, c, tr, tc)) out.add(Triple(r, c, tr * 9 + tc))
            }
        }
        return out
    }
}
