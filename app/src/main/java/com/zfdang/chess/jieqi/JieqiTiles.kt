package com.zfdang.chess.jieqi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 暗子背面瓦片（无字），BoardEditView / JieqiBoardView / JieqiCapturedView 共用。
 *
 * 重要：未翻明的暗子对所有玩家都只显示「统一的背面」，不通过颜色透露归属
 * （参考 engine.js sanitize：未揭示暗子的真实身份/归属不出现在客户端视图）。
 * 因此背面不再按红黑区分，只有一种样式。
 */
object JieqiTiles {
    private var cached: Bitmap? = null

    fun darkTile(size: Int = 110): Bitmap {
        cached?.let { if (it.width == size) return it }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 中性木色底 + 深棕描边，完全不体现红黑
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7D6B2.toInt() }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.05f
            color = 0xFF8C7355.toInt()
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2f, body)
        c.drawCircle(size / 2f, size / 2f, size / 2f - size * 0.05f, ring)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.016f
            color = 0x668C7355
        }
        c.drawCircle(size / 2f, size / 2f, size * 0.30f, inner)

        cached = bmp
        return bmp
    }
}
