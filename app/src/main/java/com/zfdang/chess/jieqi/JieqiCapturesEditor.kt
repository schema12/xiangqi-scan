package com.zfdang.chess.jieqi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 吃子记录编辑面板（校准页 / 对局页共用）。
 *
 * 红黑两行，每行 6 个明子（帅/将不会被吃，故不含）+ 1 个暗子背面图标。
 * - 点击：数量 +1，到上限后再点归零；长按：数量 -1
 * - 上限：常规子 2、兵/卒 5、暗子 9
 * - 左上角数字角标显示已被吃数量（>1 时）
 *
 * 结果通过 [buildSpec] 导出为 "红明子,红暗数;黑明子,黑暗数"。
 */
class JieqiCapturesEditor(context: Context) : LinearLayout(context) {

    private val redPieces = LinkedHashMap<Char, Int>()
    private val blackPieces = LinkedHashMap<Char, Int>()
    private var redDark = 0
    private var blackDark = 0
    private val views = HashMap<Char, PiecePickView>()

    private val revealChars = listOf('A', 'B', 'N', 'R', 'C', 'P')
    private val hideChars = listOf('a', 'b', 'n', 'r', 'c', 'p')

    init {
        orientation = VERTICAL
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(buildRow("红方吃掉：", revealChars, JieqiCaptures.DARK_RED))
        addView(buildRow("黑方吃掉：", hideChars, JieqiCaptures.DARK_BLACK))
    }

    /** 用已有 spec 初始化面板。 */
    fun load(spec: String?) {
        val (red, black) = JieqiCaptures.parse(spec)
        redPieces.clear(); blackPieces.clear()
        for ((ch, n) in red) if (ch != JieqiCaptures.DARK_RED) redPieces[ch] = n
        for ((ch, n) in black) if (ch != JieqiCaptures.DARK_BLACK) blackPieces[ch] = n
        redDark = red[JieqiCaptures.DARK_RED] ?: 0
        blackDark = black[JieqiCaptures.DARK_BLACK] ?: 0
        for ((ch, v) in views) v.count = currentCount(ch)
    }

    /** 导出当前编辑结果为传输串。 */
    fun buildSpec(): String {
        val red = LinkedHashMap(redPieces)
        val black = LinkedHashMap(blackPieces)
        if (redDark > 0) red[JieqiCaptures.DARK_RED] = redDark
        if (blackDark > 0) black[JieqiCaptures.DARK_BLACK] = blackDark
        return JieqiCaptures.format(red, black)
    }

    private fun currentCount(ch: Char): Int = when (ch) {
        JieqiCaptures.DARK_RED -> redDark
        JieqiCaptures.DARK_BLACK -> blackDark
        else -> (if (ch.isUpperCase()) redPieces else blackPieces)[ch] ?: 0
    }

    private fun setCount(ch: Char, n: Int) {
        val capped = n.coerceIn(0, JieqiCaptures.maxOf(ch))
        when (ch) {
            JieqiCaptures.DARK_RED -> redDark = capped
            JieqiCaptures.DARK_BLACK -> blackDark = capped
            else -> {
                val map = if (ch.isUpperCase()) redPieces else blackPieces
                if (capped == 0) map.remove(ch) else map[ch] = capped
            }
        }
        for ((c, v) in views) v.count = currentCount(c)
    }

    private fun buildRow(title: String, pieces: List<Char>, darkChar: Char): View {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(context).apply {
            text = "${title}（点一下 +1，到上限再点归零；长按 -1）"
            textSize = 13f
            setTextColor(0xFF444444.toInt())
        })
        val icons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (ch in pieces + darkChar) {
            val bmp = if (ch == JieqiCaptures.DARK_RED || ch == JieqiCaptures.DARK_BLACK)
                JieqiTiles.darkTile(96) else pieceBitmap(ch)
            val v = PiecePickView(context, ch, bmp) { viewCh, next -> setCount(viewCh, next) }
            views[ch] = v
            icons.addView(v)
        }
        row.addView(icons)
        return row
    }

    private fun pieceBitmap(ch: Char): Bitmap? {
        val id = when (ch) {
            'A' -> com.zfdang.chess.R.drawable.r_shi
            'B' -> com.zfdang.chess.R.drawable.r_xiang
            'N' -> com.zfdang.chess.R.drawable.r_ma
            'R' -> com.zfdang.chess.R.drawable.r_ju
            'C' -> com.zfdang.chess.R.drawable.r_pao
            'P' -> com.zfdang.chess.R.drawable.r_bing
            'a' -> com.zfdang.chess.R.drawable.b_shi
            'b' -> com.zfdang.chess.R.drawable.b_xiang
            'n' -> com.zfdang.chess.R.drawable.b_ma
            'r' -> com.zfdang.chess.R.drawable.b_ju
            'c' -> com.zfdang.chess.R.drawable.b_pao
            'p' -> com.zfdang.chess.R.drawable.b_zu
            else -> return null
        }
        return BitmapFactory.decodeResource(resources, id)
    }

    /** 单个候选：点击 +1 / 到顶归零，长按 -1。 */
    private class PiecePickView(
        context: Context,
        private val ch: Char,
        private val bmp: Bitmap?,
        private val onChange: (Char, Int) -> Unit
    ) : View(context) {
        var count = 0
            set(value) { field = value; invalidate() }

        private var longPressed = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val longPressRunnable = Runnable {
            longPressed = true
            onChange(ch, (count - 1).coerceAtLeast(0))
        }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        override fun onMeasure(w: Int, h: Int) {
            val d = (resources.displayMetrics.density * 40).toInt()
            setMeasuredDimension(d, (d * 1.12f).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val alpha = if (count > 0) 255 else 100
            if (bmp != null) {
                p.alpha = alpha
                val size = minOf(width, height) * 0.9f
                canvas.drawBitmap(bmp, null,
                    RectF((width - size) / 2, (height - size) / 2,
                        (width + size) / 2, (height + size) / 2), p)
            }
            p.alpha = 255
            if (count > 1) {
                p.color = 0xFF8C2B1E.toInt(); p.style = Paint.Style.FILL
                val r = minOf(width, height) * 0.24f
                canvas.drawCircle(r, r, r, p)
                p.color = 0xFFFFFFFF.toInt(); p.textSize = r * 1.35f
                val fm = p.fontMetrics
                canvas.drawText(count.toString(), r, r - (fm.ascent + fm.descent) / 2f, p)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    handler.postDelayed(longPressRunnable, 500)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!longPressed) {
                        val max = JieqiCaptures.maxOf(ch)
                        onChange(ch, if (count >= max) 0 else count + 1)
                    }
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    longPressed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 手指明显移动则取消长按
                    if (ev.x < 0 || ev.x > width || ev.y < 0 || ev.y > height) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                }
            }
            return true
        }
    }
}
