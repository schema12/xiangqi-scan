package com.zfdang.chess.jieqi.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.zfdang.chess.ChessApp
import com.zfdang.chess.controllers.GcLog
import com.zfdang.chess.engine.IJieqiCallback
import com.zfdang.chess.engine.IJieqiService

/**
 * 揭棋引擎客户端（UI 进程）：绑定 [JieqiService]，提供 UCI 会话。
 *
 * 事件模型（全部回调到主线程）：
 *  - onReady：uciok + readyok 完成，可发 position/go
 *  - onInfo：info 深度/分数/pv（提示与思考状态展示）
 *  - onBestMove：搜索结束（含 "0000"）
 *  - onDead：引擎进程退出；自动重启一次并重发当前局面（自动重试上限内）
 */
class JieqiEngineClient(
    private val listener: Listener,
    private val engineTag: String = "abjchess"
) : ServiceConnection {

    interface Listener {
        fun onEngineReady()
        fun onEngineLine(line: String)
        fun onBestMove(move: String)
        fun onDead(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: IJieqiService? = null
    private var session = 0L
    private var bound = false
    private var closed = false

    /** 绑定后待发的命令（服务就绪前缓存）。 */
    private val pending = ArrayList<String>()
    private var uciOk = false
    private var readyOk = false
    private var retryCount = 0
    /** 引擎重启后需要重发的「会话级」命令（position 等），由宿主通过 setRestartCommands 注入 */
    private var restartCommands: List<String> = emptyList()
    private var restartedForRetry = false

    private val callback = object : IJieqiCallback.Stub() {
        override fun onLine(line: String) {
            if (closed) return
            main.post { handleLine(line) }
        }
        override fun onFinished(error: String) {
            if (closed) return
            main.post { handleDead(error) }
        }
    }

    private fun handleLine(line: String) {
        GcLog.d("JQ<-: $line")
        when {
            line == "uciok" -> { uciOk = true; sendPending() }
            line == "readyok" -> {
                if (!readyOk) {
                    readyOk = true
                    listener.onEngineReady()
                    if (restartedForRetry) replayRestartCommands()
                    sendPending()
                }
            }
            line.startsWith("bestmove") -> {
                val mv = line.trim().split(" ").getOrNull(1) ?: "0000"
                listener.onBestMove(mv)
            }
            line.startsWith("info") -> listener.onEngineLine(line)
            else -> if (!line.startsWith("id ") && !line.startsWith("option ")) listener.onEngineLine(line)
        }
    }

    private fun handleDead(error: String) {
        GcLog.d("JQ engine dead: $error")
        session = 0
        service = null
        if (closed) return
        // 自动重启一次（后续失败交给宿主提示）
        if (retryCount < 2 && restartCommands.isNotEmpty()) {
            retryCount++
            restartedForRetry = true
            uciOk = false; readyOk = false
            // 旧会话释放可能有延迟（Activity 重建竞态），延迟重启
            main.postDelayed({ if (!closed) start() }, 900)
            return
        }
        listener.onDead(if (error.isBlank()) "engine exited" else error)
    }

    private fun sendPending() {
        // 引擎命令在 JNI 队列内保序：无需等待握手完成即可直发
        val svc = service ?: return
        for (c in pending) svc.command(session, c)
        pending.clear()
    }

    private fun replayRestartCommands() {
        for (c in restartCommands) service?.command(session, c)
    }

    /** 引擎重启后需重发的会话级命令（宿主在 position 变化时调用以保持同步）。 */
    fun setRestartCommands(commands: List<String>) {
        restartCommands = commands.toList()
    }

    fun start() {
        if (closed || bound) return
        bound = try {
            ChessApp.getContext().bindService(
                Intent(ChessApp.getContext(), JieqiService::class.java), this,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            GcLog.d("JQ bind failed: $e")
            main.post { listener.onDead(e.toString()) }
            false
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (closed) return
        service = IJieqiService.Stub.asInterface(binder)
        try {
            session = service!!.start(callback)
            if (session == 0L) return // onFinished 已回调
            pending.add(0, "uci")
            pending.add(1, "setoption name Threads value 4")
            pending.add(2, "setoption name Hash value 256")
            pending.add(3, "setoption name EvalFile value " +
                JieqiAssets.networkFile(ChessApp.getContext()).absolutePath)
            pending.add(4, "isready")
            sendPending()
        } catch (e: RemoteException) {
            main.post { listener.onDead(e.toString()) }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) { /* handleDead 由 onFinished 触发 */ }

    /** 发送一条命令；引擎未就绪时进入 pending（uciok+readyok 后按序发出）。 */
    fun send(command: String) {
        if (closed) return
        GcLog.d("JQ->: $command")
        val svc = service
        if (svc == null) { pending.add(command); return }
        try { svc.command(session, command) } catch (e: RemoteException) {
            GcLog.d("JQ send failed: $e")
        }
    }

    /** 立即清空 pending（例如引擎重启前丢弃过时命令）。 */
    fun clearPending() { pending.clear() }

    fun close() {
        closed = true
        try { service?.shutdown(session) } catch (ignored: Exception) { }
        if (bound) ChessApp.getContext().unbindService(this)
        bound = false
        service = null
    }
}
