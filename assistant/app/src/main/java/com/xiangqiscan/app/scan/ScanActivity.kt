package com.xiangqiscan.app.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.xiangqiscan.app.core.Position
import com.xiangqiscan.app.databinding.ActivityScanBinding
import com.xiangqiscan.app.cv.BoardDetector
import com.xiangqiscan.app.io.Prefs
import com.xiangqiscan.app.ml.CellSampler
import com.xiangqiscan.app.ml.HeuristicClassifier
import com.xiangqiscan.app.ml.PieceClassifier
import com.xiangqiscan.app.ml.TflitePieceClassifier
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 扫描页：加载图片 → 自动找四角（可拖动微调）→ 下一步：采样 90 点 → 分类 → 编辑器。
 */
class ScanActivity : AppCompatActivity() {

    private val bg = Executors.newSingleThreadExecutor()
    private var work: Bitmap? = null
    private var classifier: PieceClassifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityScanBinding.inflate(layoutInflater)
        setContentView(b.root)

        val uri = intent.data
        if (uri == null) { finish(); return }

        bg.execute {
            val bmp = loadBitmap(uri) ?: run {
                runOnUiThread { Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show(); finish() }
                return@execute
            }
            work = bmp
            runOnUiThread {
                b.overlay.bitmap = bmp
                b.overlay.quadNorm = defaultQuad()
                b.overlay.invalidate()
                autoDetect(b)
            }
        }

        b.btnRecheck.setOnClickListener { autoDetect(b) }
        b.btnNext.setOnClickListener { onNext(b) }
    }

    /** 默认四角：居中 76% 区域（自动检测失败时的起点） */
    private fun defaultQuad(): BoardDetector.Quad {
        val l = 0.12f; val t = 0.12f; val r = 0.88f; val bo = 0.88f
        return BoardDetector.Quad(PointF(l, t), PointF(r, t), PointF(r, bo), PointF(l, bo))
    }

    private fun autoDetect(b: ActivityScanBinding) {
        val bmp = work ?: return
        b.btnRecheck.isEnabled = false
        bg.execute {
            val q = try { BoardDetector.detect(bmp) } catch (e: Exception) { null }
            runOnUiThread {
                b.btnRecheck.isEnabled = true
                if (q != null) {
                    b.overlay.setQuadPixels(q)
                    Toast.makeText(this, "已自动识别棋盘边框，可拖动微调", Toast.LENGTH_SHORT).show()
                } else {
                    if (b.overlay.quadNorm == null) b.overlay.quadNorm = defaultQuad()
                    Toast.makeText(this, "自动识别失败，请手动拖动四个角点", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onNext(b: ActivityScanBinding) {
        val bmp = work ?: return
        val quad = b.overlay.quadInBitmap() ?: run {
            Toast.makeText(this, "请先确定棋盘四角", Toast.LENGTH_SHORT).show()
            return
        }
        b.btnNext.isEnabled = false
        Toast.makeText(this, "正在识别棋子…", Toast.LENGTH_SHORT).show()
        bg.execute {
            try {
                if (classifier == null) {
                    classifier = TflitePieceClassifier.create(this) ?: HeuristicClassifier()
                }
                val crops = CellSampler.sample(bmp, quad)
                val cells = arrayOfNulls<CellResult>(90)
                for (i in 0 until 90) {
                    val crop = crops[i]
                    cells[i] = if (crop != null) classifier!!.classify(crop) else CellResult(empty = true)
                }
                val pos = positionFrom(cells)
                ScanBus.clear()
                ScanBus.position = pos
                ScanBus.cells = cells
                ScanBus.crops = crops
                runOnUiThread {
                    b.btnNext.isEnabled = true
                    startActivity(android.content.Intent(this, com.xiangqiscan.app.editor.EditorActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.btnNext.isEnabled = true
                    Toast.makeText(this, "识别失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 识别结果 → 局面。图像顶行 = row0（黑方底线假设）；方向可在编辑器翻转 */
    private fun positionFrom(cells: Array<CellResult?>): Position {
        val pos = Position()
        for (i in 0 until 90) {
            val c = cells[i] ?: continue
            if (!c.empty && c.side != null) {
                pos.set(i / 9, i % 9, com.xiangqiscan.app.core.Piece(c.side, c.type ?: com.xiangqiscan.app.core.PieceType.PAWN))
            }
        }
        return pos
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sample = 1
            while (maxDim / sample > 1280) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            // EXIF 旋转（拍照文件）
            val rot = try {
                contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) { 0f }
            if (rot != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(rot) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } else bmp
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier?.close()
        bg.shutdown()
    }
}
