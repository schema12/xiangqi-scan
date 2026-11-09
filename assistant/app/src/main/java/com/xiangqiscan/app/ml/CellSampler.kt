package com.xiangqiscan.app.ml

import android.graphics.Bitmap
import com.xiangqiscan.app.cv.BoardDetector
import kotlin.math.hypot
import kotlin.math.min

/** 从工作位图 + 四角提取 90 个交点小图 */
object CellSampler {

    const val CROP_SIZE = 48

    /**
     * @return 长度 90 的 Bitmap 数组（index = row*9+col，row0 = 图像顶部一行）
     */
    fun sample(work: Bitmap, quad: BoardDetector.Quad): Array<Bitmap?> {
        val pts = BoardDetector.gridPoints(quad)
        val cellH = min(
            hypot((quad.tr.x - quad.tl.x).toDouble(), (quad.tr.y - quad.tl.y).toDouble()),
            hypot((quad.br.x - quad.bl.x).toDouble(), (quad.br.y - quad.bl.y).toDouble())
        ) / 9.0
        val cellW = min(
            hypot((quad.bl.x - quad.tl.x).toDouble(), (quad.bl.y - quad.tl.y).toDouble()),
            hypot((quad.br.x - quad.tr.x).toDouble(), (quad.br.y - quad.tr.y).toDouble())
        ) / 8.0
        val radius = (min(cellW, cellH) * 0.48).toFloat().coerceIn(8f, 120f)

        val out = arrayOfNulls<Bitmap>(90)
        for (i in 0 until 90) {
            val cx = pts[i * 2]
            val cy = pts[i * 2 + 1]
            val x0 = (cx - radius).toInt()
            val y0 = (cy - radius).toInt()
            val size = (radius * 2).toInt().coerceAtLeast(4)
            if (x0 < 0 || y0 < 0 || x0 + size > work.width || y0 + size > work.height) continue
            val crop = Bitmap.createBitmap(work, x0, y0, size, size)
            out[i] = Bitmap.createScaledBitmap(crop, CROP_SIZE, CROP_SIZE, true)
            if (crop !== out[i]) crop.recycle()
        }
        return out
    }
}
