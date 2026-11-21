package com.zfdang.chess.jieqi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/** 暗子瓦片位图（红/黑底 + 「揭」字），BoardEditView 与 JieqiBoardView 共用。 */
object JieqiTiles {
    private val cache = HashMap<Int, Bitmap>()

    fun darkTile(red: Boolean, size: Int = 110): Bitmap {
        val key = if (red) size else -size
        cache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (red) 0xFFEFD9B8.toInt() else 0xFF3E3A36.toInt()
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = size * 0.055f
            color = if (red) 0xFFA33D32.toInt() else 0xFFC9B896.toInt()
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2f, body)
        c.drawCircle(size / 2f, size / 2f, size / 2f - size * 0.045f, ring)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (red) 0xFFA33D32.toInt() else 0xFFE8DCC0.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = size * 0.5f
        }
        c.drawText("揭", size / 2f, size / 2f + tp.textSize / 3f, tp)
        cache[key] = bmp
        return bmp
    }
}
