package com.zfdang.chess.jieqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 揭棋吃子区：横向排列一方吃掉的棋子。
 * - 明子：正面棋子素材
 * - 暗子已知身份：按揭晓字母显示正面
 * - 暗子未知身份：显示无字背面（X/x 瓦片）
 *
 * 用 [pieces] 传入显示字符列表：普通字母=正面棋子；'X'/'x'=无字背面。
 */
class JieqiCapturedView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var pieces: List<Char> = emptyList()
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceCache = HashMap<Char, Bitmap>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.13f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pieces.isEmpty() || width == 0 || height == 0) return
        // 同一棋子只画一个，左上角标数字（吃子超过 1 个时）
        val groups = JieqiCaptures.collapse(pieces)
        val n = groups.size
        val icon = min(height * 0.82f, width / maxOf(n, 1) * 0.9f)
        val gap = icon * 0.06f
        val totalW = n * icon + (n - 1) * gap
        var x = (width - totalW) / 2f
        val y = (height - icon) / 2f
        for ((ch, count) in groups) {
            val bmp = bitmapFor(ch)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null,
                    Rect(x.toInt(), y.toInt(), (x + icon).toInt(), (y + icon).toInt()), paint)
            }
            if (count > 1) drawBadge(canvas, x, y, count, icon)
            x += icon + gap
        }
    }

    /** 数字角标：左上角画一个小圆底 + 白色数字。 */
    private fun drawBadge(canvas: Canvas, left: Float, top: Float, count: Int, icon: Float) {
        val r = icon * 0.22f
        val cx = left + r * 0.65f
        val cy = top + r * 0.65f
        badgePaint.color = 0xFF8C2B1E.toInt()
        canvas.drawCircle(cx, cy, r, badgePaint)
        badgePaint.color = 0xFFFFFFFF.toInt()
        badgePaint.textAlign = Paint.Align.CENTER
        badgePaint.textSize = r * 1.35f
        val fm = badgePaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(count.toString(), cx, baseline, badgePaint)
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private fun bitmapFor(ch: Char): Bitmap? {
        if (ch == 'X' || ch == 'x') return JieqiTiles.darkTile()
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
}
