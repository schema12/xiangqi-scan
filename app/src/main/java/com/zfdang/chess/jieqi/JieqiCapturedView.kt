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
        val n = pieces.size
        val icon = min(height * 0.82f, width / maxOf(n, 1) * 0.9f)
        val gap = icon * 0.06f
        val totalW = n * icon + (n - 1) * gap
        var x = (width - totalW) / 2f
        val y = (height - icon) / 2f
        for (ch in pieces) {
            val bmp = bitmapFor(ch)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null,
                    Rect(x.toInt(), y.toInt(), (x + icon).toInt(), (y + icon).toInt()), paint)
            }
            x += icon + gap
        }
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
