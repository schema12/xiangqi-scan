package com.xiangqiscan.app.cv

import kotlin.math.abs

/**
 * 3x3 单应性矩阵（行优先）。将单位方形 (0..1, 0..1) 映射到任意四边形，
 * 用于从棋盘外框四角直接采样 9x10 交点。
 */
class Homography(srcX: FloatArray, srcY: FloatArray, dstX: FloatArray, dstY: FloatArray) {
    // h11 h12 h13 h21 h22 h23 h31 h32 h33=1
    private val h = FloatArray(9)

    init {
        require(srcX.size == 4 && srcY.size == 4 && dstX.size == 4 && dstY.size == 4)
        val x0 = srcX[0]; val y0 = srcY[0]
        val x1 = srcX[1]; val y1 = srcY[1]
        val x2 = srcX[2]; val y2 = srcY[2]
        val x3 = srcX[3]; val y3 = srcY[3]
        val u0 = dstX[0]; val v0 = dstY[0]
        val u1 = dstX[1]; val v1 = dstY[1]
        val u2 = dstX[2]; val v2 = dstY[2]
        val u3 = dstX[3]; val v3 = dstY[3]
        // A * hVec = b，8 个方程（每个点 x、y 各一），hVec = [a..h]，h33=1
        val A = arrayOf(
            floatArrayOf(x0, y0, 1f, 0f, 0f, 0f, -u0 * x0, -u0 * y0),
            floatArrayOf(0f, 0f, 0f, x0, y0, 1f, -v0 * x0, -v0 * y0),
            floatArrayOf(x1, y1, 1f, 0f, 0f, 0f, -u1 * x1, -u1 * y1),
            floatArrayOf(0f, 0f, 0f, x1, y1, 1f, -v1 * x1, -v1 * y1),
            floatArrayOf(x2, y2, 1f, 0f, 0f, 0f, -u2 * x2, -u2 * y2),
            floatArrayOf(0f, 0f, 0f, x2, y2, 1f, -v2 * x2, -v2 * y2),
            floatArrayOf(x3, y3, 1f, 0f, 0f, 0f, -u3 * x3, -u3 * y3),
            floatArrayOf(0f, 0f, 0f, x3, y3, 1f, -v3 * x3, -v3 * y3)
        )
        val b = floatArrayOf(u0, v0, u1, v1, u2, v2, u3, v3)
        val sol = solve8(A, b) ?: throw IllegalArgumentException("singular homography")
        // 单位方形 → 目标四边形：src=单位角, dst=四边形
        // 这里我们反过来用：调用方传入"单位角为 src、屏幕四边形为 dst"
        h[0] = sol[0]; h[1] = sol[1]; h[2] = sol[2]
        h[3] = sol[3]; h[4] = sol[4]; h[5] = sol[5]
        h[6] = sol[6]; h[7] = sol[7]; h[8] = 1f
    }

    /** (u,v) ∈ [0,1]² → 图像坐标 */
    fun map(u: Float, v: Float): Pair<Float, Float> {
        val w = h[6] * u + h[7] * v + h[8]
        val x = (h[0] * u + h[1] * v + h[2]) / w
        val y = (h[3] * u + h[4] * v + h[5]) / w
        return x to y
    }

    private fun solve8(a: Array<FloatArray>, b: FloatArray): FloatArray? {
        val n = 8
        val m = Array(n) { FloatArray(n + 1) }
        for (i in 0 until n) {
            System.arraycopy(a[i], 0, m[i], 0, n)
            m[i][n] = b[i]
        }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) {
                if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            }
            if (abs(m[piv][col]) < 1e-6f) return null
            if (piv != col) {
                val t = m[piv]; m[piv] = m[col]; m[col] = t
            }
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                if (f == 0f) continue
                for (k in col..n) m[r][k] -= f * m[col][k]
            }
        }
        return FloatArray(n) { m[it][n] / m[it][it] }
    }
}
