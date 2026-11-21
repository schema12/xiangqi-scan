package com.zfdang.chess.jieqi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zfdang.chess.R
import com.zfdang.chess.controllers.GcLog
import com.zfdang.chess.jieqi.engine.JieqiEngineClient
import com.zfdang.chess.views.GameSetupDialog
import java.io.File

/**
 * 揭棋对弈页。
 *
 * 视角映射（扫描模式）：软件 AI = 原棋局中的我。
 *  - AI 移动暗子 / 用户移动暗子：录入揭晓身份（扫描模式）；新开对局由 App 秘密分配自动揭晓
 *  - AI 吃掉用户暗子：录入被吃身份（扫描模式）
 *  - 用户吃掉 AI 的暗子：身份未知，token 省略后缀（引擎原生概率处理）
 */
class JieqiActivity : AppCompatActivity(), JieqiEngineClient.Listener {

    companion object {
        const val EXTRA_IMPORT_FEN = "jqImportFen"       // 揭棋方言 FEN（扫描/导入回传）
        const val EXTRA_RED_HUMAN = "redHuman"
        const val EXTRA_BLACK_HUMAN = "blackHuman"
        const val EXTRA_RED_FIRST = "redFirst"
        private const val SAVE_FILE = "jieqi_save.txt"
        private const val AI_MOVE_TIME_MS = 2000L
        private const val HINT_MOVE_TIME_MS = 1200L
    }

    private lateinit var board: JieqiBoardView
    private lateinit var statusText: TextView
    private lateinit var opponentLabel: TextView
    private lateinit var capturedTop: com.zfdang.chess.jieqi.JieqiCapturedView
    private lateinit var capturedBottom: com.zfdang.chess.jieqi.JieqiCapturedView

    private lateinit var state: JieqiGameState
    private var redIsHuman = true
    private var blackIsHuman = false
    private val undoStack = ArrayDeque<JieqiGameState>()
    private val main = Handler(Looper.getMainLooper())

    private var client: JieqiEngineClient? = null
    private var engineReady = false
    private var thinking = false
    /** 待应用的一手（等待身份录入） */
    private data class PendingMove(val fr: Int, val fc: Int, val tr: Int, val tc: Int,
                                   val needReveal: Boolean, val needVictim: Boolean,
                                   var reveal: Char = '.', var victim: Char = '.')
    private var pendingMove: PendingMove? = null
    private var hintPending = false

    /** 终局提示：绝杀/将死。由引擎 mate 分数或 App 无合法走法判定。 */
    private var gameOver = false
    private var gameOverReason = ""

    /** 扫描导入：CaptureActivity 拍照/相册 → ScanActivity（揭棋模式） */
    private val captureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val path = r.data?.getStringExtra(com.zfdang.chess.views.ScanActivity.EXTRA_PATH)
        if (path != null) {
            val i = android.content.Intent(this, com.zfdang.chess.views.ScanActivity::class.java)
                .putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_PATH, path)
                .putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_JIEQI, true)
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jieqi)
        board = findViewById(R.id.jqBoard)
        statusText = findViewById(R.id.jqStatus)
        opponentLabel = findViewById(R.id.jqOpponent)
        capturedTop = findViewById(R.id.jqCapturedTop)
        capturedBottom = findViewById(R.id.jqCapturedBottom)

        board.onCellTap = { r, c -> onCellTap(r, c) }
        findViewById<View>(R.id.jqNewbt).setOnClickListener { showNewGameDialog() }
        findViewById<View>(R.id.jqFlipbt).setOnClickListener {
            board.viewFlipped = !board.viewFlipped
            updateCaptured()
        }
        findViewById<View>(R.id.jqUndobt).setOnClickListener { undo() }
        findViewById<View>(R.id.jqHintbt).setOnClickListener { requestHint() }
        findViewById<View>(R.id.jqSetupbt).setOnClickListener { showSetupDialog() }
        findViewById<View>(R.id.jqScanbt).setOnClickListener {
            captureLauncher.launch(android.content.Intent(this, com.zfdang.chess.views.CaptureActivity::class.java))
        }
        findViewById<View>(R.id.jqCopybt).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Jieqi FEN", state.fen()))
            Toast.makeText(this, "揭棋局面已复制", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.jqPoolbt).setOnClickListener { showPoolEditor() }

        val importFen = intent.getStringExtra(EXTRA_IMPORT_FEN)
        val restored = loadSaved()
        when {
            importFen != null -> {
                val parsed = JieqiGameState.parseFen(importFen)
                if (parsed == null) {
                    Toast.makeText(this, "揭棋局面无效，已开新局", Toast.LENGTH_LONG).show()
                    newGame(redHuman = true, blackHuman = false, redFirst = true)
                } else {
                    val (grid, redToMove, pools) = JieqiGameState.derivePools(parsed.first, parsed.second)
                    state = JieqiGameState.fromImported(grid, redToMove, pools)
                    redIsHuman = intent.getBooleanExtra(EXTRA_RED_HUMAN, true)
                    blackIsHuman = intent.getBooleanExtra(EXTRA_BLACK_HUMAN, false)
                    finishBoardSetup()
                }
            }
            restored != null -> {
                val (rh, bh) = restored
                val savedState = loadSavedState()
                redIsHuman = rh; blackIsHuman = bh
                if (savedState != null) {
                    state = savedState
                    finishBoardSetup(resumed = true)
                } else {
                    state = JieqiGameState.newGame(java.util.Random(), redToMove = true)
                    finishBoardSetup(resumed = true)
                }
            }
            else -> newGame(redHuman = true, blackHuman = false, redFirst = true)
        }

        client = JieqiEngineClient(this).also { it.start() }
        client?.setRestartCommands(listOf(state.positionCommand()))
    }

    // ------------------------------------------------------------------
    // 对局生命周期
    // ------------------------------------------------------------------

    private fun newGame(redHuman: Boolean, blackHuman: Boolean, redFirst: Boolean) {
        state = JieqiGameState.newGame(java.util.Random(), redToMove = redFirst)
        redIsHuman = redHuman
        blackIsHuman = blackHuman
        undoStack.clear()
        thinking = false
        gameOver = false
        gameOverReason = ""
        finishBoardSetup()
        client?.send("ucinewgame")
        syncEngine()
        maybeEngineForward()
    }

    private fun finishBoardSetup(resumed: Boolean = false) {
        board.grid = state.grid
        board.selected = -1
        board.lastMove = null
        board.hint = -1
        opponentLabel.text = when {
            redIsHuman && !blackIsHuman -> "你执红 · 电脑执黑"
            !redIsHuman && blackIsHuman -> "电脑执红 · 你执黑"
            !redIsHuman && !blackIsHuman -> "电脑演示 · 双方电脑"
            else -> "红黑双人 · 人工执棋"
        }
        updateStatus()
        updateCaptured()
        if (!resumed) persist()
    }

    private fun humanTurn(): Boolean =
        if (state.redToMove) redIsHuman else blackIsHuman

    private fun updateStatus() {
        statusText.text = when {
            gameOver -> "对局结束 · $gameOverReason"
            thinking -> "电脑思考中，请稍候..."
            else -> if (state.redToMove) "轮到红方走棋" else "轮到黑方走棋"
        }
    }

    /** 吃子区：上方是处于棋盘上方的那一方吃掉的子。
     *  棋盘未翻转时红方在下、黑方在上；翻转后相反。 */
    private fun updateCaptured() {
        // captured[0] = 红方吃掉的子；captured[1] = 黑方吃掉的子
        if (!board.viewFlipped) {
            capturedTop.pieces = state.captured[1]   // 黑方在上
            capturedBottom.pieces = state.captured[0] // 红方在下
        } else {
            capturedTop.pieces = state.captured[0]
            capturedBottom.pieces = state.captured[1]
        }
    }

    private fun persist() {
        try {
            val dir = File(getFilesDir(), "jieqi")
            if (!dir.isDirectory) dir.mkdirs()
            File(dir, SAVE_FILE).writeText(
                listOf("1", redIsHuman.toString(), blackIsHuman.toString(),
                    state.serialize()).joinToString("\n"))
        } catch (e: Exception) {
            GcLog.d("JQ persist failed: $e")
        }
    }

    private fun loadSavedState(): JieqiGameState? {
        return try {
            val f = File(File(getFilesDir(), "jieqi"), SAVE_FILE)
            if (!f.isFile) null else {
                val lines = f.readText().split("\n")
                if (lines.size < 4 || lines[0] != "1") null
                else JieqiGameState.newGame(java.util.Random(1)).deserialize(lines[3])
            }
        } catch (e: Exception) { null }
    }

    private fun loadSaved(): Pair<Boolean, Boolean>? {
        return try {
            val f = File(File(getFilesDir(), "jieqi"), SAVE_FILE)
            if (!f.isFile) null else {
                val lines = f.readText().split("\n")
                if (lines.size < 4 || lines[0] != "1") null
                else lines[1].toBoolean() to lines[2].toBoolean()
            }
        } catch (e: Exception) { null }
    }

    override fun onPause() {
        persist()
        super.onPause()
    }

    // ------------------------------------------------------------------
    // 走子（人）
    // ------------------------------------------------------------------

    private fun onCellTap(r: Int, c: Int) {
        if (thinking) { toast("电脑思考中，请稍候..."); return }
        if (!humanTurn()) { toast("等待对方出招"); return }
        if (r !in 0..9 || c !in 0..8) return

        val sel = board.selected
        if (sel >= 0) {
            val fr = sel / 9; val fc = sel % 9
            if (r == fr && c == fc) { board.selected = -1; return }
            val move = intArrayOf(fr, fc, r, c)
            if (prepareMove(move)) return
            // 非法：改选新子
        }
        val p = state.grid[r][c]
        if (p != '.' && JieqiRules.isRed(p) == state.redToMove) {
            board.selected = r * 9 + c
        } else if (p != '.') {
            toast("该走${if (state.redToMove) "红" else "黑"}方棋子")
        }
    }

    /** 组装一手：需要身份录入时弹窗，否则直接应用。返回 true = 已受理（弹窗或已走）。 */
    private fun prepareMove(move: IntArray): Boolean {
        val (fr, fc, tr, tc) = listOf(move[0], move[1], move[2], move[3])
        if (!JieqiRules.isLegal(state.grid, state.redToMove, fr, fc, tr, tc)) return false
        val mover = state.grid[fr][fc]
        val victim = state.grid[tr][tc]
        val movedDark = JieqiRules.isDark(mover)
        val capturedDark = victim != '.' && JieqiRules.isDark(victim)
        // 身份录入需求：
        //  - 移动暗子：新开局由 App 分配自动揭晓；扫描模式需用户录入（看到的真实身份）
        //  - 吃暗子：新开局自动；扫描模式下「AI 吃用户暗子」需录入，「用户吃 AI 暗子」保持未知
        val moverIsAi = !(if (state.redToMove) redIsHuman else blackIsHuman)
        val needReveal = movedDark && state.assignment == null
        val needVictim = capturedDark && state.assignment == null && moverIsAi
        pushUndo()
        board.selected = -1
        if (needReveal || needVictim) {
            pendingMove = PendingMove(fr, fc, tr, tc, needReveal, needVictim)
            if (needReveal) promptIdentity(
                "请选择揭晓身份", "对照原棋盘：该暗子翻出的棋子", state.redToMove) { ch ->
                pendingMove?.reveal = ch
                if (needVictim) promptIdentity(
                    "请输入被吃暗子身份", "对照原棋盘：被吃掉的暗子是", false) { v ->
                    commitPendingMove(v)
                } else commitPendingMove('.')
            }
            else if (needVictim) promptIdentity(
                "请输入被吃暗子身份", "对照原棋盘：被吃掉的暗子是", !state.redToMove) { v ->
                commitPendingMove(v)
            }
            return true
        }
        commitMove(fr, fc, tr, tc, '.', '.')
        return true
    }

    private fun commitPendingMove(victim: Char) {
        val pm = pendingMove ?: return
        pendingMove = null
        commitMove(pm.fr, pm.fc, pm.tr, pm.tc, pm.reveal, victim)
    }

    private var aiRetryUsed = false

    private fun commitMove(fr: Int, fc: Int, tr: Int, tc: Int, reveal: Char, victimReveal: Char, isAiMove: Boolean = false) {
        val err = state.applyMove(fr, fc, tr, tc, reveal, victimReveal, trustEngine = isAiMove)
        GcLog.d("JQ commitMove: $fr,$fc->$tr,$tc reveal=$reveal victim=$victimReveal err=$err redToMoveNow=" + state.redToMove)
        if (err != null) {
            if (isAiMove) {
                // 引擎与 App 局面漂移的保险：重发 position 并重试一次
                thinking = false
                if (aiRetryUsed) { toast("引擎同步异常，请点「新局」重开"); aiRetryUsed = false; return }
                aiRetryUsed = true
                syncEngine()
                maybeEngineForward()
                return
            }
            toast(err); undoStack.removeLastOrNull(); return
        }
        aiRetryUsed = false
        afterMoveApplied(fr, fc, tr, tc)
    }

    private fun afterMoveApplied(fr: Int, fc: Int, tr: Int, tc: Int) {
        board.grid = state.grid
        board.lastMove = intArrayOf(fr, fc, tr, tc)
        board.hint = -1
        updateStatus()
        updateCaptured()
        persist()
        syncEngine()
        checkGameOver()
        maybeEngineForward()
    }

    /** 终局判定：无合法走法 = 将死/困毙。显示绝杀提示，停止 AI。 */
    private fun checkGameOver() {
        if (gameOver) return
        if (stateIsOver()) {
            gameOver = true
            thinking = false
            val winner = if (state.redToMove) "黑方" else "红方"
            gameOverReason = "${winner}胜（绝杀）"
            statusText.text = "对局结束 · $gameOverReason"
            Toast.makeText(this, "绝杀！$gameOverReason", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // AI
    // ------------------------------------------------------------------

    private fun syncEngine() {
        client?.setRestartCommands(listOf(state.positionCommand()))
        client?.send(state.positionCommand())
    }

    private fun maybeEngineForward() {
        if (gameOver) return
        val over = stateIsOver()
        GcLog.d("JQ maybeForward: humanTurn=" + humanTurn() + " thinking=" + thinking +
            " ready=" + engineReady + " over=" + over + " redToMove=" + state.redToMove)
        if (humanTurn() || thinking || !engineReady || over) return
        thinking = true
        updateStatus()
        main.postDelayed({
            if (!thinking) return@postDelayed
            client?.send("go movetime $AI_MOVE_TIME_MS")
        }, 400)
    }

    private fun stateIsOver(): Boolean {
        // 将帅不在棋盘（被吃/摆局缺陷）或无合法走法 = 对局结束
        val moves = JieqiRules.allLegalMoves(state.grid, state.redToMove)
        return moves.isEmpty() || JieqiRules.findKing(state.grid, true) == null ||
            JieqiRules.findKing(state.grid, false) == null
    }

    // JieqiEngineClient.Listener
    override fun onEngineReady() {
        engineReady = true
        GcLog.d("JQ engine ready")
        syncEngine()
        maybeEngineForward()
    }

    override fun onEngineLine(line: String) {
        // 引擎报告 mate N：说明当前搜索方（AI）能杀，或已被杀。记录供提示用。
        val m = Regex("score mate (-?\\d+)").find(line)
        if (m != null && !hintPending) {
            val mateIn = m.groupValues[1].toIntOrNull() ?: 0
            // mate 分为负且绝对值小 = 当前行棋方（AI）被将死，走完最后一步后 checkGameOver 会兜底
            GcLog.d("JQ mate info: $mateIn")
        }
    }

    override fun onBestMove(move: String) {
        GcLog.d("JQ onBestMove: move=$move hintPending=$hintPending thinking=$thinking redToMove=" + state.redToMove)
        val wasHint = hintPending
        hintPending = false
        thinking = false
        if (move == "0000" || move.length < 4) {
            if (wasHint) { toast("无可显示的提示"); return }
            updateStatus()
            // AI 无合法着法：当前行棋方（AI 方）已被将死/困毙
            gameOver = true
            gameOverReason = "${if (state.redToMove) "红方" else "黑方"}胜（绝杀）"
            statusText.text = "对局结束 · $gameOverReason"
            Toast.makeText(this, "绝杀！$gameOverReason", Toast.LENGTH_LONG).show()
            return
        }
        val fr = "abcdefghi".indexOf(move[0])
        val fc = move[1] - '0'
        val tr = "abcdefghi".indexOf(move[2])
        val tc = move[3] - '0'
        // 坐标→网格（引擎 rank0=红底=row9）
        val gr = 9 - fc; val gc = fr
        val gtr = 9 - tc; val gtc = tr
        if (wasHint) {
            board.hint = gtr * 9 + gtc
            board.selected = gr * 9 + gc
            statusText.text = "提示：${"abcdefghi"[gc]}${9 - gr}→${"abcdefghi"[gtc]}${9 - gtr}"
            maybeEngineForward()
            return
        }
        if (!humanTurn()) {
            // AI 的一手
            pushUndo()
            if (prepareAiMove(gr, gc, gtr, gtc)) return
        }
    }

    /** AI bestmove → 应用（扫描模式下暗子揭晓/吃子需用户录入）。 */
    private fun prepareAiMove(gr: Int, gc: Int, gtr: Int, gtc: Int): Boolean {
        val mover = state.grid[gr][gc]
        val victim = state.grid[gtr][gtc]
        val movedDark = JieqiRules.isDark(mover)
        val capturedDark = victim != '.' && JieqiRules.isDark(victim)
        val needReveal = movedDark && state.assignment == null
        val needVictim = capturedDark && state.assignment == null
        if (needReveal || needVictim) {
            pendingMove = PendingMove(gr, gc, gtr, gtc, needReveal, needVictim)
            val moverRed = JieqiRules.isRed(mover)
            if (needReveal) promptIdentity(
                "电脑动了暗子：请录入揭晓身份",
                "原棋盘中你走了该暗子，翻出的${if (moverRed) "红" else "黑"}方棋子是", moverRed) { ch ->
                pendingMove?.reveal = ch
                if (needVictim) promptIdentity(
                    "电脑吃掉了你的暗子：请录入被吃身份",
                    "原棋盘中被吃掉的黑方暗子是", false) { v -> commitPendingMove(v) }
                else commitPendingMove('.')
            } else if (needVictim) promptIdentity(
                "电脑吃掉了你的暗子：请录入被吃身份",
                "对照原棋盘：被吃掉的暗子是", JieqiRules.isRed(victim)) { v -> commitPendingMove(v) }
            updateStatus()
            return true
        }
        commitMove(gr, gc, gtr, gtc, '.', '.', isAiMove = true)
        return true
    }

    override fun onDead(message: String) {
        thinking = false
        engineReady = false
        toast("揭棋引擎已停止：$message")
        updateStatus()
    }

    // ------------------------------------------------------------------
    // 身份录入对话框
    // ------------------------------------------------------------------

    private fun promptIdentity(title: String, desc: String, red: Boolean, onPick: (Char) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_jieqi_identity, null)
        (view.findViewById<TextView>(R.id.jqIdTitle)).text = title
        (view.findViewById<TextView>(R.id.jqIdDesc)).text = desc
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setBackground(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this, R.drawable.ui_dialog_background))
            .setView(view)
            .setCancelable(false)
            .create()
        val letters = mapOf(
            R.id.jqIdR to 'R', R.id.jqIdN to 'N', R.id.jqIdB to 'B',
            R.id.jqIdA to 'A', R.id.jqIdC to 'C', R.id.jqIdP to 'P')
        val names = mapOf('R' to "车", 'N' to "马", 'B' to "相/象", 'A' to "仕/士", 'C' to "炮", 'P' to "兵/卒")
        val color = if (red) resources.getColor(R.color.ui_red) else resources.getColor(R.color.ui_ink)
        for ((id, ch) in letters) {
            val tv = view.findViewById<TextView>(id)
            tv.setTextColor(color)
            tv.setOnClickListener { dlg.dismiss(); onPick(ch) }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------
    // 悔棋 / 新局 / 设置 / 池
    // ------------------------------------------------------------------

    private fun pushUndo() {
        undoStack.addLast(state.snapshot())
        while (undoStack.size > 64) undoStack.removeFirst()
    }

    private fun undo() {
        if (undoStack.isEmpty()) { toast("无着可悔"); return }
        // 回退到「人可行动」的状态：最多两步（电脑手 + 人手）
        var snap = undoStack.removeLast()
        state.restoreFrom(snap)
        // 若回退后轮到电脑，再回退一手（避免 AI 立即重走）
        if (!humanTurn() && undoStack.isNotEmpty()) {
            snap = undoStack.removeLast()
            state.restoreFrom(snap)
        }
        thinking = false
        pendingMove = null
        gameOver = false
        gameOverReason = ""
        finishBoardSetup(resumed = true)
        board.grid = state.grid
        updateCaptured()
        syncEngine()
        maybeEngineForward()
    }

    private fun showNewGameDialog() {
        GameSetupDialog(this,
            redFirstInit = true, redHumanInit = true, blackHumanInit = false
        ) { redFirst, redHuman, blackHuman ->
            newGame(redHuman, blackHuman, redFirst)
        }.show()
    }

    private fun showSetupDialog() {
        GameSetupDialog(this,
            redFirstInit = state.redToMove,
            redHumanInit = redIsHuman,
            blackHumanInit = blackIsHuman
        ) { redFirst, redHuman, blackHuman ->
            redIsHuman = redHuman
            blackIsHuman = blackHuman
            // 应用到当前局面：轮走方改为先手方所选
            // （引擎行棋方由 startFen+token 奇偶决定，必须 rebase 同步，否则 AI 走子永远被拒）
            undoStack.addLast(state.snapshot())
            state.rebase(redFirst)
            thinking = false
            gameOver = false
            gameOverReason = ""
            finishBoardSetup(resumed = true)
            board.grid = state.grid
            updateCaptured()
            syncEngine()
            maybeEngineForward()
        }.show()
    }

    private fun requestHint() {
        if (thinking) { toast("电脑思考中"); return }
        if (!engineReady) { toast("引擎未就绪"); return }
        hintPending = true
        client?.send(state.positionCommand())
        client?.send("go movetime $HINT_MOVE_TIME_MS")
    }

    /** 身份池手动校对（中途导入用）：每类 ±1，双方六类。 */
    private fun showPoolEditor() {
        val rows = StringBuilder()
        val names = listOf("车", "马", "相", "仕", "炮", "兵")
        val slotOrder = listOf(0, 3, 4, 5, 1, 2)
        for ((idx, slot) in slotOrder.withIndex()) {
            rows.append("${names[idx]}: 红${state.pools[0][slot]} 黑${state.pools[1][slot]}  ")
        }
        rows.append("\n暗子: 红${state.darkCount(true)} 黑${state.darkCount(false)}")
        rows.append("\n身份不明被吃: 红${state.darkCapturedUnknown[0]} 黑${state.darkCapturedUnknown[1]}")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setBackground(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this, R.drawable.ui_dialog_background))
            .setTitle("身份池与暗子")
            .setMessage(rows.toString())
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }
}
