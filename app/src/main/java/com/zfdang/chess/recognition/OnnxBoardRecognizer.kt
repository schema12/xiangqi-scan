package com.zfdang.chess.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX 棋盘识别（移植自 Shirakawa-Kotone/chinese-chess-helper 的 OnnxRecognizer，
 * 模型来源 TheOne1006/chinese-chess-recognition / HF yolo12138）。
 *
 * 管线：RTMPose 4 角点（256×256 仿射, SimCC /512）→ 透视矫正 450×500(边距50)
 *   → 中心裁 400×450 → 缩 280×315 → ImageNet 归一化 → Swin 分类 [1,90,16]。
 *
 * 稳健化（相对 helper 原版）：
 *  - 无 OpenCV：android.graphics.Matrix 完成仿射/透视，像素循环归一化
 *  - 双 pose 缩放假设（按宽 / 按长边）× 旋转重试，4 候选按全 90 格
 *    top1-top2 margin 均值择优（pose 本身方向感知，无任何重映射）
 *  - 可疑识别（margin<0.6）触发底边角点平移扫描精修（棋盘下方按钮等强特征
 *    会把底边角点拖偏 ~100px）
 *  - 仕/相行范围侧别约束修正；全 90 格平均 margin 作为置信度
 *  - EXIF 解码见 [ImageLoader]
 *
 * 输出 FEN 与本项目 Board.restoreFromFEN 完全兼容（大写=红、行序自上而下）。
 */
class OnnxBoardRecognizer(private val context: Context) {

    companion object {
        const val POSE_MODEL = "models/4_v6-0301.onnx"
        const val LAYOUT_MODEL = "models/nano_v3-0319.onnx"

        private const val POSE_INPUT_SIZE = 256
        private const val POSE_PADDING = 1.25f

        private const val CLASSIFIER_CROP_W = 400
        private const val CLASSIFIER_CROP_H = 450
        private const val CLASSIFIER_INPUT_W = 280
        private const val CLASSIFIER_INPUT_H = 315

        private const val WARP_W = 450
        private const val WARP_H = 500
        private const val WARP_PADDING = 50

        private val MEAN_R = 123.675f; private val MEAN_G = 116.28f; private val MEAN_B = 103.53f
        private val STD_R = 58.395f;  private val STD_G = 57.12f;   private val STD_B = 57.375f

        /** 16 类，顺序与训练侧一致 */
        private val CLASS_NAMES = charArrayOf(
            '.', 'x',
            'K', 'A', 'B', 'N', 'R', 'C', 'P',
            'k', 'a', 'b', 'n', 'r', 'c', 'p'
        )
    }

    data class Result(
        val grid: Array<CharArray>,   // 10×9 FEN 字符（'.'=空）
        val fen: String,              // 含 " - - 0 1" 尾
        val avgConfidence: Float,     // 全 90 格平均 margin
        val confidences: FloatArray,  // 90 格 margin
        val warnings: List<String>,
        val corners: FloatArray,      // 源图 A0,A8,J0,J8 (x,y)×4
        val warped: Bitmap?,          // 450×500 俯视图
        val elapsedMs: Long
    )

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var poseSession: OrtSession? = null
    private var layoutSession: OrtSession? = null
    private var poseInputName = "input"
    private var poseOutputNames: List<String> = emptyList()
    private var layoutInputName = "input"
    private var layoutOutputName = "output"
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    /** 最近一次 recognize 的全部候选诊断串 */
    var lastCandidatesDump: String = ""
        private set

    fun load() {
        if (loaded) return
        val t0 = System.currentTimeMillis()
        context.assets.open(POSE_MODEL).use { s ->
            poseSession = ortEnv.createSession(s.readBytes(), OrtSession.SessionOptions())
            poseInputName = poseSession!!.inputNames.iterator().next()
            poseOutputNames = poseSession!!.outputNames.toList()
        }
        context.assets.open(LAYOUT_MODEL).use { s ->
            layoutSession = ortEnv.createSession(s.readBytes(), OrtSession.SessionOptions())
            layoutInputName = layoutSession!!.inputNames.iterator().next()
            layoutOutputName = layoutSession!!.outputNames.first()
        }
        loaded = true
        android.util.Log.i("BoardRec", "models loaded in ${System.currentTimeMillis() - t0}ms")
    }

    fun release() {
        try { poseSession?.close() } catch (_: Exception) {}
        try { layoutSession?.close() } catch (_: Exception) {}
        poseSession = null; layoutSession = null; loaded = false
    }

    /** 识别入口：双缩放 × 旋转 4 候选，按平均 margin 择优（pose 方向感知，无重映射） */
    fun recognize(src0: Bitmap): Result? {
        if (!loaded) throw IllegalStateException("Call load() first")
        val t0 = System.currentTimeMillis()
        data class Cand(val res: Result, val src: Bitmap)
        val cands = mutableListOf<Cand>()
        val rotSrc = rot180(src0)
        for ((src, _) in listOf(src0 to false, rotSrc to true)) {
            recognizeOnce(src, squareNorm = false)?.let { cands.add(Cand(it, src)) }
            recognizeOnce(src, squareNorm = true)?.let { cands.add(Cand(it, src)) }
        }
        if (cands.isEmpty()) return null
        // 候选诊断串（调试通道写入结果文件）
        run {
            val sb = StringBuilder()
            for ((i, cd) in cands.withIndex()) {
                sb.append("cand#").append(i)
                    .append(" margin=").append("%.3f".format(cd.res.avgConfidence)).append('\n')
                for (r in 0 until 10) sb.append("  ").append(cd.res.grid[r].joinToString("")).append('\n')
            }
            lastCandidatesDump = sb.toString()
        }
        val best = cands.maxBy { it.res.avgConfidence }
        var res = best.res
        // 角点精修：识别可疑时（棋盘正下方按钮等强特征会把底边角点拖偏），
        // 对 J0/J8 的 y 平移扫描重分类，取 margin 最优
        if (res.avgConfidence < 0.6f) {
            var dy = -160
            while (dy <= 160) {
                if (dy != 0) {
                    val c = res.corners.copyOf().also { it[5] += dy; it[7] += dy }
                    finishRecognition(best.src, c)?.let { r ->
                        if (r.avgConfidence > res.avgConfidence) res = r
                    }
                }
                dy += 40
            }
        }
        return res.copy(elapsedMs = System.currentTimeMillis() - t0)
    }

    private fun rot180(b: Bitmap): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height,
            Matrix().apply { postRotate(180f) }, true)

    // ------------------------------------------------------------------
    // 单次识别
    // ------------------------------------------------------------------

    private fun recognizeOnce(src0: Bitmap, squareNorm: Boolean): Result? {
        return try {
            val src = if (src0.config == Bitmap.Config.ARGB_8888) src0
                      else src0.copy(Bitmap.Config.ARGB_8888, false)
            val corners = detectCorners(src, squareNorm) ?: return null
            finishRecognition(src, corners)
        } catch (e: Exception) {
            android.util.Log.e("BoardRec", "recognize failed", e)
            null
        }
    }

    /** 角点已知时的后半段：透视矫正 → 分类 → 组装（角点精修复用） */
    private fun finishRecognition(src: Bitmap, corners: FloatArray): Result? {
        return try {
            val warped = warpPerspective(src, corners, WARP_W, WARP_H, WARP_DST)

            val cropX = (WARP_W - CLASSIFIER_CROP_W) / 2
            val cropY = (WARP_H - CLASSIFIER_CROP_H) / 2
            val cropped = Bitmap.createBitmap(warped, cropX, cropY,
                min(CLASSIFIER_CROP_W, warped.width), min(CLASSIFIER_CROP_H, warped.height))
            val resized = Bitmap.createScaledBitmap(cropped, CLASSIFIER_INPUT_W, CLASSIFIER_INPUT_H, true)

            val (labels, probs) = classify(resized)
            if (warped !== src) warped.recycle()
            if (resized !== cropped) resized.recycle()
            cropped.recycle()

            val grid = Array(10) { CharArray(9) { '.' } }
            val confOut = FloatArray(90)
            val counts = HashMap<Char, Int>()
            for (r in 0 until 10) for (c in 0 until 9) {
                val i = r * 9 + c
                val ch = CLASS_NAMES[labels[i]]
                confOut[i] = probs[i]
                if (ch == '.' || ch == 'x') continue
                var side = if (ch.isUpperCase()) 'R' else 'B'
                // 仕/相（士/象）不过河：侧别按行范围约束修正
                val lower = ch.lowercaseChar()
                if (lower == 'a' || lower == 'b') {
                    side = if (r >= 5) 'R' else 'B'
                }
                grid[r][c] = if (side == 'R') ch.uppercaseChar() else ch.lowercaseChar()
                counts[grid[r][c]] = (counts[grid[r][c]] ?: 0) + 1
            }
            val warnings = mutableListOf<String>()
            val kings = (counts['K'] ?: 0) + (counts['k'] ?: 0)
            if (kings != 2) warnings.add("将/帅数量异常($kings)")
            for ((ch, max) in mapOf('K' to 1, 'k' to 1, 'A' to 2, 'a' to 2, 'B' to 2, 'b' to 2,
                                    'N' to 2, 'n' to 2, 'R' to 2, 'r' to 2, 'C' to 2, 'c' to 2,
                                    'P' to 5, 'p' to 5)) {
                val n = counts[ch] ?: 0
                if (n > max) warnings.add("${pieceCn(ch)}数量超限($n>$max)")
            }

            Result(grid, gridToFen(grid), probs.sum() / 90f, confOut, warnings,
                corners, warpedBitmapForDisplay(src, corners), 0L)
        } catch (e: Exception) {
            android.util.Log.e("BoardRec", "finish failed", e)
            null
        }
    }

    private fun pieceCn(ch: Char): String = when (ch) {
        'K' -> "红帅"; 'A' -> "红士"; 'B' -> "红相"; 'N' -> "红马"; 'R' -> "红车"
        'C' -> "红炮"; 'P' -> "红兵"; 'k' -> "黑将"; 'a' -> "黑士"; 'b' -> "黑象"
        'n' -> "黑马"; 'r' -> "黑车"; 'c' -> "黑炮"; 'p' -> "黑卒"; else -> ch.toString()
    }

    /** 标准 FEN（大写=红、行序自上而下，与 Board.restoreFromFEN 兼容） */
    private fun gridToFen(grid: Array<CharArray>): String {
        val parts = mutableListOf<String>()
        for (row in grid) {
            val sb = StringBuilder()
            var e = 0
            for (ch in row) {
                if (ch == '.') e++ else {
                    if (e > 0) { sb.append(e); e = 0 }
                    sb.append(ch)
                }
            }
            if (e > 0) sb.append(e)
            parts.add(sb.toString())
        }
        return parts.joinToString("/") + " w - - 0 1"
    }

    // ------------------------------------------------------------------
    // Step 1: RTMPose 角点（256×256 仿射 + SimCC argmax）
    // ------------------------------------------------------------------

    private fun detectCorners(src: Bitmap, squareNorm: Boolean): FloatArray? {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        // 两种缩放假设：
        //  squareNorm=false：按宽（helper Android 移植版原版）
        //  squareNorm=true ：按长边（helper PC 参考 get_warp_size_with_input_size；
        //                    整屏棋盘贴边/偏下的竖版截图靠它才能不裁上下）
        val s = if (squareNorm) POSE_INPUT_SIZE / (maxOf(w, h) * POSE_PADDING)
                else POSE_INPUT_SIZE / (w * POSE_PADDING)

        val poseBmp = Bitmap.createBitmap(POSE_INPUT_SIZE, POSE_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate(POSE_INPUT_SIZE / 2f - cx * s, POSE_INPUT_SIZE / 2f - cy * s)
        }
        Canvas(poseBmp).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val input = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(toNchw(poseBmp)),
            longArrayOf(1, 3, POSE_INPUT_SIZE.toLong(), POSE_INPUT_SIZE.toLong()))
        val out = poseSession!!.run(mapOf(poseInputName to input))
        input.close()
        try {
            val simccX = readOut(out, poseOutputNames[0])   // [4,512]
            val simccY = readOut(out, poseOutputNames[1])   // [4,512]
            val n = simccX.size
            val pts = FloatArray(n * 2)
            for (i in 0 until n) {
                var mx = 0f; var mxi = 0; var my = 0f; var myi = 0
                val rx = simccX[i]; val ry = simccY[i]
                for (j in rx.indices) { if (rx[j] > mx) { mx = rx[j]; mxi = j } }
                for (j in ry.indices) { if (ry[j] > my) { my = ry[j]; myi = j } }
                pts[i * 2] = mxi / 2f      // SimCC bin → 256 空间坐标
                pts[i * 2 + 1] = myi / 2f
            }
            val invM = Matrix().apply {
                setScale(s, s)
                postTranslate(POSE_INPUT_SIZE / 2f - cx * s, POSE_INPUT_SIZE / 2f - cy * s)
                invert()
            }
            val back = FloatArray(n * 2)
            invM.mapPoints(back, pts)
            return back   // [A0.x,A0.y, A8.x,A8.y, J0.x,J0.y, J8.x,J8.y]
        } finally {
            out.close()
        }
    }

    private fun readOut(result: OrtSession.Result, name: String): Array<FloatArray> {
        val t = result.get(name).get() as OnnxTensor
        val shape = t.info.shape
        val d1 = shape[1].toInt(); val d2 = shape[2].toInt()
        val buf = t.floatBuffer
        val data = FloatArray(buf.remaining()); buf.get(data)
        val out = Array(d1) { FloatArray(d2) }
        var k = 0
        for (i in 0 until d1) for (j in 0 until d2) out[i][j] = data[k++]
        return out
    }

    // ------------------------------------------------------------------
    // Step 3: 90 格分类
    // ------------------------------------------------------------------

    private fun classify(input: Bitmap): Pair<IntArray, FloatArray> {
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(toNchw(input)),
            longArrayOf(1, 3, CLASSIFIER_INPUT_H.toLong(), CLASSIFIER_INPUT_W.toLong()))
        val out = layoutSession!!.run(mapOf(layoutInputName to tensor))
        tensor.close()
        try {
            val t = out.get(layoutOutputName).get() as OnnxTensor
            val buf = t.floatBuffer
            val data = FloatArray(buf.remaining()); buf.get(data)
            val labels = IntArray(90)
            val probs = FloatArray(90)
            for (i in 0 until 90) {
                var mx = -1e20f; var mxi = 0; var second = -1e20f
                val off = i * 16
                for (j in 0 until 16) {
                    val v = data[off + j]
                    if (v > mx) { second = mx; mx = v; mxi = j }
                    else if (v > second) second = v
                }
                labels[i] = mxi
                // 置信度量：top1-top2 margin（该模型 logits 未归一化，
                // 实测正常格 margin≈0.8、可疑格<0.3，阈值 0.5 分隔干净）
                probs[i] = mx - second
            }
            return labels to probs
        } finally {
            out.close()
        }
    }

    // ------------------------------------------------------------------
    // 像素/几何工具（替代 OpenCV）
    // ------------------------------------------------------------------

    /** ARGB bitmap → ImageNet 归一化 NCHW FloatArray（RGB 顺序） */
    private fun toNchw(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pix[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            out[i] = (r - MEAN_R) / STD_R
            out[plane + i] = (g - MEAN_G) / STD_G
            out[2 * plane + i] = (b - MEAN_B) / STD_B
        }
        return out
    }

    /** 4 点透视映射 */
    private fun warpPerspective(src: Bitmap, srcCorners: FloatArray,
                                outW: Int, outH: Int, dstCorners: FloatArray): Bitmap {
        val m = Matrix()
        if (!m.setPolyToPoly(srcCorners, 0, dstCorners, 0, 4)) {
            throw IllegalStateException("setPolyToPoly failed")
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
