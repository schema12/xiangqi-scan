package com.xiangqiscan.app.ml

import android.graphics.Bitmap
import com.xiangqiscan.app.core.Side

/**
 * 无模型时的启发式回退：只判断"有没有子"和"红/黑"，字形交给人工。
 * 依据：交点附近墨迹（暗/红像素）占比。
 */
class HeuristicClassifier : PieceClassifier {

    override fun classify(crop: Bitmap): CellResult {
        val w = crop.width; val h = crop.height
        if (w == 0 || h == 0) return CellResult(empty = true)
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        var ink = 0
        var red = 0
        for (p in px) {
            android.graphics.Color.colorToHSV(p, hsv)
            val s = hsv[1] * 255f
            val v = hsv[2] * 255f
            val isDark = v < 90 && s < 120
            val isRed = hsv[0] in 0f..25f && s > 90 && v > 60
            if (isDark || isRed) {
                ink++
                if (isRed) red++
            }
        }
        val ratio = ink.toFloat() / (w * h)
        if (ratio < 0.05f) return CellResult(empty = true)
        return CellResult(
            empty = false,
            side = if (red * 2 > ink - red) Side.RED else Side.BLACK,
            type = null,
            confidence = ratio
        )
    }
}
