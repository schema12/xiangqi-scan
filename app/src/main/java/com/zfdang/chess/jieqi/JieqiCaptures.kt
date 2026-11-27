package com.zfdang.chess.jieqi

/**
 * 吃子记录的解析/格式化与去重显示。
 * 供校对页「补充吃子」编辑器和首页吃子区共用，保证同一副棋子只显示一个图标 + 数字角标。
 *
 * 传输格式沿用原有约定："红明子,红暗数;黑明子,黑暗数"，例如 "RRNP,2;np,1"。
 */
object JieqiCaptures {
    const val DARK_RED = 'X'
    const val DARK_BLACK = 'x'

    /** 单种棋子可吃上限：常规子 2、兵/卒 5、暗子 9（避免点选过多）。 */
    const val MAX_NORMAL = 2
    const val MAX_PAWN = 5
    const val MAX_DARK = 9

    private const val REVEALED = "KABNRCPkabnrcp"

    fun maxOf(ch: Char): Int = when (ch) {
        'P', 'p' -> MAX_PAWN
        DARK_RED, DARK_BLACK -> MAX_DARK
        else -> MAX_NORMAL
    }

    /** 解析吃子补充串；返回 (红方吃掉的子, 黑方吃掉的子)，map 值为数量。 */
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
            val dark = if (side == 0) DARK_RED else DARK_BLACK
            for (ch in parts[0].trim()) {
                if (ch == 'K' || ch == 'k') continue
                if (ch !in REVEALED) continue
                if (if (side == 0) !ch.isUpperCase() else !ch.isLowerCase()) continue
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
        fun side(map: Map<Char, Int>, dark: Char): String {
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
        return "${side(red, DARK_RED)};${side(black, DARK_BLACK)}"
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
        fun side(list: List<CapturedPiece>, dark: Char): Map<Char, Int> {
            val m = LinkedHashMap<Char, Int>()
            for (cp in list) {
                if (cp.ch == 'K' || cp.ch == 'k') continue
                val ch = if (cp.wasHidden && cp.ch == '?') dark else cp.ch
                if (ch !in REVEALED && ch != DARK_RED && ch != DARK_BLACK) continue
                m[ch] = (m[ch] ?: 0) + 1
            }
            return m
        }
        return format(side(captured[0], DARK_RED), side(captured[1], DARK_BLACK))
    }
}
