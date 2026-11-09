package com.xiangqiscan.app.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.xiangqiscan.app.cv.BoardDetector
import com.xiangqiscan.app.core.Fen
import com.xiangqiscan.app.ml.CellSampler
import com.xiangqiscan.app.ml.HeuristicClassifier
import com.xiangqiscan.app.ml.TflitePieceClassifier
import java.io.File
import kotlin.concurrent.thread

/**
 * 无 GUI 调试入口（仅 debug 构建导出），供 adb 自动化测试：
 *
 *   am start -n com.xiangqiscan.app/.debug.DebugActivity \
 *     --es in /path/img.jpg --es out /path/outdir \
 *     [--es corners x1,y1,x2,y2,x3,y3,x4,y4]   # tl,tr,br,bl，跳过自动检测
 *     [--es fen "..."]                          # 仅测试 FEN 编解码
 *
 * 输出：out/<name>_overlay.jpg、out/<name>_fen.txt、out/<name>_cells.txt
 */
class DebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = intent.getStringExtra("in")
        val outDirPath = intent.getStringExtra("out")
        val corners = intent.getStringExtra("corners")
        val fen = intent.getStringExtra("fen")
        val outDir = File(outDirPath ?: getExternalFilesDir("debug")!!.absolutePath)
        outDir.mkdirs()
        thread {
            try {
                if (fen != null) {
                    val pos = Fen.parse(fen)
                    val rt = if (pos != null) Fen.encode(pos) else "PARSE_FAIL"
                    File(outDir, "roundtrip.txt").writeText("$fen\n->\n$rt\n")
                    Log.i(TAG, "fen roundtrip: $fen -> $rt")
                }
                if (input != null) {
                    runPipeline(input, outDir, corners)
                }
            } catch (e: Exception) {
                Log.e(TAG, "debug failed", e)
                try { File(outDir, "error.txt").writeText(Log.getStackTraceString(e)) } catch (_: Exception) {}
            } finally {
                Log.i(TAG, "DONE")
                finish()
            }
        }
    }

    private fun runPipeline(inputPath: String, outDir: File, corners: String?) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        var bmp = BitmapFactory.decodeFile(inputPath, opts) ?: return
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim > 1280) {
            val s = 1280f / maxDim
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        val name = File(inputPath).nameWithoutExtension

        val quad: BoardDetector.Quad = if (corners != null) {
            val v = corners.split(",").map { it.trim().toFloat() }
            fun p(i: Int) = PointF(v[i * 2], v[i * 2 + 1])
            BoardDetector.Quad(p(0), p(1), p(2), p(3))
        } else {
            BoardDetector.detect(bmp) ?: run {
                File(outDir, "${name}_detect_fail.txt").writeText("detect failed")
                Log.i(TAG, "detect failed")
                return
            }
        }

        // overlay
        val overlay = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(overlay)
        val lp = Paint().apply { color = Color.GREEN; strokeWidth = 3f }
        val gp = Paint().apply { color = Color.YELLOW; strokeWidth = 1.5f }
        val cs = quad.corners()
        for (i in 0 until 4) {
            c.drawLine(cs[i].x, cs[i].y, cs[(i + 1) % 4].x, cs[(i + 1) % 4].y, lp)
        }
        val pts = BoardDetector.gridPoints(quad)
        for (i in 0 until 90) {
            c.drawCircle(pts[i * 2], pts[i * 2 + 1], 2f, gp)
        }
        File(outDir, "${name}_overlay.jpg").outputStream().use {
            overlay.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }

        // cells
        val crops = CellSampler.sample(bmp, quad)
        val clf = TflitePieceClassifier.create(this) ?: HeuristicClassifier()
        val sb = StringBuilder()
        for (i in 0 until 90) {
            val crop = crops[i]
            val r = if (crop != null) clf.classify(crop) else com.xiangqiscan.app.ml.CellResult(empty = true)
            sb.append("${i / 9} ${i % 9} empty=${r.empty} side=${r.side} type=${r.type} conf=${"%.2f".format(r.confidence)}\n")
        }
        clf.close()
        File(outDir, "${name}_cells.txt").writeText(sb.toString())

        // fen
        val pos = com.xiangqiscan.app.core.Position()
        for (line in sb.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 5) continue
            val rr = t[0].toInt(); val cc = t[1].toInt()
            val empty = t[2].substringAfter('=').toBoolean()
            if (empty) continue
            val side = when (t[3].substringAfter('=')) {
                "RED" -> com.xiangqiscan.app.core.Side.RED
                "BLACK" -> com.xiangqiscan.app.core.Side.BLACK
                else -> continue
            }
            val typeName = t[4].substringAfter('=').substringBefore(' ')
            val type = try { com.xiangqiscan.app.core.PieceType.valueOf(typeName) } catch (e: Exception) { null }
            pos.set(rr, cc, com.xiangqiscan.app.core.Piece(side, type ?: com.xiangqiscan.app.core.PieceType.PAWN))
        }
        File(outDir, "${name}_fen.txt").writeText(Fen.encode(pos) + "\n\n" + Fen.buildPgn(pos))
        Log.i(TAG, "pipeline done: $name")
    }

    companion object { const val TAG = "XQDebug" }
}
