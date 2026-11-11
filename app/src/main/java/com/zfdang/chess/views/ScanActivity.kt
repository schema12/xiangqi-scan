package com.zfdang.chess.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zfdang.chess.GameActivity
import com.zfdang.chess.R
import com.zfdang.chess.recognition.ImageLoader
import com.zfdang.chess.recognition.OnnxBoardRecognizer
import kotlin.concurrent.thread

/**
 * 识别摆谱：拍照/相册图片 → ONNX 识别 → 校对 → 交给 GameActivity 从该局面继续对弈。
 *
 * 识别核心移植自 Shirakawa-Kotone/chinese-chess-helper（OnnxRecognizer），
 * 并补充 helper 缺失的相册/截图导入入口。
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        private const val LOW_CONF_TH = 0.5f
        private const val MAX_DIM = 4096   // 实测全分辨率角点最稳，勿随意缩小
    }

    private lateinit var board: BoardEditView
    private lateinit var tvFen: TextView
    private lateinit var tvConf: TextView
    private lateinit var overlay: View
    private lateinit var tvLoading: TextView

    private var grid: Array<CharArray> = Array(10) { CharArray(9) { '.' } }
    private var fen: String = ""
    private var sourceBmp: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        board = findViewById(R.id.boardEdit)
        tvFen = findViewById(R.id.tvFen)
        tvConf = findViewById(R.id.tvConf)
        overlay = findViewById(R.id.loadingOverlay)
        tvLoading = findViewById(R.id.tvLoading)

        board.onCellTap = { r, c -> showPieceSelector(r, c) }

        findViewById<Button>(R.id.btnStartGame).setOnClickListener { startGame() }
        findViewById<Button>(R.id.btnCopyFen).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FEN", fen))
            Toast.makeText(this, "FEN串已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        val bmp = decodeInput()
        if (bmp == null) {
            Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        sourceBmp = bmp
        startRecognition(bmp)
    }

    private fun decodeInput(): Bitmap? {
        val path = intent.getStringExtra(EXTRA_PATH)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        return ImageLoader.decode(path,
            uriStr?.let { Uri.parse(it) }, MAX_DIM, contentResolver)
    }

    private fun startRecognition(bmp: Bitmap) {
        overlay.visibility = View.VISIBLE
        tvLoading.text = getString(R.string.rec_loading)
        thread {
            var rec: OnnxBoardRecognizer? = null
            try {
                rec = OnnxBoardRecognizer(this)
                rec.load()
                val r = rec.recognize(bmp)
                runOnUiThread {
                    overlay.visibility = View.GONE
                    if (r == null) {
                        Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show()
                    } else apply(r)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    overlay.visibility = View.GONE
                    Toast.makeText(this, "识别出错: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { rec?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun apply(r: OnnxBoardRecognizer.Result) {
        grid = r.grid
        fen = r.fen
        board.grid = grid
        board.lowConf = r.confidences.mapIndexed { i, f -> if (f < LOW_CONF_TH) i else -1 }
            .filter { it >= 0 }.toSet()
        updateFen()
        tvConf.text = buildString {
            append("置信度 ${(r.avgConfidence * 100).toInt()}% (${r.elapsedMs}ms)")
            if (board.lowConf.isNotEmpty()) append(" · 低置信红框")
        }
        if (r.warnings.isNotEmpty()) {
            Toast.makeText(this, "注意: ${r.warnings.joinToString("；")}", Toast.LENGTH_LONG).show()
        }
    }

    /** 点击交叉点 → 换/加/删子 */
    private fun showPieceSelector(r: Int, c: Int) {
        val items = listOf("空", "帅", "仕", "相", "马", "车", "炮", "兵",
                           "将", "士", "象", "马", "车", "炮", "卒")
        val chars = listOf('.', 'K', 'A', 'B', 'N', 'R', 'C', 'P',
                           'k', 'a', 'b', 'n', 'r', 'c', 'p')
        AlertDialog.Builder(this)
            .setTitle("($r,$c) 放什么棋子")
            .setItems(items.toTypedArray()) { _, w ->
                grid[r][c] = chars[w]
                board.grid = grid
                board.lowConf = board.lowConf - (r * 9 + c)
                updateFen()
            }
            .show()
    }

    private fun updateFen() {
        fen = gridToFen(grid)
        tvFen.text = fen
    }

    private fun gridToFen(g: Array<CharArray>): String {
        val parts = mutableListOf<String>()
        for (row in g) {
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

    private fun startGame() {
        // 双将校验
        val redK = grid.sumOf { row -> row.count { it == 'K' } }
        val blackK = grid.sumOf { row -> row.count { it == 'k' } }
        if (redK != 1 || blackK != 1) {
            Toast.makeText(this, "棋盘必须包含双方将/帅", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, GameActivity::class.java).apply {
            putExtra("FENString", gridToFen(grid))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
        finish()
    }
}
