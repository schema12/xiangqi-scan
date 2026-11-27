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

    private const val REVEALED = "KABNRCPkabnrcp"

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
            if (n > 0) dest[dark] = n.coerceAtMost(16)
        }
        return red to black
    }

    /** 反向格式化；两边都没吃子时返回空串（表示不补充）。 */
    fun format(red: Map<Char, Int>, black: Map<Char, Int>): String {
        if (red.isEmpty() && black.isEmpty()) return ""
        fun side(map: Map<Char, Int>, dark: Char): String {
            val letters = StringBuilder()
            for ((ch, n) in map) {
                if (ch == 'K' || ch == 'k') continue
                if (ch == DARK_RED || ch == DARK_BLACK || ch !in REVEALED) continue
                repeat(n.coerceAtLeast(0).coerceAtMost(16)) { letters.append(ch) }
            }
            return "$letters,${map[dark] ?: 0}"
        }
        return "${side(red, DARK_RED)};${side(black, DARK_BLACK)}"
    }

    /** List<Char> -> [(棋子, 数量)]，按首次出现顺序去重，供吃子区绘制数字角标。 */
    fun collapse(pieces: List<Char>): List<Pair<Char, Int>> {
        val counts = LinkedHashMap<Char, Int>()
        for (ch in pieces) counts[ch] = (counts[ch] ?: 0) + 1
        return counts.map { it.key to it.value }
    }
}
