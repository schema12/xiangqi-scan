package com.xiangqiscan.app.core

/** 红黑双方 */
enum class Side { RED, BLACK }

/** 七种棋子类型 */
enum class PieceType { KING, ADVISOR, ELEPHANT, HORSE, ROOK, CANNON, PAWN }

/** 一枚棋子 */
data class Piece(val side: Side, val type: PieceType) {
    /** 编辑器/日志显示字符 */
    fun glyph(): String {
        val red = arrayOf("帅", "仕", "相", "马", "车", "炮", "兵")
        val black = arrayOf("将", "士", "象", "马", "车", "炮", "卒")
        val i = type.ordinal
        return if (side == Side.RED) red[i] else black[i]
    }
}

/**
 * 局面。90 个交叉点，行 0 = 黑方底线（棋盘顶部），列 0 = a 路（红方视角左侧）。
 * 与 UCCI FEN 行序一致：row0 在 FEN 中最先输出。
 */
class Position {
    companion object {
        const val ROWS = 10
        const val COLS = 9
        /** 每方每种棋子的最大数量 */
        val MAX_COUNT = mapOf(
            PieceType.KING to 1, PieceType.ADVISOR to 2, PieceType.ELEPHANT to 2,
            PieceType.HORSE to 2, PieceType.ROOK to 2, PieceType.CANNON to 2,
            PieceType.PAWN to 5
        )
    }

    /** index = row * 9 + col */
    val grid = arrayOfNulls<Piece>(ROWS * COLS)
    /** 轮走方 */
    var turn: Side = Side.RED

    fun get(row: Int, col: Int): Piece? = grid[row * COLS + col]

    fun set(row: Int, col: Int, p: Piece?) {
        grid[row * COLS + col] = p
    }

    fun clear() {
        java.util.Arrays.fill(grid, null)
        turn = Side.RED
    }

    /** 180° 旋转（拍摄方位相反时纠正用），棋子颜色不变 */
    fun rotate180() {
        val old = grid.copyOf()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            grid[r * COLS + c] = old[(ROWS - 1 - r) * COLS + (COLS - 1 - c)]
        }
    }

    /** 统计某方某类棋子数量 */
    fun count(side: Side, type: PieceType): Int {
        var n = 0
        for (p in grid) if (p != null && p.side == side && p.type == type) n++
        return n
    }

    /** 王的位置 */
    fun kingPos(side: Side): Pair<Int, Int>? {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val p = get(r, c)
            if (p != null && p.side == side && p.type == PieceType.KING) return r to c
        }
        return null
    }

    /** 校验：返回错误列表（空 = 合法） */
    fun validate(): List<String> {
        val errs = mutableListOf<String>()
        for (side in listOf(Side.RED, Side.BLACK)) {
            val name = if (side == Side.RED) "红方" else "黑方"
            if (count(side, PieceType.KING) != 1) errs.add("${name}必须有且只有一个将/帅")
            for ((t, max) in MAX_COUNT) {
                if (t == PieceType.KING) continue
                val n = count(side, t)
                if (n > max) errs.add("${name}${t.name}数量超限($n>$max)")
            }
        }
        val rk = kingPos(Side.RED)
        val bk = kingPos(Side.BLACK)
        if (rk != null && bk != null && rk.second == bk.second) {
            errs.add("警告：将帅同线（对脸）")
        }
        return errs
    }

    fun isEmpty(): Boolean = grid.all { it == null }
}
