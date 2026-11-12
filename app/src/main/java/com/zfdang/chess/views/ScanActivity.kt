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

        board.onCellTap = { r, c -> onCellTapped(r, c) }
        board.onPiecePick = { ch -> onPiecePicked(ch) }

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
        updateFen()
        tvConf.text = "置信度 ${(r.avgConfidence * 100).toInt()}% (${r.elapsedMs}ms)"
        if (r.warnings.isNotEmpty()) {
            Toast.makeText(this, "注意: ${r.warnings.joinToString("；")}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- 摆棋交互状态机 ----
    // selectedCell: 已选中的棋子格（-1 无）；再点同一格=收起，点空格=移动，点有子格=替换
    private var selectedCell = -1

    private fun onCellTapped(r: Int, c: Int) {
        val idx = r * 9 + c
        val ch = grid[r][c]
        if (selectedCell >= 0) {
            val sr = selectedCell / 9; val sc = selectedCell % 9
            if (idx == selectedCell) {
                // 再点同一个棋子 → 收起（棋子从棋盘消失）
                grid[sr][sc] = '.'
            } else if (ch == '.') {
                // 点空地 → 把选中的棋子移动过去
                grid[r][c] = grid[sr][sc]
                grid[sr][sc] = '.'
            } else {
                // 点有子的格 → 收起该目标子，把选中的棋子放到这里
                grid[r][c] = grid[sr][sc]
                grid[sr][sc] = '.'
            }
            selectedCell = -1
            board.selected = -1
            board.grid = grid
            board.hidePopup()
            updateFen()
            return
        }
        if (ch != '.') {
            // 第一次点有子的格 → 选中，四角直角标记
            selectedCell = idx
            board.selected = idx
            board.hidePopup()
        } else {
            // 点空地 → 展示可用棋子悬浮窗（按剩余数量动态过滤）
            val available = board.computeAvailable()
            if (available.isEmpty()) {
                Toast.makeText(this, "没有可用棋子了", Toast.LENGTH_SHORT).show()
            } else {
                board.showPopup(idx, available)
            }
        }
    }

    private fun onPiecePicked(ch: Char) {
        val anchor = board.popupAnchor
        if (anchor < 0) return
        val r = anchor / 9; val c = anchor % 9
        grid[r][c] = ch
        board.grid = grid
        board.hidePopup()
        updateFen()
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
