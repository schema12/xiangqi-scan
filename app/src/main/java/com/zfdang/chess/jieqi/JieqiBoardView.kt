package com.zfdang.chess.jieqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 揭棋对弈棋盘：复用 ChessView/BoardEditView 的几何与素材。
 *
 * - 明子按素材位图绘制；暗子为程序化瓦片（红/黑底 + 「揭」字）
 * - 视图翻转（viewFlipped）只影响绘制与触摸映射，数据不动
 * - selected：四角直角标记；lastMove：最近一步起止标记；hint：AI 提示目标
 */
class JieqiBoardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BOARD_WIDTH = 1240
        const val BOARD_HEIGHT = 1340
        const val BOARD_PIECE_SIZE = 110
        const val BOARD_X_OFFSET = 22
        const val BOARD_Y_OFFSET = 5
        const val BOARD_GRID_INTERVAL = 136
    }

    /** 10×9 揭棋棋盘（'X'/'x' 暗子，其余明子字母，'.' 空）。 */
    var grid: Array<CharArray> = Array(10) { CharArray(9) { '.' } }
        set(value) { field = value; invalidate() }

    var viewFlipped = false
        set(value) { field = value; invalidate() }

    /** 选中格索引 r*9+c（四角标记），-1 无 */
    var selected = -1
        set(value) { field = value; invalidate() }

    /** 最近一步 (fr,fc,tr,tc)，null 无 */
    var lastMove: IntArray? = null
        set(value) { field = value; invalidate() }

    /** AI 提示目标格索引 r*9+c（虚线圈），-1 无 */
    var hint = -1
        set(value) { field = value; invalidate() }

    var onCellTap: ((row: Int, col: Int) -> Unit)? = null

    private val boardBmp: Bitmap = BitmapFactory.decodeResource(resources, com.zfdang.chess.R.drawable.chessboard)
    private val pieceCache = HashMap<Char, Bitmap>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFFE23B24.toInt()
    }
    private val lastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x66E23B24
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFF6B4E9E.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var s = 1f
    private var top = 0f
    private var pieceSize = 0f

    private fun vRow(r: Int) = if (viewFlipped) 9 - r else r
    private fun vCol(c: Int) = if (viewFlipped) 8 - c else c

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * BOARD_HEIGHT.toFloat() / BOARD_WIDTH).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        s = width.toFloat() / BOARD_WIDTH
        top = (height - BOARD_HEIGHT * s) / 2f
        pieceSize = BOARD_PIECE_SIZE * s
        canvas.drawBitmap(boardBmp, null,
            Rect(0, top.toInt(), width, (top + BOARD_HEIGHT * s).toInt()), paint)

        // 最近一步底色标记
        lastMove?.let { lm ->
            for (i in 0..2 step 2) {
                val r = vRow(lm[i]); val c = vCol(lm[i + 1])
                canvas.drawRect(
                    cellX(c) - pieceSize / 2, cellY(r) - pieceSize / 2,
                    cellX(c) + pieceSize / 2, cellY(r) + pieceSize / 2, lastPaint)
            }
        }

        for (r in 0 until 10) for (c in 0 until 9) {
            val ch = grid[r][c]
            if (ch == '.') continue
            val bmp = bitmapFor(ch)
            val cx = cellX(vCol(c)); val cy = cellY(vRow(r))
            if (bmp != null) {
                canvas.drawBitmap(bmp, null,
                    Rect((cx - pieceSize / 2).toInt(), (cy - pieceSize / 2).toInt(),
                        (cx + pieceSize / 2).toInt(), (cy + pieceSize / 2).toInt()), paint)
            }
        }

        selected.takeIf { it >= 0 }?.let { sel ->
            val r = sel / 9; val c = sel % 9
            drawCorners(canvas, cellX(vCol(c)), cellY(vRow(r)))
        }
        hint.takeIf { it >= 0 }?.let { h ->
            val r = h / 9; val c = h % 9
            canvas.drawCircle(cellX(vCol(c)), cellY(vRow(r)), pieceSize * 0.62f, hintPaint)
        }
    }

    private fun drawCorners(canvas: Canvas, cx: Float, cy: Float) {
        val half = pieceSize / 2 + 6f
        val arm = pieceSize * 0.28f
        val l = cx - half; val rr = cx + half
        val t = cy - half; val b = cy + half
        canvas.drawLine(l, t, l + arm, t, selectPaint)
        canvas.drawLine(l, t, l, t + arm, selectPaint)
        canvas.drawLine(rr, t, rr - arm, t, selectPaint)
        canvas.drawLine(rr, t, rr, t + arm, selectPaint)
        canvas.drawLine(l, b, l + arm, b, selectPaint)
        canvas.drawLine(l, b, l, b - arm, selectPaint)
        canvas.drawLine(rr, b, rr - arm, b, selectPaint)
        canvas.drawLine(rr, b, rr, b - arm, selectPaint)
    }

    private fun bitmapFor(ch: Char): Bitmap? {
        if (ch == 'X' || ch == 'x') return com.zfdang.chess.jieqi.JieqiTiles.darkTile()
        pieceCache[ch]?.let { return it }
        val id = when (ch) {
            'K' -> com.zfdang.chess.R.drawable.r_shuai; 'A' -> com.zfdang.chess.R.drawable.r_shi
            'B' -> com.zfdang.chess.R.drawable.r_xiang; 'N' -> com.zfdang.chess.R.drawable.r_ma
            'R' -> com.zfdang.chess.R.drawable.r_ju; 'C' -> com.zfdang.chess.R.drawable.r_pao
            'P' -> com.zfdang.chess.R.drawable.r_bing
            'k' -> com.zfdang.chess.R.drawable.b_jiang; 'a' -> com.zfdang.chess.R.drawable.b_shi
            'b' -> com.zfdang.chess.R.drawable.b_xiang; 'n' -> com.zfdang.chess.R.drawable.b_ma
            'r' -> com.zfdang.chess.R.drawable.b_ju; 'c' -> com.zfdang.chess.R.drawable.b_pao
            'p' -> com.zfdang.chess.R.drawable.b_zu
            else -> return null
        }
        val bmp = BitmapFactory.decodeResource(resources, id)
        pieceCache[ch] = bmp
        return bmp
    }

    private fun cellX(c: Int) =
        (BOARD_X_OFFSET + BOARD_PIECE_SIZE / 2f + c * BOARD_GRID_INTERVAL) * s
    private fun cellY(r: Int) =
        top + (BOARD_Y_OFFSET + BOARD_PIECE_SIZE / 2f + r * BOARD_GRID_INTERVAL) * s

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_DOWN) return true
        val x = ev.x / s - BOARD_X_OFFSET - BOARD_PIECE_SIZE / 2f
        val y = (ev.y - top) / s - BOARD_Y_OFFSET - BOARD_PIECE_SIZE / 2f
        val c = Math.round(x / BOARD_GRID_INTERVAL)
        val r = Math.round(y / BOARD_GRID_INTERVAL)
        if (r in 0 until 10 && c in 0 until 9) {
            onCellTap?.invoke(vRow(r), vCol(c))
        }
        return true
    }
}
