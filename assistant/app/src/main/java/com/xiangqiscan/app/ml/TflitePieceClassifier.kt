package com.xiangqiscan.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.xiangqiscan.app.core.PieceType
import com.xiangqiscan.app.core.Side
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite 棋子分类器。
 * 模型：assets/model/pieces_int8.tflite，输入 [1,48,48,3] uint8（RGB），
 * 输出 [1,8] float32：0=空，1..7=KING..PAWN。颜色由像素统计独立判定。
 */
class TflitePieceClassifier private constructor(private val interpreter: Interpreter) :
    PieceClassifier {

    companion object {
        const val MODEL_PATH = "model/pieces_int8.tflite"
        const val INPUT = 48

        fun create(ctx: Context): TflitePieceClassifier? {
            return try {
                ctx.assets.openFd(MODEL_PATH).use { fd ->
                    val ch = fd.createInputStream().channel
                    val model = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    val opt = Interpreter.Options().apply { numThreads = 2 }
                    TflitePieceClassifier(Interpreter(model, opt))
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private val buf = ByteBuffer.allocateDirect(INPUT * INPUT * 3)

    override fun classify(crop: Bitmap): CellResult {
        val side = colorOf(crop)
        buf.rewind()
        val px = IntArray(INPUT * INPUT)
        crop.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        for (p in px) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
        }
        buf.rewind()
        val out = Array(1) { FloatArray(8) }
        interpreter.run(buf, out)
        val probs = out[0]
        var best = 0
        for (i in 1 until 8) if (probs[i] > probs[best]) best = i
        if (best == 0) return CellResult(empty = true, confidence = probs[0])
        return CellResult(
            empty = false, side = side,
            type = PieceType.entries[best - 1],
            confidence = probs[best]
        )
    }

    /** 依据墨色像素的色相判断红/黑（红字饱和度高、暗字亮度低） */
    private fun colorOf(b: Bitmap): Side {
        val w = b.width; val h = b.height
        var red = 0
        var dark = 0
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        for (p in px) {
            android.graphics.Color.colorToHSV(p, hsv)
            val s = hsv[1] * 255f
            val v = hsv[2] * 255f
            if (v < 90 && s < 120) dark++                       // 深黑墨
            else if (hsv[0] in 0f..25f && s > 90 && v > 60) red++ // 红墨
        }
        return if (red * 2 > dark) Side.RED else Side.BLACK
    }

    override fun close() = interpreter.close()
}
