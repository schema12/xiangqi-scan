package com.xiangqiscan.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xiangqiscan.app.core.Piece
import com.xiangqiscan.app.core.Position
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 可交互棋盘：绘制 9x10 网格与棋子，点选交叉点。
 * 显示约定：row0 在顶部（黑方底线在上，红方在下），与 Position/FEN 一致。
 */
class BoardEditorView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var position = Position()
    /** 需要人工指定字形的点（识别到子但认不出类型） */
    val pending = mutableSetOf<Int>()
    var selected = -1
        private set
    var onSelect: ((Int) -> Unit)? = null

    private var cell = 0f
    private var originX = 0f
    private var originY = 0f

    private val bgPaint = Paint().apply { color = Color.rgb(232, 200, 143) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 58, 30)
        strokeWidth = 2f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 58, 30)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 236, 205)
        style = Paint.Style.FILL
    }
    private val diskRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 90, 50)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 162, 75)
        style = Paint.Style.FILL
    }
    private val redText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(193, 39, 45)
        textAlign = Paint.Align.CENTER
    }
    private val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 26, 26)
        textAlign = Paint.Align.CENTER
    }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 140, 140)
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cell = minOf(w / 9.4f, h / 10.4f)
        originX = (w - cell * 8) / 2
        originY = (h - cell * 9) / 2
    }

    fun pointCenter(r: Int, c: Int): Pair<Float, Float> =
        originX + c * cell to originY + r * cell

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (cell <= 0) return

        // 网格：横线 10 条全宽；竖线两侧全高，中间在楚河汉界断开
        for (r in 0 until 10) {
            val (_, y) = pointCenter(r, 0)
            canvas.drawLine(originX, y, originX + 8 * cell, y, linePaint)
        }
        for (c in 0 until 9) {
            if (c == 0 || c == 8) {
                canvas.drawLine(originX + c * cell, originY,
                    originX + c * cell, originY + 9 * cell, linePaint)
            } else {
                canvas.drawLine(originX + c * cell, originY,
                    originX + c * cell, originY + 4 * cell, linePaint)
                canvas.drawLine(originX + c * cell, originY + 5 * cell,
                    originX + c * cell, originY + 9 * cell, linePaint)
            }
        }
        // 外框加粗
        canvas.drawRect(originX, originY, originX + 8 * cell, originY + 9 * cell, borderPaint)

        redText.textSize = cell * 0.52f
        blackText.textSize = cell * 0.52f
        hintText.textSize = cell * 0.45f

        for (r in 0 until 10) {
            for (c in 0 until 9) {
                val i = r * 9 + c
                val (x, y) = pointCenter(r, c)
                if (i == selected) {
                    canvas.drawCircle(x, y, cell * 0.55f, selPaint)
                }
                val p: Piece? = position.get(r, c)
                if (p == null && i !in pending) continue
                // 棋子圆盘
                canvas.drawCircle(x, y, cell * 0.46f, diskPaint)
                canvas.drawCircle(x, y, cell * 0.46f, diskRing)
                if (p != null) {
                    val tp = if (p.side == com.xiangqiscan.app.core.Side.RED) redText else blackText
                    val baseline = y - (tp.ascent() + tp.descent()) / 2
                    canvas.drawText(p.glyph(), x, baseline, tp)
                } else {
                    val baseline = y - (hintText.ascent() + hintText.descent()) / 2
                    canvas.drawText("?", x, baseline, hintText)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            val c = ((e.x - originX) / cell + 0.5f).toInt()
            val r = ((e.y - originY) / cell + 0.5f).toInt()
            if (r in 0 until 10 && c in 0 until 9) {
                val (cx, cy) = pointCenter(r, c)
                val d = hypot((e.x - cx).toDouble(), (e.y - cy).toDouble())
                if (d < cell * 0.6) {
                    selected = r * 9 + c
                    invalidate()
                    onSelect?.invoke(selected)
                    performClick()
                    return true
                }
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
