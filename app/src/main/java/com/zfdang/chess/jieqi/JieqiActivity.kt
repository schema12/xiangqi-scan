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
        const val EXTRA_CAPTURES = "jqCaptures"           // 可选吃子补充："红明子,红暗数;黑明子,黑暗数"
        const val EXTRA_SCAN_GRID = "jqScanGrid"         // 失败回传：校对后的棋盘
        const val EXTRA_SCAN_FEN = "jqScanFen"           // 失败回传：校对页展示的 FEN
        const val EXTRA_RESTORE_GRID = "jqRestoreGrid"
        const val EXTRA_RESTORE_FEN = "jqRestoreFen"
        const val EXTRA_RESTORE_REASON = "jqRestoreReason"
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

    /** 身份录入弹窗最小化后，保存"重新弹出"的闭包；浮动按钮点击时调用 */
    private var pendingIdentityPrompt: (() -> Unit)? = null

    /** AI 翻暗子待用户确认：已记录该手，等用户点击标记的目的地后再弹窗录入身份。
     *  与谁执红/执黑无关，切换身份设置不会破坏此状态；坐标均为逻辑坐标，翻转棋盘自动适配。 */
    private var awaitingAiRevealConfirm = false

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
        findViewById<View>(R.id.jqResumeIdentity).setOnClickListener { resumeIdentityPrompt() }
        findViewById<View>(R.id.jqNewbt).setOnClickListener { showNewGameDialog() }
        findViewById<View>(R.id.jqFlipbt).setOnClickListener {
            board.viewFlipped = !board.viewFlipped
            updateCaptured()
        }
        findViewById<View>(R.id.jqUndobt).setOnClickListener { undo() }
        findViewById<View>(R.id.jqHintbt).setOnClickListener { requestHint() }
        findViewById<View>(R.id.jqSetupbt).setOnClickListener { showSetupDialog() }
        findViewById<View>(R.id.jqScanbt).setOnClickListener {
            captureLauncher.launch(android.content.Intent(this, com.zfdang.chess.views.CaptureActivity::class.java)
                .putExtra(com.zfdang.chess.views.CaptureActivity.EXTRA_JIEQI, true))
        }
        findViewById<View>(R.id.jqCopybt).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Jieqi FEN", state.fen()))
            Toast.makeText(this, "揭棋局面已复制", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.jqPoolbt).setOnClickListener { showPoolEditor() }
        findViewById<View>(R.id.jqCapturesBt).setOnClickListener { startBoardEditor() }

        val importFen = intent.getStringExtra(EXTRA_IMPORT_FEN)
        val restored = loadSaved()
        when {
            importFen != null -> {
                val parsed = JieqiGameState.parseFenWithError(importFen)
                if (parsed.value == null) {
                    // 导入失败：绝不自动开新局。显示引擎可读原因，并把校对结果回传 ScanActivity
                    val reason = parsed.error ?: "未知原因"
                    val scanGrid = intent.getStringExtra(EXTRA_SCAN_GRID)
                    val scanFen = intent.getStringExtra(EXTRA_SCAN_FEN)
                    if (scanGrid != null && scanFen != null) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("引擎拒绝该局面")
                            .setMessage("原因：$reason\n\nFEN：$importFen\n\n将返回校对页，你的棋盘与修改都会保留，请修正后重试。")
                            .setPositiveButton("返回校对") { _, _ ->
                                val back = android.content.Intent(this,
                                    com.zfdang.chess.views.ScanActivity::class.java).apply {
                                    putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_PATH,
                                        intent.getStringExtra(com.zfdang.chess.views.ScanActivity.EXTRA_PATH))
                                    putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_URI,
                                        intent.getStringExtra(com.zfdang.chess.views.ScanActivity.EXTRA_URI))
                                    putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_JIEQI, true)
                                    putExtra(EXTRA_RESTORE_GRID, scanGrid)
                                    putExtra(EXTRA_RESTORE_FEN, scanFen)
                                    putExtra(EXTRA_RESTORE_REASON, reason)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                startActivity(back)
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        // 没有可回传的校对状态（例如外部直接以 FEN 启动）：保留在页内提示，不开新局
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("引擎拒绝该局面")
                            .setMessage("原因：$reason\n\nFEN：$importFen")
                            .setPositiveButton("知道了", null)
                            .setCancelable(false)
                            .show()
                    }
                } else {
                    val (grid, redToMove, pools) = JieqiGameState.derivePools(parsed.value!!.first, parsed.value!!.second)
                    state = JieqiGameState.fromImported(grid, redToMove, pools)
                    applyCapturesSpec(intent.getStringExtra(EXTRA_CAPTURES))
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

    /** 未知新局：标准开局摆法，但 App 不知道暗子身份，走暗子/吃暗子均需人工录入（等同扫描新局） */
    private fun newGameUnknown(redHuman: Boolean, blackHuman: Boolean, redFirst: Boolean) {
        state = JieqiGameState.newGameUnknown(redToMove = redFirst)
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
        toast("未知新局：暗子身份由你揭晓（等同扫描标准新局）")
    }

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
        board.revealCell = -1
        awaitingAiRevealConfirm = false
        pendingIdentityPrompt = null
        findViewById<View>(R.id.jqResumeIdentity).visibility = View.GONE
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
            awaitingAiRevealConfirm -> "电脑翻了暗子：请点琥珀色标记的目的地录入身份"
            thinking -> "电脑思考中，请稍候..."
            else -> if (state.redToMove) "轮到红方走棋" else "轮到黑方走棋"
        }
    }

    /** 吃子区：上方是处于棋盘上方的那一方吃掉的子。
     *  棋盘未翻转时红方在下、黑方在上；翻转后相反。 */
    private fun updateCaptured() {
        // 新局(assignment!=null)：App 持有全部真实身份，按「软件玩家方」过滤可见性
        //   —— 玩家吃的暗子显示正面，AI 吃的暗子对玩家显示背面（与参考 sanitize 一致）
        // 扫描局(assignment==null)：App 不知道暗子身份，录入流程已决定 truth：
        //   AI 吃玩家暗子(用户录入了身份)→正面；玩家吃 AI 暗子(看不到)→'?'背面。
        //   此处不能再套新局的 observer 过滤，否则两种视角叠加会显示反。
        val observer: Int? = if (state.assignment != null) when {
            redIsHuman && !blackIsHuman -> 0
            !redIsHuman && blackIsHuman -> 1
            else -> null
        } else null   // 扫描局：按 truth 直接显示
        fun viewList(eater: Int) = state.capturedViewList(eater, observer)
        // captured[0] = 红方吃掉的子；captured[1] = 黑方吃掉的子
        if (!board.viewFlipped) {
            capturedTop.pieces = viewList(1)    // 黑方在上：显示黑方吃掉的子
            capturedBottom.pieces = viewList(0) // 红方在下：显示红方吃掉的子
        } else {
            capturedTop.pieces = viewList(0)
            capturedBottom.pieces = viewList(1)
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
        val tappedPiece = state.grid.getOrNull(r)?.getOrNull(c) ?: '?'
        GcLog.d("JQ tap: grid[$r][$c]='$tappedPiece' redToMove=${state.redToMove} sel=$board.selected reveal=${board.revealCell} await=$awaitingAiRevealConfirm")
        if (r !in 0..9 || c !in 0..8) return

        // AI 翻暗子待确认：只认标记的目的地（逻辑坐标），点它才弹窗录身份。
        // 翻转棋盘时 onCellTap 收到的已是逆映射后的逻辑坐标，无需额外换算。
        if (awaitingAiRevealConfirm) {
            val pm = pendingMove
            if (pm == null) { awaitingAiRevealConfirm = false; return }
            if (r == pm.tr && c == pm.tc) {
                awaitingAiRevealConfirm = false
                board.selected = -1
                board.revealCell = -1
                showPendingIdentity(pm)
            } else {
                toast("请点击琥珀色标记的目的地，确认电脑翻的暗子")
            }
            return
        }

        if (thinking) { toast("电脑思考中，请稍候..."); return }
        if (!humanTurn()) { toast("等待对方出招"); return }

        val sel = board.selected
        if (sel >= 0) {
            val fr = sel / 9; val fc = sel % 9
            if (r == fr && c == fc) { board.selected = -1; return }
            // 已选中棋子：本次点击视为目标格。无论是否合法，都不应把目标格当成「改选棋子」
            // （目标格常是对方暗子，旧代码 fallthrough 后误弹「该走红方棋子」）。
            prepareMove(intArrayOf(fr, fc, r, c))
            return
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
        if (!JieqiRules.isLegal(state.grid, state.redToMove, fr, fc, tr, tc)) {
            board.selected = -1
            // 区分非法原因：送将(含照面) vs 不符合走法规则
            val exposes = (tr to tc) in JieqiRules.destinations(state.grid, fr, fc) &&
                JieqiRules.wouldExposeKing(state.grid, state.redToMove, fr, fc, tr, tc)
            toast(if (exposes) "不能送将" else "该走法不合法")
            return false
        }
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
            board.revealCell = tr * 9 + tc
            // 人工翻暗子：保持原样，直接弹窗录入
            showPendingIdentity(pendingMove!!)
            return true
        }
        commitMove(fr, fc, tr, tc, '.', '.')
        return true
    }

    /** 弹出待录身份的弹窗（人工直接调用；AI 待用户点目的地后调用）。
     *  全部颜色/字形由 PendingMove 自身数据推导，与当前人机身份设置、棋盘翻转无关。 */
    private fun showPendingIdentity(pm: PendingMove) {
        val mover = state.grid.getOrNull(pm.fr)?.getOrNull(pm.fc) ?: '.'
        val victim = state.grid.getOrNull(pm.tr)?.getOrNull(pm.tc) ?: '.'
        val moverRed = JieqiRules.isRed(mover)
        if (pm.needReveal) promptIdentity(
            "请录入揭晓身份", "对照原棋盘：${if (moverRed) "红" else "黑"}方暗子翻出的棋子是", moverRed) { ch ->
            pm.reveal = ch
            if (pm.needVictim) promptIdentity(
                "请录入被吃暗子身份", "对照原棋盘：被吃掉的${if (JieqiRules.isRed(victim)) "红" else "黑"}方暗子是",
                JieqiRules.isRed(victim)) { v -> commitPendingMove(v) }
            else commitPendingMove('.')
        } else promptIdentity(
            "请录入被吃暗子身份", "对照原棋盘：被吃掉的暗子是", JieqiRules.isRed(victim)) { v ->
            commitPendingMove(v)
        }
    }

    private fun commitPendingMove(victim: Char) {
        val pm = pendingMove ?: return
        pendingMove = null
        awaitingAiRevealConfirm = false
        board.revealCell = -1
        board.selected = -1
        findViewById<View>(R.id.jqResumeIdentity).visibility = View.GONE
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
            // AI 翻暗子新流程：同时框出起点（selected）和终点（revealCell），不弹窗；
            // 等用户点终点后才弹窗录身份（onCellTap 的 awaitingAiRevealConfirm 分支）。
            board.selected = gr * 9 + gc
            board.revealCell = gtr * 9 + gtc
            awaitingAiRevealConfirm = true
            statusText.text = "电脑翻了暗子（见琥珀色框）：请点标记的目的地录入揭晓身份"
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

    /** 解析可选的吃子补充："红明子,红暗数;黑明子,黑暗数"。
     *  明子按 FEN 字母原样加入 captured[eater]（wasHidden=false）；
     *  暗子数用 X/x 背面加入（wasHidden=true，身份未知）。 */
    private fun applyCapturesSpec(spec: String?) {
        if (spec.isNullOrBlank()) return
        val (red, black) = JieqiCaptures.parse(spec)
        for (side in 0..1) {
            val map = if (side == 0) red else black
            val dark = if (side == 0) JieqiCaptures.DARK_RED else JieqiCaptures.DARK_BLACK
            for ((ch, n) in map) {
                repeat(n) {
                    state.captured[side].add(CapturedPiece(ch, ch == dark))
                }
            }
        }
    }

    /** 对局页「摆棋」：把当前局面 + 吃子 + 先手方带回校对页（跳过识别）。
     *  校对页可改局面棋子/位置/数量与吃子，点校准完成确认先后手和身份后重新开局。
     *  对局历史/悔棋栈会重置（摆棋即手动悔棋到校对局面），符合用户预期。 */
    private fun startBoardEditor() {
        if (thinking || pendingMove != null || awaitingAiRevealConfirm) {
            Toast.makeText(this, "请先完成当前招法/身份录入再摆棋", Toast.LENGTH_SHORT).show()
            return
        }
        val gridStr = state.grid.joinToString("/") { it.joinToString("") }
        val i = android.content.Intent(this, com.zfdang.chess.views.ScanActivity::class.java).apply {
            putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_JIEQI, true)
            putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_EDIT_GRID, gridStr)
            putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_EDIT_RED_FIRST, state.redToMove)
            putExtra(com.zfdang.chess.views.ScanActivity.EXTRA_EDIT_CAPTURES,
                JieqiCaptures.specOf(state.captured))
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
        finish()
    }

    // ------------------------------------------------------------------
    // 身份录入对话框
    // ------------------------------------------------------------------

    private fun promptIdentity(title: String, desc: String, red: Boolean, onPick: (Char) -> Unit) {
        // 抽成闭包：点"最小化"后 dismiss，但仍可用浮动按钮重新弹出（保留同一待录入状态）
        var show: () -> Unit = {}
        show = {
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
            // 引擎方言要求揭晓身份字母大小写与行棋方一致：红方大写、黑方小写。
            val letters = mapOf(
                R.id.jqIdR to 'R', R.id.jqIdN to 'N', R.id.jqIdB to 'B',
                R.id.jqIdA to 'A', R.id.jqIdC to 'C', R.id.jqIdP to 'P')
            val names = if (red) {
                mapOf('R' to "车", 'N' to "马", 'B' to "相", 'A' to "仕", 'C' to "炮", 'P' to "兵")
            } else {
                mapOf('R' to "车", 'N' to "马", 'B' to "象", 'A' to "士", 'C' to "炮", 'P' to "卒")
            }
            val color = if (red) resources.getColor(R.color.ui_red) else resources.getColor(R.color.ui_ink)
            for ((id, ch) in letters) {
                val tv = view.findViewById<TextView>(id)
                tv.text = names[ch]
                tv.setTextColor(color)
                tv.setOnClickListener {
                    dlg.dismiss()
                    pendingIdentityPrompt = null
                    findViewById<View>(R.id.jqResumeIdentity).visibility = View.GONE
                    onPick(if (red) ch else ch.lowercaseChar())
                }
            }
            view.findViewById<TextView>(R.id.jqIdMinimize).setOnClickListener {
                dlg.dismiss()
                pendingIdentityPrompt = show
                findViewById<TextView>(R.id.jqResumeIdentity).apply {
                    text = "待录入揭晓身份 · $title"
                    visibility = View.VISIBLE
                }
            }
            dlg.show()
        }
        pendingIdentityPrompt = show
        show()
    }

    /** 最小化后点击浮动按钮重新弹出身份录入 */
    private fun resumeIdentityPrompt() {
        pendingIdentityPrompt?.invoke()
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
        awaitingAiRevealConfirm = false
        gameOver = false
        gameOverReason = ""
        finishBoardSetup(resumed = true)
        board.grid = state.grid
        updateCaptured()
        syncEngine()
        maybeEngineForward()
    }

    private fun showNewGameDialog() {
        // 新局两种：标准（App 秘密分配、自动揭晓）/ 未知（所有暗子由用户揭晓，等同扫描新局）
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setBackground(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this, R.drawable.ui_dialog_background))
            .setTitle("选择新局类型")
            .setItems(arrayOf("标准新局（自动揭晓）", "未知新局（暗子由我揭晓）")) { _, which ->
                GameSetupDialog(this,
                    redFirstInit = true, redHumanInit = true, blackHumanInit = false
                ) { redFirst, redHuman, blackHuman ->
                    if (which == 0) newGame(redHuman, blackHuman, redFirst)
                    else newGameUnknown(redHuman, blackHuman, redFirst)
                }.show()
            }
            .setNegativeButton("取消", null)
            .show()
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
