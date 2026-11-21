package com.zfdang.chess.jieqi

import java.util.Random

/**
 * 被吃棋子记录（对应参考 engine.js sanitize 的 captured 项）。
 * @param ch 真实棋子字母（明子=自身；暗子=揭晓后的真实身份；未知='?'）
 * @param wasHidden 被吃时是否为未翻暗子（hiddenAtCapture）
 *
 * 可见性：明子双方可见；暗子仅「吃子方」可见真实身份，对方看到统一背面。
 */
data class CapturedPiece(val ch: Char, val wasHidden: Boolean)

/**
 * 揭棋对局状态（App 端唯一事实源）。
 *
 * - grid[row][col]：row0=rank9。'X' 红暗子、'x' 黑暗子、明子字母、'.' 空。
 * - pools[c][slot]：c 0=红 1=黑；slot 0..5 = {R,N,B,A,C,P} 未揭晓身份余量；与引擎 urn 严格同步。
 * - assignment：新开局时 App 秘密持有的真实暗子身份（引擎永远不知道）；扫描导入为 null。
 * - startFen + tokens：引擎视角历史。position fen <startFen> moves <tokens...> 可随时重建引擎局面。
 * - undoStack：全量快照，悔棋时整体回滚（棋盘/池/分配/行棋方/token 历史）。
 *
 * token：4 坐标 + [揭晓字母（暗子移动必带，行棋方颜色大小写）] + [被吃暗子身份（可选，
 * victim 颜色大小写；省略 = 保持未知，引擎原生支持）]。
 */
class JieqiGameState private constructor(
    val grid: Array<CharArray>,
    var redToMove: Boolean,
    val pools: Array<IntArray>,
    val assignment: Array<CharArray>?,
    var startFen: String,
    val tokens: MutableList<String>,
    var startRedToMove: Boolean,
    /** 各方「身份不明的被吃暗子」计数（校对面板展示；引擎池不扣减） */
    val darkCapturedUnknown: IntArray,
    /** 对局记录（人类可读，一条一手） */
    val moveRecords: MutableList<String>,
    /** 双方吃掉的棋子，captured[0]=红方吃到、captured[1]=黑方吃到。
     *  存真实信息（含暗子真实身份与 wasHidden），显示时按观察者视角过滤。 */
    val captured: Array<MutableList<CapturedPiece>> = arrayOf(mutableListOf(), mutableListOf())
) {
    companion object {
        const val START_FEN_BOARD = "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX"
        val URN_INIT = intArrayOf(2, 2, 2, 2, 2, 5) // slot: R N B A C P
        val SLOT_LETTER = charArrayOf('R', 'N', 'B', 'A', 'C', 'P')
        const val POOL_RED = "R2A2C2P5N2B2"
        const val POOL_BLACK = "r2a2c2p5n2b2"

        private fun fullPools(): Array<IntArray> =
            arrayOf(intArrayOf(2, 2, 2, 2, 2, 5), intArrayOf(2, 2, 2, 2, 2, 5))

        private fun startGrid(): Array<CharArray> {
            val rows = START_FEN_BOARD.split("/")
            val g = Array(10) { CharArray(9) { '.' } }
            for (r in 0 until 10) {
                var c = 0
                for (ch in rows[r]) {
                    if (ch.isDigit()) c += ch - '0' else { g[r][c] = ch; c++ }
                }
            }
            return g
        }

        /** 新开揭棋：标准暗子位 + 全池 + 随机洗牌（App 秘密分配，绝不下发引擎）。 */
        fun newGame(rng: Random, redToMove: Boolean = true): JieqiGameState {
            val grid = startGrid()
            val assignment = Array(10) { CharArray(9) { '.' } }
            for (red in listOf(true, false)) {
                // 池身份多重集 → 洗牌 → 依序铺到该方暗子格
                val bag = ArrayList<Char>(15)
                for (slot in 0 until 6) repeat(URN_INIT[slot]) { bag.add(slotLetter(slot, red)) }
                bag.shuffle(rng)
                var i = 0
                for (r in 0 until 10) for (c in 0 until 9) {
                    if (JieqiRules.isDark(grid[r][c]) && JieqiRules.isRed(grid[r][c]) == red) {
                        assignment[r][c] = bag[i++]
                    }
                }
                check(i == bag.size) { "dark square count mismatch" }
            }
            val state = JieqiGameState(
                grid, redToMove, fullPools(), assignment,
                fenOf(grid, redToMove, fullPools()), mutableListOf(), redToMove,
                intArrayOf(0, 0), mutableListOf()
            )
            return state
        }

        /** 扫描导入：以编辑后的 grid 建立不完整信息局面（无真实分配，池可后校）。 */
        fun fromImported(grid: Array<CharArray>, redToMove: Boolean, pools: Array<IntArray>): JieqiGameState =
            JieqiGameState(
                grid.map { it.copyOf() }.toTypedArray(), redToMove,
                pools.map { it.copyOf() }.toTypedArray(), null,
                fenOf(grid, redToMove, pools), mutableListOf(), redToMove,
                intArrayOf(0, 0), mutableListOf()
            )

        private fun slotLetter(slot: Int, red: Boolean) =
            if (red) SLOT_LETTER[slot] else SLOT_LETTER[slot].lowercaseChar()

        fun pieceChar(slot: Int, red: Boolean) = slotLetter(slot, red)

        fun slotOf(ch: Char): Int {
            val up = ch.uppercaseChar()
            for (s in 0 until 6) if (SLOT_LETTER[s] == up) return s
            return -1
        }

        /** 由 grid+池序列化 FEN（引擎 set_fen 兼容；6 字段第 4 字段省略）。 */
        fun fenOf(grid: Array<CharArray>, redToMove: Boolean, pools: Array<IntArray>): String {
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
            // 池输出顺序与引擎一致：R A C P N B
            var pool = ""
            val order = listOf(0, 3, 4, 5, 1, 2)  // slot: R A C P N B
            for (cc in 0..1) for (slot in order) {
                val n = pools[cc][slot]
                if (n > 0) pool += pieceChar(slot, cc == 0).toString() + n.toString()
            }
            sb.append(if (pool.isEmpty()) "-" else pool)
            sb.append(" 0 ").append(if (redToMove) 1 else 2)
            return sb.toString()
        }

        /**
         * 扫描导入的池推导：初始池 - 盘面可见明子数（被吃明子会造成多计，
         * 由「身份池」面板与用户校对；引擎约束 池总数 ≥ 暗子数 在此钳制兜底）。
         */
        fun derivePools(grid: Array<CharArray>, redToMove: Boolean):
                Triple<Array<CharArray>, Boolean, Array<IntArray>> {
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
                while (total < dark) {
                    var idx = -1
                    var max = 0
                    for (s in 0 until 6) if (pools[side][s] > max) { max = pools[side][s]; idx = s }
                    if (idx < 0 || max == 0) break
                    pools[side][idx]++
                    total++
                }
            }
            return Triple(grid, redToMove, pools)
        }

        /** 揭棋导入 FEN：grid + 先手方 → 引擎方言（含推导池）。 */
        fun buildImportFen(grid: Array<CharArray>, redFirst: Boolean): String? {
            val trimmed = grid.map { it.copyOf() }.toTypedArray()
            val (_, _, pools) = derivePools(trimmed, redFirst)
            return fenOf(trimmed, redFirst, pools)
        }

        /** 解析引擎方言 FEN（棋盘/行棋方/池），失败返回 null。 */
        fun parseFen(fen: String): Triple<Array<CharArray>, Boolean, Array<IntArray>>? {
            val fields = fen.trim().split(Regex("\\s+"))
            if (fields.size < 4) return null
            val rows = fields[0].split("/")
            if (rows.size != 10) return null
            val g = Array(10) { CharArray(9) { '.' } }
            var kings = intArrayOf(0, 0)
            for ((i, row) in rows.withIndex()) {
                var c = 0
                for (ch in row) {
                    when {
                        ch.isDigit() -> c += ch - '0'
                        else -> {
                            if (c > 8) return null
                            g[i][c] = ch
                            if (ch == 'K') kings[0]++
                            if (ch == 'k') kings[1]++
                            if (JieqiRules.isDark(ch) && JieqiRules.darkRole(i, c, JieqiRules.isRed(ch)) == '.') return null
                            c++
                        }
                    }
                }
                if (c != 9) return null
            }
            if (kings[0] != 1 || kings[1] != 1) return null
            val redToMove = when (fields[1]) { "w" -> true; "b" -> false; else -> return null }
            val pools = arrayOf(intArrayOf(0, 0, 0, 0, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0))
            if (fields[2] != "-") {
                var i = 0
                val poolStr = fields[2]
                while (i < poolStr.length) {
                    val ch = poolStr[i++]
                    val slot = slotOf(ch)
                    if (slot < 0) return null
                    var n = 1
                    val num = StringBuilder()
                    while (i < poolStr.length && poolStr[i].isDigit()) { num.append(poolStr[i++]) }
                    if (num.isNotEmpty()) n = num.toString().toIntOrNull() ?: return null
                    if (n > URN_INIT[slot]) return null
                    pools[if (ch.isUpperCase()) 0 else 1][slot] = n
                }
            }
            // 池总数 ≥ 暗子数（引擎校验对齐）
            for (cc in 0..1) {
                var dark = 0; var total = 0
                for (r in 0 until 10) for (c in 0 until 9) {
                    if (JieqiRules.isDark(g[r][c]) && JieqiRules.isRed(g[r][c]) == (cc == 0)) dark++
                }
                for (s in 0 until 6) total += pools[cc][s]
                if (total < dark) return null
            }
            return Triple(g, redToMove, pools)
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    fun fen(): String = fenOf(grid, redToMove, pools)

    /** position 命令串（客户端每次重发；悔棋=回退 tokens 后重建）。 */
    fun positionCommand(): String {
        val sb = StringBuilder("position fen ").append(startFen)
        if (tokens.isNotEmpty()) {
            sb.append(" moves")
            for (t in tokens) sb.append(' ').append(t)
        }
        return sb.toString()
    }

    fun poolTotal(red: Boolean): Int = pools[if (red) 0 else 1].sum()
    fun darkCount(red: Boolean): Int {
        var n = 0
        for (r in 0 until 10) for (c in 0 until 9)
            if (JieqiRules.isDark(grid[r][c]) && JieqiRules.isRed(grid[r][c]) == red) n++
        return n
    }

    /**
     * 按观察者视角生成「eaterSide 吃掉的子」显示列表。
     * 参考 engine.js sanitize：明子双方可见；暗子仅吃子方(eaterSide)可见真实身份，
     * 对方一律显示统一背面（'X'/'x'，颜色统一，不透露归属）。
     *
     * @param eaterSide 吃子方：0=红 1=黑（取 captured[eaterSide]）
     * @param observerSide 观察者：0=红 1=黑；null=不隐藏(调试/新开局单机玩家全知)
     */
    fun capturedViewList(eaterSide: Int, observerSide: Int?): List<Char> {
        return captured[eaterSide].map { cp ->
            when {
                !cp.wasHidden -> cp.ch                          // 明子，双方可见
                observerSide == null || observerSide == eaterSide ->
                    if (cp.ch == '?') 'X' else cp.ch            // 吃子方(或全知)：真实身份
                else -> 'X'                                     // 对方：统一背面
            }
        }
    }

    /**
     * 以当前局面为新的引擎起点（棋局设置中途改先手方时调用）。
     * 引擎的行棋方由 startFen+token 奇偶决定，直接改 redToMove 会使两边错位一手；
     * rebase 把当前 grid/池/新轮走方固化为 startFen 并清空 token 历史。
     */
    fun rebase(redFirst: Boolean) {
        startFen = fenOf(grid, redFirst, pools)
        startRedToMove = redFirst
        redToMove = redFirst
        tokens.clear()
    }

    fun snapshot(): JieqiGameState = JieqiGameState(
        grid.map { it.copyOf() }.toTypedArray(), redToMove,
        pools.map { it.copyOf() }.toTypedArray(),
        assignment?.map { it.copyOf() }?.toTypedArray(),
        startFen, tokens.toMutableList(), startRedToMove,
        darkCapturedUnknown.copyOf(), moveRecords.toMutableList(),
        arrayOf(captured[0].toMutableList(), captured[1].toMutableList())
    )

    fun restoreFrom(s: JieqiGameState) {
        for (r in 0 until 10) System.arraycopy(s.grid[r], 0, grid[r], 0, 9)
        redToMove = s.redToMove
        for (c in 0..1) System.arraycopy(s.pools[c], 0, pools[c], 0, 6)
        if (assignment != null && s.assignment != null)
            for (r in 0 until 10) System.arraycopy(s.assignment[r], 0, assignment[r], 0, 9)
        tokens.clear(); tokens.addAll(s.tokens)
        darkCapturedUnknown[0] = s.darkCapturedUnknown[0]
        darkCapturedUnknown[1] = s.darkCapturedUnknown[1]
        moveRecords.clear(); moveRecords.addAll(s.moveRecords)
        captured[0].clear(); captured[0].addAll(s.captured[0])
        captured[1].clear(); captured[1].addAll(s.captured[1])
    }

    // ------------------------------------------------------------------
    // 走子应用（App 端权威；token 规则与引擎 apply_token 对齐）
    // ------------------------------------------------------------------

    /** 走法是否需要揭晓身份（移动子是暗子）。 */
    fun moveNeedsReveal(fr: Int, fc: Int): Boolean = JieqiRules.isDark(grid[fr][fc])

    /** 走法是否吃掉暗子（victim 身份录入与否决定 token 后缀）。 */
    fun capturesDark(tr: Int, tc: Int): Boolean = JieqiRules.isDark(grid[tr][tc])

    /**
     * 应用一手走法。
     * @param reveal 移动暗子时揭晓的真实身份（'R'/'N'/'B'/'A'/'C'/'P'，颜色随行棋方）；非暗子移动传 '.'
     * @param victimReveal 被吃暗子的已知身份；未知传 '.'（引擎保持概率分布，池不扣减）
     * @return 错误消息；null = 成功
     */
    fun applyMove(fr: Int, fc: Int, tr: Int, tc: Int, reveal: Char, victimReveal: Char,
                  trustEngine: Boolean = false): String? {
        val mover = grid[fr][fc]
        if (mover == '.') return "起点无子"
        if (JieqiRules.isRed(mover) != redToMove) return "不是该方的棋子"
        val victim = grid[tr][tc]
        val movedDark = JieqiRules.isDark(mover)
        val capturedDark = victim != '.' && JieqiRules.isDark(victim)
        // trustEngine：AI 的走法来自引擎 gen_legal（引擎局面权威），跳过 App 端校验，
        // 避免移植差异把合法走法误拒导致对弈卡死
        if (!trustEngine && !JieqiRules.isLegal(grid, redToMove, fr, fc, tr, tc)) return "非法走法"
        val us = if (redToMove) 0 else 1
        // 新开对局：App 持有秘密分配，暗子揭晓自动取真实身份；扫描模式必须由用户录入
        val effReveal = if (movedDark) {
            if (assignment != null) assignment[fr][fc].also { check(it != '.') { "assigned dark without identity" } }
            else reveal
        } else '.'
        if (movedDark && assignment == null && (reveal == '.' || reveal.uppercaseChar() !in SLOT_LETTER)) return "缺少揭晓身份"
        if (movedDark && effReveal.uppercaseChar() !in SLOT_LETTER) return "揭晓身份无效"
        if (movedDark && JieqiRules.isRed(effReveal) != redToMove) return "揭晓身份颜色不符"
        if (movedDark && pools[us][slotOf(effReveal)] <= 0) return "揭晓身份不在身份池"
        if (victim != '.' && !JieqiRules.isDark(victim) && victimReveal != '.') return "被吃子不是暗子"
        if (capturedDark && victimReveal != '.') {
            if (JieqiRules.isRed(victimReveal) == redToMove) return "被吃身份颜色不符"
            if (pools[1 - us][slotOf(victimReveal)] <= 0) return "被吃身份不在身份池"
        }
        val moverDesc = if (movedDark) "暗" else ""
        val victimDesc = when {
            victim == '.' -> ""
            !JieqiRules.isDark(victim) -> "吃${victim}"
            victimReveal != '.' -> "吃暗${victimReveal}"
            else -> "吃暗?"
        }

        // 1) token（引擎历史）
        val token = StringBuilder()
        token.append(sqName(fr, fc)).append(sqName(tr, tc))
        if (movedDark) token.append(effReveal)
        if (capturedDark && victimReveal != '.') token.append(victimReveal)
        tokens.add(token.toString())

        // 2) 池更新（与引擎 urn 语义一致）
        if (movedDark) pools[us][slotOf(effReveal)]--
        if (capturedDark) {
            if (victimReveal != '.') pools[1 - us][slotOf(victimReveal)]--
            else darkCapturedUnknown[1 - us]++   // 己方暗子被吃且身份未知（仅扫描模式会出现）
        }
        // 被吃棋子记录（真实信息；显示时按观察者视角过滤，见 capturedViewList）
        if (victim != '.') {
            val truth = when {
                !JieqiRules.isDark(victim) -> victim          // 明子，双方可见
                victimReveal != '.' -> victimReveal           // 暗子，吃子方获知真实身份
                else -> '?'                                   // 暗子身份完全未知
            }
            captured[us].add(CapturedPiece(truth, JieqiRules.isDark(victim)))
        }

        // 3) 棋盘
        grid[tr][tc] = if (movedDark) {
            (if (redToMove) effReveal else effReveal.lowercaseChar())
        } else mover
        if (movedDark && assignment != null) assignment[fr][fc] = '.'
        grid[fr][fc] = '.'
        redToMove = !redToMove

        // 4) 对局记录
        moveRecords.add("$moverDesc${sqName(fr, fc)}→${sqName(tr, tc)}" +
            (if (movedDark) " 揭" + (if (!redToMove) effReveal else effReveal.lowercaseChar()) else "") +
            victimDesc)
        return null
    }

    fun sqName(r: Int, c: Int): String {
        val files = "abcdefghi"
        return "${files[c]}${9 - r}"
    }

    // ------------------------------------------------------------------
    // 保存 / 恢复（自包含行式序列化：| 分隔字段，避免依赖 org.json —— JVM 单测被 stub）
    // v1: version | grid(10 行, / 连接) | redToMove | pools(两行逗号连接) | assignment(10 行或 '-') |
    //     startFen | startRedToMove | dcu0,dcu1 | tokens(空格连接) | records(§ 连接)
    // ------------------------------------------------------------------

    fun serialize(): String {
        val parts = ArrayList<String>(12)
        parts.add("3")
        parts.add(grid.joinToString("/") { String(it) })
        parts.add(redToMove.toString())
        parts.add(pools.joinToString(",") { p -> p.joinToString(",") })
        parts.add(assignment?.joinToString("/") { String(it) } ?: "-")
        parts.add(startFen)
        parts.add(startRedToMove.toString())
        parts.add("${darkCapturedUnknown[0]},${darkCapturedUnknown[1]}")
        parts.add(tokens.joinToString(" "))
        parts.add(moveRecords.joinToString("§"))
        // v3: 每个被吃子 = 字母 + (暗子加 '#' 标记 wasHidden)
        fun enc(list: MutableList<CapturedPiece>) = buildString {
            for (cp in list) {
                append(cp.ch)
                // '?' 本身即代表未知暗子，无需再带 '#'（否则解码会把 '#' 当成新棋子）
                if (cp.wasHidden && cp.ch != '?') append('#')
            }
        }
        parts.add(enc(captured[0]))
        parts.add(enc(captured[1]))
        return parts.joinToString("|")
    }

    /** 解码被吃子列表：字母 + 可选 '#'（暗子标记）。兼容 v2 的纯字母（视为明子）。 */
    private fun decCap(text: String, v2: Boolean): MutableList<CapturedPiece> {
        val out = mutableListOf<CapturedPiece>()
        var i = 0
        while (i < text.length) {
            val ch = text[i++]
            if (ch == '?' || ch == 'X' || ch == 'x') {
                out.add(CapturedPiece('?', true)); continue
            }
            val hidden = i < text.length && text[i] == '#'
            if (hidden) i++
            out.add(CapturedPiece(ch, if (v2) false else hidden))
        }
        return out
    }

    fun deserialize(text: String): JieqiGameState {
        val p = text.split("|")
        require(p.size >= 10) { "bad save format" }
        val ver = p[0].toIntOrNull() ?: 1
        val grid = p[1].split("/").map { it.toCharArray() }.toTypedArray()
        val redToMove = p[2].toBoolean()
        val pools = p[3].split(",").map { it.toInt() }.chunked(6)
            .map { l -> l.toIntArray() }.toTypedArray()
        val assignment = if (p[4] == "-") null else p[4].split("/").map { it.toCharArray() }.toTypedArray()
        val startFen = p[5]
        val startRedToMove = p[6].toBoolean()
        val dcu = p[7].split(",").map { it.toInt() }
        val tokens = if (p[8].isEmpty()) mutableListOf() else p[8].split(" ").toMutableList()
        val records = if (p[9].isEmpty()) mutableListOf() else p[9].split("§").toMutableList()
        val capRed = if (p.size > 10 && p[10].isNotEmpty()) decCap(p[10], ver < 3) else mutableListOf()
        val capBlack = if (p.size > 11 && p[11].isNotEmpty()) decCap(p[11], ver < 3) else mutableListOf()
        return JieqiGameState(
            grid, redToMove, pools, assignment, startFen, tokens, startRedToMove,
            intArrayOf(dcu[0], dcu[1]), records, arrayOf(capRed, capBlack)
        )
    }
}
