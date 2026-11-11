package com.zfdang.chess.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

import com.zfdang.chess.R

/**
 * 识别摆谱的校对棋盘：复用 ChessView 的棋盘/棋子素材与几何常量，
 * 显示识别网格（可编辑）、低置信格红框提示。
 */
class BoardEditView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // 与 ChessView 的几何常量保持一致
        const val BOARD_WIDTH = 1240
        const val BOARD_HEIGHT = 1340
        const val BOARD_PIECE_SIZE = 110
        const val BOARD_X_OFFSET = 22
        const val BOARD_Y_OFFSET = 5
        const val BOARD_GRID_INTERVAL = 136

        val PIECE_DRAWABLES = mapOf(
            'K' to R.drawable.r_shuai, 'A' to R.drawable.r_shi, 'B' to R.drawable.r_xiang,
            'N' to R.drawable.r_ma, 'R' to R.drawable.r_ju, 'C' to R.drawable.r_pao,
            'P' to R.drawable.r_bing,
            'k' to R.drawable.b_jiang, 'a' to R.drawable.b_shi, 'b' to R.drawable.b_xiang,
            'n' to R.drawable.b_ma, 'r' to R.drawable.b_ju, 'c' to R.drawable.b_pao,
            'p' to R.drawable.b_zu
        )
    }

    /** 10×9 FEN 字符网格（'.'=空） */
    var grid: Array<CharArray> = Array(10) { CharArray(9) { '.' } }
        set(value) { field = value; invalidate() }

    /** 低置信格 index（r*9+c），红框提示 */
    var lowConf: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** 点击交叉点回调 */
    var onCellTap: ((row: Int, col: Int) -> Unit)? = null

    private val boardBmp: Bitmap =
        BitmapFactory.decodeResource(resources, R.drawable.chessboard)
    private val pieceCache = HashMap<Char, Bitmap>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = 0xE6E23B24.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        // 棋盘底图按宽度缩放、垂直居中
        val s = width.toFloat() / BOARD_WIDTH
        val top = (height - BOARD_HEIGHT * s) / 2f
        canvas.drawBitmap(boardBmp, null,
            Rect(0, top.toInt(), width, (top + BOARD_HEIGHT * s).toInt()), paint)

        val pieceSize = BOARD_PIECE_SIZE * s
        for (r in 0 until 10) for (c in 0 until 9) {
            val ch = grid[r][c]
            val cx = (BOARD_X_OFFSET + BOARD_PIECE_SIZE / 2f + c * BOARD_GRID_INTERVAL) * s
            val cy = top + (BOARD_Y_OFFSET + BOARD_PIECE_SIZE / 2f + r * BOARD_GRID_INTERVAL) * s
            if (ch != '.') {
                val bmp = pieceFor(ch)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null,
                        Rect((cx - pieceSize / 2).toInt(), (cy - pieceSize / 2).toInt(),
                            (cx + pieceSize / 2).toInt(), (cy + pieceSize / 2).toInt()), paint)
                }
            }
            if (lowConf.contains(r * 9 + c)) {
                canvas.drawRect(cx - pieceSize / 2, cy - pieceSize / 2,
                    cx + pieceSize / 2, cy + pieceSize / 2, lowPaint)
            }
        }
    }

    private fun pieceFor(ch: Char): Bitmap? {
        pieceCache[ch]?.let { return it }
        val id = PIECE_DRAWABLES[ch] ?: return null
        val bmp = BitmapFactory.decodeResource(resources, id)
        pieceCache[ch] = bmp
        return bmp
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action != android.view.MotionEvent.ACTION_DOWN) return true
        val s = width.toFloat() / BOARD_WIDTH
        val top = (height - BOARD_HEIGHT * s) / 2f
        val x = ev.x / s - BOARD_X_OFFSET - BOARD_PIECE_SIZE / 2f
        val y = (ev.y - top) / s - BOARD_Y_OFFSET - BOARD_PIECE_SIZE / 2f
        val c = Math.round(x / BOARD_GRID_INTERVAL)
        val r = Math.round(y / BOARD_GRID_INTERVAL)
        if (r in 0 until 10 && c in 0 until 9) {
            onCellTap?.invoke(r, c)
        }
        return true
    }
}
