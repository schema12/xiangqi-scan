package com.zfdang.chess.jieqi

/**
 * 吃子记录的解析/格式化与去重显示。
 * 供校对页「补充吃子」编辑器和首页吃子区共用，保证同一副棋子只显示一个图标 + 数字角标。
 *
 * 传输格式："红方吃掉的子,红方吃掉暗数;黑方吃掉的子,黑方吃掉暗数"。
 * 红方吃掉的是黑子（小写）、黑方吃掉的是红子（大写）；暗子统一背面，用 x/X 仅作槽位标记。
 */
object JieqiCaptures {
    const val DARK_RED = 'X'    // 黑方吃掉的暗子（槽位标记：红方暗）
    const val DARK_BLACK = 'x'  // 红方吃掉的暗子（槽位标记：黑方暗）

    /** 单种棋子可吃上限：常规子 2、兵/卒 5、暗子 9（避免点选过多）。 */
    const val MAX_NORMAL = 2
    const val MAX_PAWN = 5
    const val MAX_DARK = 9

    /** 某组（谁吃掉）应使用的暗子槽位标记：红方吃掉→x，黑方吃掉→X */
    fun darkSlotFor(eaterSide: Int): Char = if (eaterSide == 0) DARK_BLACK else DARK_RED

    private const val REVEALED = "KABNRCPkabnrcp"

    fun maxOf(ch: Char): Int = when (ch) {
        'P', 'p' -> MAX_PAWN
        DARK_RED, DARK_BLACK -> MAX_DARK
        else -> MAX_NORMAL
    }

    /** 解析吃子补充串；返回 (红方吃掉的子, 黑方吃掉的子)，map 值为数量。
     *  红方吃掉的是黑子（小写），黑方吃掉的是红子（大写）。 */
    fun parse(spec: String?): Pair<MutableMap<Char, Int>, MutableMap<Char, Int>> {
        val red = LinkedHashMap<Char, Int>()
        val black = LinkedHashMap<Char, Int>()
        if (spec.isNullOrBlank()) return red to black
        val groups = spec.split(";")
        if (groups.size != 2) return red to black
        for (side in 0..1) {
            val parts = groups[side].split(",")
            if (parts.size != 2) continue
            val dest = if (side == 0) red else black
            val dark = darkSlotFor(side)
            for (ch in parts[0].trim()) {
                if (ch == 'K' || ch == 'k') continue
                if (ch !in REVEALED) continue
                // 红方吃黑子→小写；黑方吃红子→大写
                if (if (side == 0) !ch.isLowerCase() else !ch.isUpperCase()) continue
                dest[ch] = (dest[ch] ?: 0) + 1
            }
            val n = parts[1].trim().toIntOrNull() ?: 0
            if (n > 0) dest[dark] = n.coerceIn(0, MAX_DARK)
        }
        // 逐棋子收敛到上限，防止历史脏数据把吃子区画爆
        for (m in arrayOf(red, black)) {
            for (ch in m.keys.toList()) {
                val n = m[ch] ?: 0
                if (n > maxOf(ch)) m[ch] = maxOf(ch)
                if (n <= 0) m.remove(ch)
            }
        }
        return red to black
    }

    /** 反向格式化；两边都没吃子时返回空串（表示不补充）。 */
    fun format(red: Map<Char, Int>, black: Map<Char, Int>): String {
        if (red.isEmpty() && black.isEmpty()) return ""
        fun side(map: Map<Char, Int>, eaterSide: Int): String {
            val dark = darkSlotFor(eaterSide)
            val letters = StringBuilder()
            for ((ch, nRaw) in map) {
                if (ch == 'K' || ch == 'k') continue
                if (ch == DARK_RED || ch == DARK_BLACK || ch !in REVEALED) continue
                val n = nRaw.coerceIn(0, maxOf(ch))
                repeat(n) { letters.append(ch) }
            }
            val darkN = (map[dark] ?: 0).coerceIn(0, MAX_DARK)
            return "$letters,$darkN"
        }
        return "${side(red, 0)};${side(black, 1)}"
    }

    /** List<Char> -> [(棋子, 数量)]，按首次出现顺序去重，供吃子区绘制数字角标。 */
    fun collapse(pieces: List<Char>): List<Pair<Char, Int>> {
        val counts = LinkedHashMap<Char, Int>()
        for (ch in pieces) counts[ch] = (counts[ch] ?: 0) + 1
        return counts.map { it.key to it.value }
    }

    /** 从对局状态真实吃子列表导出 spec（供对局页「修正吃子」载入编辑器）。
     *  只取明子（wasHidden=false）；暗子身份未知的统一并入暗子计数，身份已知的真实子是历史录入，不改。 */
    fun specOf(captured: Array<MutableList<CapturedPiece>>): String {
        fun side(list: List<CapturedPiece>, eaterSide: Int): Map<Char, Int> {
            val dark = darkSlotFor(eaterSide)
            val m = LinkedHashMap<Char, Int>()
            for (cp in list) {
                if (cp.ch == 'K' || cp.ch == 'k') continue
                val ch = if (cp.wasHidden && cp.ch == '?') dark else cp.ch
                if (ch !in REVEALED && ch != DARK_RED && ch != DARK_BLACK) continue
                m[ch] = (m[ch] ?: 0) + 1
            }
            return m
        }
        return format(side(captured[0], 0), side(captured[1], 1))
    }

    /** 从 spec 导出「被吃明子」按所属方+角色的计数，供身份池扣减。
     *  池 = 初始15 − 盘面明子 − 已翻明后被吃的明子；被吃暗子身份未知不扣池。
     *  按棋子字母本身大小写决定归属方（红字母 R→红池、黑字母 r→黑池），
     *  而不是按"谁吃掉"分组（红方吃掉的子是黑子，黑方吃掉的子是红子）。
     *  返回 [redSlots, blackSlots]，slot 顺序 R N B A C P。 */
    fun capturedRevealedSlots(spec: String?): Array<IntArray> {
        val slots = arrayOf(IntArray(6), IntArray(6))
        val (red, black) = parse(spec)
        val order = charArrayOf('R', 'N', 'B', 'A', 'C', 'P')
        for (map in listOf(red, black)) {
            for ((ch, n) in map) {
                val side = if (ch.isUpperCase()) 0 else 1
                val up = ch.uppercaseChar()
                val s = order.indexOf(up)
                if (s >= 0) slots[side][s] += n
            }
        }
        return slots
    }
}
