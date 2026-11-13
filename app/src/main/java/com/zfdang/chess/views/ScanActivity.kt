package com.zfdang.chess.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zfdang.chess.GameActivity
import com.zfdang.chess.R
import com.zfdang.chess.recognition.ImageLoader
import com.zfdang.chess.recognition.OnnxBoardRecognizer
import com.zfdang.chess.views.GameSetupDialog
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

        findViewById<LinearLayout>(R.id.btnStartGame).setOnClickListener {
            // 校准完成：先弹棋局设置（先手方默认=识别FEN的轮走方），确认后带设置开局
            val redFirstNow = fen.endsWith(" w - - 0 1") || fen.contains(" w - - 0 1")
            GameSetupDialog(this, redFirstInit = redFirstNow, redHumanInit = true, blackHumanInit = true) {
                redFirst, redHuman, blackHuman -> startGame(redFirst, redHuman, blackHuman)
            }.show()
        }
        findViewById<LinearLayout>(R.id.btnFlip).setOnClickListener { flipBoard() }
        findViewById<LinearLayout>(R.id.btnCopyFen).setOnClickListener {
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
            val moving = grid[sr][sc]
            if (idx == selectedCell) {
                // 再点同一个棋子 → 收起（棋子从棋盘消失）
                grid[sr][sc] = '.'
            } else {
                // 点空地/有子格 → 校验目标位置，然后把选中棋子放过去
                placementError(moving, r, c)?.let {
                    Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                    return
                }
                grid[r][c] = moving
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

    /** 棋子可摆放位置校验：违反规则返回错误提示（null=可摆放） */
    private fun placementError(ch: Char, r: Int, c: Int): String? {
        val name = pieceName(ch)
        val red = ch.isUpperCase()
        val lower = ch.lowercaseChar()
        return when (lower) {
            // 将/帅、士/仕：只能在己方九宫（列 3..5；红行 7..9，黑行 0..2）
            'k', 'a' -> {
                val okC = c in 3..5
                val okR = if (red) r >= 7 else r <= 2
                if (!okC || !okR) "${name}不能摆在这里" else null
            }
            // 相/象：不能过河（红行>=5，黑行<=4）
            'b' -> {
                val ok = if (red) r >= 5 else r <= 4
                if (!ok) "${name}不能摆在这里" else null
            }
            // 兵/卒：不能摆在己方底线之前（红不能在行 0..2 即对方九宫底线? 放宽：不能退到底线之后）
            // 按标准：兵卒未过河不能后退，但摆棋允许自由摆；仅禁止出现在对方底线行
            'p' -> {
                val ok = if (red) r >= 1 else r <= 8
                if (!ok) "${name}不能摆在这里" else null
            }
            else -> null
        }
    }

    private fun pieceName(ch: Char): String = when (ch) {
        'K' -> "帅"; 'A' -> "仕"; 'B' -> "相"; 'N' -> "马"; 'R' -> "车"; 'C' -> "炮"; 'P' -> "兵"
        'k' -> "将"; 'a' -> "士"; 'b' -> "象"; 'n' -> "马"; 'r' -> "车"; 'c' -> "炮"; 'p' -> "卒"
        else -> "棋子"
    }

    private fun onPiecePicked(ch: Char) {
        val anchor = board.popupAnchor
        if (anchor < 0) return
        val r = anchor / 9; val c = anchor % 9
        placementError(ch, r, c)?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            return
        }
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

    /** 翻转棋盘：仅视图翻转（grid 数据与 FEN 不变），便于校对上下颠倒的识别图 */
    private fun flipBoard() {
        board.viewFlipped = !board.viewFlipped
        board.selected = -1
        selectedCell = -1
        board.hidePopup()
        updateFen()
    }

    private fun startGame(redFirst: Boolean, redHuman: Boolean, blackHuman: Boolean) {
        // 双将校验
        val redK = grid.sumOf { row -> row.count { it == 'K' } }
        val blackK = grid.sumOf { row -> row.count { it == 'k' } }
        if (redK != 1 || blackK != 1) {
            Toast.makeText(this, "棋盘必须包含双方将/帅", Toast.LENGTH_LONG).show()
            return
        }
        // FEN 轮走字段按先手方设置覆写（gridToFen 固定输出 w）
        val f = if (redFirst) gridToFen(grid) else gridToFen(grid).replaceFirst(" w - - 0 1", " b - - 0 1")
        val i = Intent(this, GameActivity::class.java).apply {
            putExtra("FENString", f)
            putExtra(GameActivity.EXTRA_RED_HUMAN, redHuman)
            putExtra(GameActivity.EXTRA_BLACK_HUMAN, blackHuman)
            putExtra(GameActivity.EXTRA_RED_FIRST, redFirst)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
        finish()
    }
}
