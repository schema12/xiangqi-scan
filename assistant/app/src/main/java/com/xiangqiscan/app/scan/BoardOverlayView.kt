package com.xiangqiscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xiangqiscan.app.cv.BoardDetector
import kotlin.math.hypot

/**
 * 显示原图 + 可拖动的四角框。
 * 角点内部保存"归一化坐标"（相对 bitmap 宽高的 0..1），视图缩放/平移自动适配。
 */
class BoardOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var bitmap: Bitmap? = null

    /** 四角（归一化 0..1） */
    var quadNorm: BoardDetector.Quad? = null

    private var dragIdx = -1
    private val handleRadiusPx = 26f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 46, 91, 52)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 200, 162, 75)
        style = Paint.Style.FILL
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** 直接设置"原图像素坐标"下的四角（检测结果入口） */
    fun setQuadPixels(q: BoardDetector.Quad) {
        val bw = bitmap?.width?.toFloat() ?: 1f
        val bh = bitmap?.height?.toFloat() ?: 1f
        fun norm(p: PointF) = PointF(p.x / bw, p.y / bh)
        quadNorm = BoardDetector.Quad(norm(q.tl), norm(q.tr), norm(q.br), norm(q.bl))
        invalidate()
    }

    /** 输出原图像素坐标下的四角 */
    fun quadInBitmap(): BoardDetector.Quad? {
        val q = quadNorm ?: return null
        val bw = bitmap?.width?.toFloat() ?: 1f
        val bh = bitmap?.height?.toFloat() ?: 1f
        fun denorm(p: PointF) = PointF(p.x * bw, p.y * bh)
        return BoardDetector.Quad(denorm(q.tl), denorm(q.tr), denorm(q.br), denorm(q.bl))
    }

    private fun drawRect(w: Int, h: Int, bm: Bitmap): RectF {
        val s = minOf(w / bm.width.toFloat(), h / bm.height.toFloat())
        val dw = bm.width * s
        val dh = bm.height * s
        return RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
    }

    private fun normToView(p: PointF, r: RectF): PointF =
        PointF(r.left + p.x * r.width(), r.top + p.y * r.height())

    private fun viewToNorm(x: Float, y: Float, r: RectF): PointF =
        PointF(((x - r.left) / r.width()).coerceIn(0f, 1f), ((y - r.top) / r.height()).coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return
        val r = drawRect(width, height, bm)
        canvas.drawBitmap(bm, null, r, null)
        val q = quadNorm ?: return
        val cs = arrayOf(normToView(q.tl, r), normToView(q.tr, r), normToView(q.br, r), normToView(q.bl, r))
        for (i in 0 until 4) {
            canvas.drawLine(cs[i].x, cs[i].y, cs[(i + 1) % 4].x, cs[(i + 1) % 4].y, linePaint)
        }
        for (p in cs) {
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handlePaint)
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleRing)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val q = quadNorm ?: return false
        val bm = bitmap ?: return false
        val r = drawRect(width, height, bm)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val cs = arrayOf(q.tl, q.tr, q.br, q.bl)
                var best = -1
                var bestD = Float.MAX_VALUE
                for (i in cs.indices) {
                    val v = normToView(cs[i], r)
                    val d = hypot((v.x - e.x).toDouble(), (v.y - e.y).toDouble()).toFloat()
                    if (d < bestD) { bestD = d; best = i }
                }
                if (best >= 0 && bestD < handleRadiusPx * 3) {
                    dragIdx = best
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIdx >= 0) {
                    val p = viewToNorm(e.x, e.y, r)
                    val cur = quadNorm!!
                    quadNorm = when (dragIdx) {
                        0 -> BoardDetector.Quad(p, cur.tr, cur.br, cur.bl)
                        1 -> BoardDetector.Quad(cur.tl, p, cur.br, cur.bl)
                        2 -> BoardDetector.Quad(cur.tl, cur.tr, p, cur.bl)
                        else -> BoardDetector.Quad(cur.tl, cur.tr, cur.br, p)
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                dragIdx = -1
            }
        }
        return false
    }
}
