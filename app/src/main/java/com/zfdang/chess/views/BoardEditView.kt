package com.zfdang.chess.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.zfdang.chess.R

/**
 * 摆棋（校对）棋盘：复用 ChessView 素材/几何；绘制选中四角直角标记 +
 * 动态「可用棋子」悬浮窗（深色圆角卡片、两排圆形棋子，按场上剩余数量过滤）。
 *
 * 交互状态机（由 [onCellTap] 宿主实现）：
 *  - 点有子格：选中该子（四角直角标记），等待第二次点击
 *  - 再点同一子：收起该子（从棋盘消失）
 *  - 再点空格：把选中子移动到空格
 *  - 再点有子格：收起目标子，把选中子放到目标位置
 *  - 点空格（无选中）：弹出可用棋子悬浮窗
 *  - 悬浮窗点棋子：放到该空格
 */
class BoardEditView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BOARD_WIDTH = 1240
        const val BOARD_HEIGHT = 1340
        const val BOARD_PIECE_SIZE = 110
        const val BOARD_X_OFFSET = 22
        const val BOARD_Y_OFFSET = 5
        const val BOARD_GRID_INTERVAL = 136

        /** 14 种棋子 FEN 字符与素材（红 7 + 黑 7） */
        val PIECES = listOf('K', 'A', 'B', 'N', 'R', 'C', 'P',
                            'k', 'a', 'b', 'n', 'r', 'c', 'p')
        val PIECE_DRAWABLES = mapOf(
            'K' to R.drawable.r_shuai, 'A' to R.drawable.r_shi, 'B' to R.drawable.r_xiang,
            'N' to R.drawable.r_ma, 'R' to R.drawable.r_ju, 'C' to R.drawable.r_pao,
            'P' to R.drawable.r_bing,
            'k' to R.drawable.b_jiang, 'a' to R.drawable.b_shi, 'b' to R.drawable.b_xiang,
            'n' to R.drawable.b_ma, 'r' to R.drawable.b_ju, 'c' to R.drawable.b_pao,
            'p' to R.drawable.b_zu
        )
        /** 每种棋子的标准数量上限（场上已有的不再出现在悬浮窗） */
        val MAX_COUNT = mapOf(
            'K' to 1, 'A' to 2, 'B' to 2, 'N' to 2, 'R' to 2, 'C' to 2, 'P' to 5,
            'k' to 1, 'a' to 2, 'b' to 2, 'n' to 2, 'r' to 2, 'c' to 2, 'p' to 5
        )
    }

    var grid: Array<CharArray> = Array(10) { CharArray(9) { '.' } }
        set(value) { field = value; invalidate() }

    /** 选中格（显示四角直角标记），-1 无 */
    var selected = -1
        set(value) { field = value; invalidate() }

    /** 悬浮窗：显示的棋子（按剩余数量动态计算） */
    var popupPieces: List<Char> = emptyList()
        private set

    /** 悬浮窗锚点格（点空格时弹出），-1 无 */
    var popupAnchor = -1
        private set

    /** 悬浮窗点击回调（返回选中的棋子字符） */
    var onPiecePick: ((Char) -> Unit)? = null

    /** 点击格子回调（宿主处理选中/移动/收起状态机） */
    var onCellTap: ((row: Int, col: Int) -> Unit)? = null

    private val boardBmp: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.chessboard)
    private val pieceCache = HashMap<Char, Bitmap>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFFE23B24.toInt()
    }
    private val popupBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2222222.toInt()
    }
    private val popupItemPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 布局缓存：绘制与点击共用
    private var s = 1f
    private var top = 0f
    private var pieceSize = 0f
    private var popupRect = RectF()
    private val itemRects = ArrayList<RectF>()

    /** 计算当前可用棋子（场上已有数量已达上限的不显示） */
    fun computeAvailable(): List<Char> {
        val counts = HashMap<Char, Int>()
        for (row in grid) for (ch in row) if (ch != '.') counts[ch] = (counts[ch] ?: 0) + 1
        return PIECES.filter { (counts[it] ?: 0) < (MAX_COUNT[it] ?: 0) }
    }

    fun showPopup(anchorCell: Int, pieces: List<Char>) {
        selected = -1
        popupAnchor = anchorCell
        popupPieces = pieces
        invalidate()
    }

    fun hidePopup() {
        popupAnchor = -1
        popupPieces = emptyList()
        invalidate()
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        s = width.toFloat() / BOARD_WIDTH
        top = (height - BOARD_HEIGHT * s) / 2f
        pieceSize = BOARD_PIECE_SIZE * s
        canvas.drawBitmap(boardBmp, null,
            Rect(0, top.toInt(), width, (top + BOARD_HEIGHT * s).toInt()), paint)

        for (r in 0 until 10) for (c in 0 until 9) {
            val ch = grid[r][c]
            val cx = cellX(c); val cy = cellY(r)
            if (ch != '.') {
                pieceFor(ch)?.let {
                    canvas.drawBitmap(it, null,
                        Rect((cx - pieceSize / 2).toInt(), (cy - pieceSize / 2).toInt(),
                            (cx + pieceSize / 2).toInt(), (cy + pieceSize / 2).toInt()), paint)
                }
            }
        }

        // 选中四角直角标记（参照效果图的红色 L 形角）
        if (selected >= 0) {
            val r = selected / 9; val c = selected % 9
            drawCorners(canvas, cellX(c), cellY(r))
        }

        if (popupAnchor >= 0 && popupPieces.isNotEmpty()) {
            drawPopup(canvas)
        }
    }

    private fun drawCorners(canvas: Canvas, cx: Float, cy: Float) {
        val half = pieceSize / 2 + 6f
        val arm = pieceSize * 0.28f
        val l = cx - half; val rr = cx + half
        val t = cy - half; val b = cy + half
        // 左上
        canvas.drawLine(l, t, l + arm, t, selectPaint)
        canvas.drawLine(l, t, l, t + arm, selectPaint)
        // 右上
        canvas.drawLine(rr, t, rr - arm, t, selectPaint)
        canvas.drawLine(rr, t, rr, t + arm, selectPaint)
        // 左下
        canvas.drawLine(l, b, l + arm, b, selectPaint)
        canvas.drawLine(l, b, l, b - arm, selectPaint)
        // 右下
        canvas.drawLine(rr, b, rr - arm, b, selectPaint)
        canvas.drawLine(rr, b, rr, b - arm, selectPaint)
    }

    /** 悬浮窗：深色圆角卡片，红/黑各一排圆形棋子，尺寸随数量动态变化、位置智能避让 */
    private fun drawPopup(canvas: Canvas) {
        itemRects.clear()
        if (popupPieces.isEmpty()) return
        val red = popupPieces.filter { it.isUpperCase() }
        val black = popupPieces.filter { it.isLowerCase() }
        val cols = maxOf(1, maxOf(red.size, black.size))
        val rows = if (red.isNotEmpty() && black.isNotEmpty()) 2 else 1
        val item = pieceSize * 0.72f
        val pad = item * 0.18f
        val w = cols * item + (cols + 1) * pad
        val h = rows * item + (rows + 1) * pad + 4f

        // 锚点格中心；悬浮窗默认显示在格子下方，靠近边缘时自动翻到上方/收进边界
        val ar = popupAnchor / 9; val ac = popupAnchor % 9
        val ax = cellX(ac); val ay = cellY(ar)
        var left = ax - w / 2
        var topY = ay + pieceSize / 2 + 8f
        if (topY + h > height - 8f) topY = ay - pieceSize / 2 - 8f - h
        left = left.coerceIn(8f, width - w - 8f)
        topY = topY.coerceIn(8f, height - h - 8f)
        popupRect.set(left, topY, left + w, topY + h)

        canvas.drawRoundRect(popupRect, 18f, 18f, popupBg)
        // 两排：红上黑下（只有一方时单排）；itemRects 顺序与 popupPieces 完全一致
        var rowIdx = 0
        for (rowPieces in listOf(red, black)) {
            if (rowPieces.isEmpty()) continue
            val baseY = topY + pad + rowIdx * (item + pad) + item / 2
            for ((i, ch) in rowPieces.withIndex()) {
                val x = left + pad + i * (item + pad) + item / 2
                val y = baseY
                val r = item * 0.46f
                popupItemPaint.style = Paint.Style.FILL
                popupItemPaint.color = 0xFFF5E9CC.toInt()
                canvas.drawCircle(x, y, r, popupItemPaint)
                pieceFor(ch)?.let {
                    canvas.drawBitmap(it, null,
                        Rect((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt()), paint)
                }
                itemRects.add(RectF(x - r, y - r, x + r, y + r))
            }
            rowIdx++
        }
    }

    private fun cellX(c: Int) =
        (BOARD_X_OFFSET + BOARD_PIECE_SIZE / 2f + c * BOARD_GRID_INTERVAL) * s
    private fun cellY(r: Int) =
        top + (BOARD_Y_OFFSET + BOARD_PIECE_SIZE / 2f + r * BOARD_GRID_INTERVAL) * s

    private fun pieceFor(ch: Char): Bitmap? {
        pieceCache[ch]?.let { return it }
        val id = PIECE_DRAWABLES[ch] ?: return null
        val bmp = BitmapFactory.decodeResource(resources, id)
        pieceCache[ch] = bmp
        return bmp
    }

    // ------------------------------------------------------------------
    // 触摸：先判悬浮窗内点击，再判棋盘格
    // ------------------------------------------------------------------

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_DOWN) return true
        // 悬浮窗棋子命中
        if (popupAnchor >= 0) {
            for ((i, rect) in itemRects.withIndex()) {
                if (rect.contains(ev.x, ev.y)) {
                    val ch = popupPieces.getOrNull(i)
                    if (ch != null) onPiecePick?.invoke(ch)
                    return true
                }
            }
            // 点悬浮窗外：关闭
            if (!popupRect.contains(ev.x, ev.y)) hidePopup()
        }
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
