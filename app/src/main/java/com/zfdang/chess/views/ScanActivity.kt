package com.zfdang.chess.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.EditText
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
        const val EXTRA_JIEQI = "jieqi"
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
    /** 揭棋模式：编辑器可摆暗子，校准完成后进入揭棋对弈 */
    private var jieqiMode = false

    /** 可选补充的吃子记录："红明子字母,红暗子数;黑明子字母,黑暗子数"，空=不补充 */
    private var capturesSpec: String = ""

    /** 揭棋固定候选栏：当前选中的待摆棋子（null=无），及候选视图引用 */
    private var palettePiece: Char? = null
    private lateinit var jieqiPalette: View
    private lateinit var paletteRed: LinearLayout
    private lateinit var paletteBlack: LinearLayout
    private val paletteViews = HashMap<Char, View>()

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
            GameSetupDialog(this, redFirstInit = redFirstNow, redHumanInit = true, blackHumanInit = false) {
                redFirst, redHuman, blackHuman ->
                if (jieqiMode) startJieqiGame(redFirst, redHuman, blackHuman)
                else startGame(redFirst, redHuman, blackHuman)
            }.show()
        }
        findViewById<LinearLayout>(R.id.btnFlip).setOnClickListener { flipBoard() }
        findViewById<LinearLayout>(R.id.btnCopyFen).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FEN", fen))
            Toast.makeText(this, "FEN串已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        jieqiMode = intent.getBooleanExtra(EXTRA_JIEQI, false)
        if (jieqiMode) {
            jieqiPalette = findViewById(R.id.jieqiPalette)
            paletteRed = findViewById(R.id.paletteRed)
            paletteBlack = findViewById(R.id.paletteBlack)
            jieqiPalette.visibility = View.VISIBLE
            buildJieqiPalette()
            findViewById<View>(R.id.btnCaptures).visibility = View.VISIBLE
            findViewById<View>(R.id.btnCaptures).setOnClickListener { showCapturesEditor() }
        }

        // 引擎拒绝后回传：恢复校对棋盘/FEN（不重新识别，不丢人工修改），并高亮问题格
        val restoreGrid = intent.getStringExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_RESTORE_GRID)
        val restoreFen = intent.getStringExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_RESTORE_FEN)
        if (restoreGrid != null && restoreFen != null) {
            try {
                val rows = restoreGrid.split("/")
                if (rows.size == 10 && rows.all { it.length == 9 }) {
                    grid = Array(10) { r -> rows[r].toCharArray() }
                    fen = restoreFen
                    board.grid = grid
                    tvFen.text = fen
                    tvConf.text = "已恢复上次校对结果，请修正高亮格后重试"
                    com.zfdang.chess.jieqi.JieqiImportValidator.ensureLoaded(this)
                    val check = com.zfdang.chess.jieqi.JieqiImportValidator.validate(
                        grid, fen.contains(" w - -"))
                    board.errorCells = check.issues.map { it.cell }.filter { it >= 0 }.toSet()
                    intent.getStringExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_RESTORE_REASON)
                        ?.let { Toast.makeText(this, "引擎拒绝原因：$it", Toast.LENGTH_LONG).show() }
                    return
                }
            } catch (_: Exception) {}
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
                // 揭棋模式加载揭棋专用 17 类模型（普通模式加载原模型，互不影响）
                rec.load(jieqi = jieqiMode)
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
        // 低置信度格标记（揭棋模式下暗子/明子分别校准；普通模式沿用统一阈值）
        val uncertain = buildSet {
            for (i in 0 until 90) {
                val th = if (jieqiMode) {
                    if (grid[i / 9][i % 9] == 'X' || grid[i / 9][i % 9] == 'x') 0.55f else 0.35f
                } else 0.5f
                if (r.confidences[i] < th) add(i)
            }
        }
        board.uncertainCells = uncertain
        val hint = if (jieqiMode && uncertain.isNotEmpty())
            "，${uncertain.size} 格低置信度(黄点)请核对" else ""
        tvConf.text = "置信度 ${(r.avgConfidence * 100).toInt()}% (${r.elapsedMs}ms)$hint"
        if (r.warnings.isNotEmpty()) {
            Toast.makeText(this, "注意: ${r.warnings.joinToString("；")}", Toast.LENGTH_LONG).show()
        }
    }

    /** 可选：手动补充扫描前已被吃掉的棋子（校对页填，不影响识别；用于吃子区显示）。
     *  明子用 FEN 字母填写（如 "RNPB"），暗子填数字。两组用分号分隔，顺序：红;黑。 */
    private fun showCapturesEditor() {
        val et = EditText(this)
        et.hint = "红明子,红暗数;黑明子,黑暗数  例：RB,2;np,1"
        et.setText(capturesSpec)
        et.setSingleLine(true)
        et.textSize = 14f
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("补充吃子记录（可选）")
            .setMessage("扫描前的历史吃子无法从图片得知。可在此补充，让对弈页吃子区正确显示。
留空则不补充。")
            .setView(et)
            .setPositiveButton("保存") { _, _ -> capturesSpec = et.text.toString().trim() }
            .setNegativeButton("清空") { _, _ -> capturesSpec = "" }
            .show()
    }

    // ---- 揭棋固定候选栏（仅揭棋模式）----
    /** 每样棋子显示一个：红排 帅仕相马车炮兵+暗，黑排 将士象马车炮卒+暗。
     *  点候选=选中待摆子，再点棋盘格摆放；再点同一候选取消。 */
    private fun buildJieqiPalette() {
        val red = listOf('K', 'A', 'B', 'N', 'R', 'C', 'P', 'X')
        val black = listOf('k', 'a', 'b', 'n', 'r', 'c', 'p', 'x')
        fun addRow(container: LinearLayout, pieces: List<Char>) {
            container.removeAllViews()
            for (ch in pieces) {
                val iv = ImageView(this)
                val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
                iv.layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.adjustViewBounds = true
                iv.setImageBitmap(paletteBitmap(ch))
                iv.setBackgroundColor(0x00000000)
                iv.setOnClickListener { onPalettePick(ch) }
                container.addView(iv)
                paletteViews[ch] = iv
            }
        }
        addRow(paletteRed, red)
        addRow(paletteBlack, black)
        highlightPalette(null)
    }

    private fun paletteBitmap(ch: Char): android.graphics.Bitmap? {
        if (ch == 'X' || ch == 'x') {
            return com.zfdang.chess.jieqi.JieqiTiles.darkTile(96)
        }
        val id = when (ch) {
            'K' -> R.drawable.r_shuai; 'A' -> R.drawable.r_shi; 'B' -> R.drawable.r_xiang
            'N' -> R.drawable.r_ma; 'R' -> R.drawable.r_ju; 'C' -> R.drawable.r_pao; 'P' -> R.drawable.r_bing
            'k' -> R.drawable.b_jiang; 'a' -> R.drawable.b_shi; 'b' -> R.drawable.b_xiang
            'n' -> R.drawable.b_ma; 'r' -> R.drawable.b_ju; 'c' -> R.drawable.b_pao; 'p' -> R.drawable.b_zu
            else -> return null
        }
        return android.graphics.BitmapFactory.decodeResource(resources, id)
    }

    private fun highlightPalette(selected: Char?) {
        palettePiece = selected
        for ((ch, v) in paletteViews) {
            v.setBackgroundColor(if (ch == selected)
                android.graphics.Color.argb(90, 242, 163, 60) else android.graphics.Color.TRANSPARENT)
        }
    }

    private fun onPalettePick(ch: Char) {
        highlightPalette(if (palettePiece == ch) null else ch)
        selectedCell = -1
        board.selected = -1
        board.hidePopup()
    }

    // ---- 摆棋交互状态机 ----
    // selectedCell: 已选中的棋子格（-1 无）；再点同一格=收起，点空格=移动，点有子格=替换
    private var selectedCell = -1

    private fun onCellTapped(r: Int, c: Int) {
        val idx = r * 9 + c
        val ch = grid[r][c]
        // 揭棋固定候选栏模式：选中候选棋子后，点棋盘格直接摆/替换（可连续摆）
        if (jieqiMode && palettePiece != null) {
            val piece = palettePiece!!
            placementError(piece, r, c)?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                return
            }
            grid[r][c] = piece
            board.grid = grid
            board.uncertainCells = board.uncertainCells - idx
            updateFen()
            return
        }
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
            board.uncertainCells = board.uncertainCells - idx - (sr * 9 + sc)
            updateFen()
            return
        }
        if (ch != '.') {
            // 第一次点有子的格 → 选中，四角直角标记
            selectedCell = idx
            board.selected = idx
            board.hidePopup()
        } else {
            // 点空地：揭棋模式用下方固定候选栏（先选棋子再点格）；普通模式弹悬浮窗
            if (jieqiMode) {
                Toast.makeText(this, "请先在下方候选栏选择棋子，再点棋盘格摆放", Toast.LENGTH_SHORT).show()
            } else {
                val available = board.computeAvailable()
                if (available.isEmpty()) {
                    Toast.makeText(this, "没有可用棋子了", Toast.LENGTH_SHORT).show()
                } else {
                    board.showPopup(idx, available)
                }
            }
        }
    }

    /** 棋子可摆放位置校验：违反规则返回错误提示（null=可摆放） */
    private fun placementError(ch: Char, r: Int, c: Int): String? {
        if (ch == 'X' || ch == 'x') {
            // 揭棋引擎方言约束：未翻暗子只能待在初始暗子位（翻明后才会按角色移动），
            // 这是揭棋专有的必要限制，不是普通象棋遗留，必须保留。
            val ok = com.zfdang.chess.jieqi.JieqiRules.darkRole(r, c, ch == 'X') != '.'
            return if (ok) null else "暗子只能摆在双方初始兵/炮/车/马/士/象位"
        }
        // 揭棋：明子（含士/象/兵卒）翻明后可按角色走到棋盘任意合法格，
        // 校对摆谱必须允许放在任何格，不能套用普通象棋的九宫/不过河/底线限制。
        return null
    }

    private fun pieceName(ch: Char): String = when (ch) {
        'K' -> "帅"; 'A' -> "仕"; 'B' -> "相"; 'N' -> "马"; 'R' -> "车"; 'C' -> "炮"; 'P' -> "兵"
        'k' -> "将"; 'a' -> "士"; 'b' -> "象"; 'n' -> "马"; 'r' -> "车"; 'c' -> "炮"; 'p' -> "卒"
        'X' -> "红暗子"; 'x' -> "黑暗子"
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
        board.uncertainCells = board.uncertainCells - (r * 9 + c)
        updateFen()
    }

    private fun updateFen() {
        fen = gridToFen(grid)
        tvFen.text = fen
        // 已显示错误高亮时，每次改动棋盘都重新校验并刷新高亮，让用户即时看到修正生效
        if (jieqiMode && board.errorCells.isNotEmpty()) {
            com.zfdang.chess.jieqi.JieqiImportValidator.ensureLoaded(this)
            val check = com.zfdang.chess.jieqi.JieqiImportValidator.validate(
                grid, fen.contains(" w - -"))
            board.errorCells = check.issues.map { it.cell }.filter { it >= 0 }.toSet()
        }
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

    /** 揭棋模式开局：结构化校验 → 引擎方言 FEN → JieqiActivity（失败则留在校对页并能定位修正） */
    private fun startJieqiGame(redFirst: Boolean, redHuman: Boolean, blackHuman: Boolean) {
        com.zfdang.chess.jieqi.JieqiImportValidator.ensureLoaded(this)
        // 校对阶段按位置确定暗子所属方（背面无红黑，X/x 只是引擎编码，由所在初始位唯一决定）。
        // 这不是识别阶段改类别：暗子状态由模型判定，红黑归属纯由揭棋规则的位置推出。
        val normalized = com.zfdang.chess.jieqi.JieqiImportValidator.normalizeDarkSides(grid)
        val result = com.zfdang.chess.jieqi.JieqiImportValidator.validate(grid, redFirst)
        if (normalized.isNotEmpty()) {
            board.grid = grid
            updateFen()
        }
        if (!result.ok || result.fen == null) {
            // 高亮所有有问题的格子，显示逐条具体原因；不跳转、保留当前校对状态
            board.errorCells = result.issues.map { it.cell }.filter { it >= 0 }.toSet()
            val detail = result.issues.joinToString("\n") { "• ${it.message}" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("揭棋局面需要修正（${result.issues.size} 项）")
                .setMessage(detail + "\n\n红色高亮的格子请点选后从下方候选栏改为明子、空位或合法暗子。")
                .setPositiveButton("返回校对", null)
                .show()
            return
        }
        board.errorCells = emptySet()
        if (normalized.isNotEmpty()) {
            Toast.makeText(this,
                "已按所在初始位确定 ${normalized.size} 个暗子的归属（暗子背面无红黑，位置即所属方）",
                Toast.LENGTH_LONG).show()
        }
        val importFen = result.fen
        val i = Intent(this, com.zfdang.chess.jieqi.JieqiActivity::class.java).apply {
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_IMPORT_FEN, importFen)
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_RED_HUMAN, redHuman)
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_BLACK_HUMAN, blackHuman)
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_RED_FIRST, redFirst)
            // 回传原始校对结果：若引擎侧仍拒绝，JieqiActivity 要能带回并保留
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_SCAN_GRID,
                grid.joinToString("/") { it.joinToString("") })
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_SCAN_FEN, fen)
            putExtra(com.zfdang.chess.jieqi.JieqiActivity.EXTRA_CAPTURES, capturesSpec)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
        finish()
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
