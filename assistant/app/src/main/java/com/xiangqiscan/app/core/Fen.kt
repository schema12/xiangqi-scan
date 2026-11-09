package com.xiangqiscan.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UCCI FEN / PGN 编解码。
 * 方言按 cnvcs《中国象棋》实测（samples/outtest.pgn）：
 *   [FEN "4k3r/.../R3K4 b"] —— 黑方底线在前、大写红小写黑、仅轮走方、无计数尾巴。
 */
object Fen {

    private val RED_LETTERS = charArrayOf('K', 'A', 'B', 'N', 'R', 'C', 'P')
    private val BLACK_LETTERS = charArrayOf('k', 'a', 'b', 'n', 'r', 'c', 'p')

    fun pieceToChar(p: Piece): Char {
        val arr = if (p.side == Side.RED) RED_LETTERS else BLACK_LETTERS
        return arr[p.type.ordinal]
    }

    fun charToPiece(ch: Char): Piece? {
        val side = when {
            ch.isUpperCase() -> Side.RED
            ch.isLowerCase() -> Side.BLACK
            else -> return null
        }
        val up = ch.uppercaseChar()
        val idx = RED_LETTERS.indexOf(up)
        if (idx < 0) return null
        return Piece(side, PieceType.entries[idx])
    }

    fun encode(pos: Position): String {
        val sb = StringBuilder()
        for (r in 0 until Position.ROWS) {
            var run = 0
            for (c in 0 until Position.COLS) {
                val p = pos.get(r, c)
                if (p == null) {
                    run++
                } else {
                    if (run > 0) { sb.append(run); run = 0 }
                    sb.append(pieceToChar(p))
                }
            }
            if (run > 0) sb.append(run)
            if (r < Position.ROWS - 1) sb.append('/')
        }
        sb.append(' ').append(if (pos.turn == Side.RED) 'w' else 'b')
        return sb.toString()
    }

    /** 解析 FEN（兼容带 " - - 0 1" 计数尾巴的写法），失败返回 null */
    fun parse(fen: String): Position? {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val rows = parts[0].split('/')
        if (rows.size != Position.ROWS) return null
        val pos = Position()
        for ((r, rowStr) in rows.withIndex()) {
            var c = 0
            for (ch in rowStr) {
                if (ch.isDigit()) {
                    c += ch - '0'
                } else {
                    if (c >= Position.COLS) return null
                    pos.set(r, c, charToPiece(ch) ?: return null)
                    c++
                }
            }
            if (c != Position.COLS) return null
        }
        pos.turn = when (parts.getOrNull(1)?.lowercase()) {
            "w" -> Side.RED
            "b" -> Side.BLACK
            else -> Side.RED
        }
        return pos
    }

    /** 生成与《中国象棋》导出格式一致的 PGN（带 FEN 起始局面） */
    fun buildPgn(pos: Position): String {
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return StringBuilder().apply {
            append("[Game \"Chinese Chess\"]\n")
            append("[Date \"$date\"]\n")
            append("[Time \"$time\"]\n")
            append("[Red \"棋手\"]\n")
            append("[Black \"棋手\"]\n")
            append("[Result \"*\"]\n")
            append("[FEN \"${encode(pos)}\"]\n")
            append("\n")
        }.toString()
    }
}
