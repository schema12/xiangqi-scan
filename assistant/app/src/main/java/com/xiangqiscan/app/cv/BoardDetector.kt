package com.xiangqiscan.app.cv

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 棋盘外框检测：在缩小的灰度图上做受限角度的 Hough 直线检测，
 * 取最强的"横族"平行线对与"竖族"平行线对，交出四角。
 * 失败时返回 null（UI 提供手动拖四角）。
 */
object BoardDetector {

    /** 检测结果：棋盘最外层网格线的四个角 */
    data class Quad(
        val tl: PointF, val tr: PointF, val br: PointF, val bl: PointF
    ) {
        fun corners(): Array<PointF> = arrayOf(tl, tr, br, bl)
    }

    private class Line(val theta: Double, val rho: Float) {
        // x*cos + y*sin = rho
        fun intersect(o: Line): PointF? {
            val a = cos(theta); val b = sin(theta)
            val c = cos(o.theta); val d = sin(o.theta)
            val det = a * d - b * c
            if (abs(det) < 1e-9) return null
            val x = (rho * d - b * o.rho) / det
            val y = (a * o.rho - c * rho) / det
            return PointF(x.toFloat(), y.toFloat())
        }
    }

    /**
     * @param work 工作位图（建议 ≤1280px），直接使用其坐标输出 Quad
     */
    fun detect(work: Bitmap): Quad? {
        val w = work.width
        val h = work.height
        // Hough 用更小的图
        val hs = 640f / maxOf(w, h)
        val hw = if (hs < 1f) (w * hs).toInt().coerceAtLeast(64) else w
        val hh = if (hs < 1f) (h * hs).toInt().coerceAtLeast(64) else h
        val small = Bitmap.createScaledBitmap(work, hw, hh, true)
        val px = IntArray(hw * hh)
        small.getPixels(px, 0, hw, 0, 0, hw, hh)
        val lum = FloatArray(hw * hh)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Sobel 梯度
        val mag = FloatArray(hw * hh)
        for (y in 1 until hh - 1) {
            for (x in 1 until hw - 1) {
                val i = y * hw + x
                val gx = -lum[i - hw - 1] - 2f * lum[i - 1] - lum[i + hw - 1] +
                        lum[i - hw + 1] + 2f * lum[i + 1] + lum[i + hw + 1]
                val gy = -lum[i - hw - 1] - 2f * lum[i - hw] - lum[i - hw + 1] +
                        lum[i + hw - 1] + 2f * lum[i + hw] + lum[i + hw + 1]
                mag[i] = abs(gx) + abs(gy)
            }
        }
        // 边缘阈值：取前 12% 强度
        val sorted = mag.copyOf().also { it.sort() }
        val thr = sorted[(sorted.size * 0.88f).toInt().coerceIn(0, sorted.size - 1)]

        // Hough：横族（法向角 62°~118°），竖族（-28°~28°），全部在小图坐标系内
        val hLines = hough(mag, thr, hw, hh, horizontal = true)
        val vLines = hough(mag, thr, hw, hh, horizontal = false)
        val scale = 1f / if (hs < 1f) hs else 1f

        val bestH = pickPair(hLines, hh * 0.55f) ?: return null
        val bestV = pickPair(vLines, hw * 0.45f) ?: return null

        val tl = bestH.first.intersect(bestV.first) ?: return null
        val tr = bestH.first.intersect(bestV.second) ?: return null
        val br = bestH.second.intersect(bestV.second) ?: return null
        val bl = bestH.second.intersect(bestV.first) ?: return null

        val q = Quad(
            tl.multiply(scale), tr.multiply(scale),
            br.multiply(scale), bl.multiply(scale)
        )
        if (!plausible(q, w, h)) return null
        return q
    }

    private fun PointF.multiply(k: Float) = PointF(x * k, y * k)

    private fun plausible(q: Quad, w: Int, h: Int): Boolean {
        val cs = q.corners()
        // 面积 ≥ 12%
        var area2 = 0f
        for (i in 0 until 4) {
            val a = cs[i]; val b = cs[(i + 1) % 4]
            area2 += a.x * b.y - b.x * a.y
        }
        val area = abs(area2) / 2f
        if (area < 0.12f * w * h) return false
        // 边长比
        val top = hypot((q.tr.x - q.tl.x).toDouble(), (q.tr.y - q.tl.y).toDouble())
        val left = hypot((q.bl.x - q.tl.x).toDouble(), (q.bl.y - q.tl.y).toDouble())
        if (top < 40 || left < 40) return false
        val ratio = top / left
        return ratio in 0.5..1.7
    }

    /** 找相距最远的一对强线（lines 已按强度降序，票数即强度） */
    private fun pickPair(lines: List<Line>, minSep: Float): Pair<Line, Line>? {
        if (lines.size < 2) return null
        var best: Pair<Line, Line>? = null
        var bestScore = -1f
        val byRho = lines.sortedBy { it.rho }
        for (i in byRho.indices) {
            for (j in byRho.size - 1 downTo i + 1) {
                val sep = byRho[j].rho - byRho[i].rho
                if (sep < minSep) break
                val score = sep
                if (score > bestScore) {
                    bestScore = score
                    best = byRho[i] to byRho[j]
                }
            }
        }
        return best
    }

    private fun hough(
        mag: FloatArray, thr: Float,
        w: Int, h: Int, horizontal: Boolean
    ): List<Line> {
        val thetas = mutableListOf<Double>()
        if (horizontal) {
            var t = 62.0
            while (t <= 118.0) { thetas.add(Math.toRadians(t)); t += 1.5 }
        } else {
            var t = -28.0
            while (t <= 28.0) { thetas.add(Math.toRadians(t)); t += 1.5 }
        }
        val maxRho = (hypot(w.toDouble(), h.toDouble()) * 1.5).toInt() + 1
        val rhoStep = 2
        val acc = Array(thetas.size) { IntArray(maxRho * 2 / rhoStep + 2) }

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (mag[i] < thr) continue
                for (ti in thetas.indices) {
                    val th = thetas[ti]
                    val r = (x * cos(th) + y * sin(th)).toInt()
                    val ri = (r / rhoStep + maxRho / rhoStep / 2).coerceIn(0, acc[ti].size - 1)
                    acc[ti][ri]++
                }
            }
        }
        // 每个 theta 取 rho 峰，全局收集 top 峰（非极大抑制）
        data class Peak(val ti: Int, val ri: Int, val votes: Int)
        val peaks = mutableListOf<Peak>()
        for (ti in acc.indices) {
            for (ri in 1 until acc[ti].size - 1) {
                val v = acc[ti][ri]
                if (v >= 40 && v >= acc[ti][ri - 1] && v >= acc[ti][ri + 1]) {
                    peaks.add(Peak(ti, ri, v))
                }
            }
        }
        peaks.sortByDescending { it.votes }
        val out = mutableListOf<Line>()
        val minRhoSep = (min(w, h) * 0.10f).toInt() / rhoStep + 1
        for (p in peaks) {
            if (out.size >= 12) break
            val th = thetas[p.ti]
            val rho = ((p.ri - maxRho / rhoStep / 2) * rhoStep).toFloat()
            var dup = false
            for (l in out) {
                val drho = abs(l.rho - rho)
                val dth = abs(l.theta - th)
                if (drho < minRhoSep * rhoStep && dth < Math.toRadians(4.0)) { dup = true; break }
            }
            if (!dup) out.add(Line(th, rho))
        }
        return out
    }

    /** 由四角计算 90 个交点坐标（列 0..8，行 0..9），返回 FloatArray(90*2) */
    fun gridPoints(q: Quad): FloatArray {
        val hg = Homography(
            floatArrayOf(0f, 1f, 1f, 0f), floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(q.tl.x, q.tr.x, q.br.x, q.bl.x),
            floatArrayOf(q.tl.y, q.tr.y, q.br.y, q.bl.y)
        )
        val out = FloatArray(90 * 2)
        for (r in 0 until 10) {
            for (c in 0 until 9) {
                val (x, y) = hg.map(c / 8f, r / 9f)
                out[(r * 9 + c) * 2] = x
                out[(r * 9 + c) * 2 + 1] = y
            }
        }
        return out
    }
}
