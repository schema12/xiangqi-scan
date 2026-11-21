package com.zfdang.chess.jieqi.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import com.zfdang.chess.engine.IJieqiCallback
import com.zfdang.chess.engine.IJieqiService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 揭棋引擎宿主服务（独立进程 :abjchess）。
 *
 * 引擎 = AB-JChess 静态可执行文件（stdio UCI）。本服务从 assets 提取引擎与 NNUE，
 * 以 ProcessBuilder 拉起子进程，并把 stdin/stdout 通过 Binder 桥接到 UI 进程。
 * 与 Pikafish 的 JNI 服务完全隔离，互不影响。
 */
class JieqiService : Service() {
    private class Session(
        val callback: IJieqiCallback,
        @Volatile var process: Process? = null,
        @Volatile var writer: BufferedWriter? = null
    )

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "Jieqi-Engine") }
    @Volatile private var destroyed = false

    private val binder = object : IJieqiService.Stub() {
        override fun start(callback: IJieqiCallback): Long = startSession(callback)
        override fun command(session: Long, command: String) {
            val s = sessions[session] ?: return
            try {
                s.writer?.write(command)
                s.writer?.newLine()
                s.writer?.flush()
            } catch (e: Exception) {
                killSession(session, "engine write failed: ${e.message}")
            }
        }
        override fun shutdown(session: Long) = killSession(session, "")
    }

    private fun finished(callback: IJieqiCallback, error: String) {
        try { callback.onFinished(error) } catch (ignored: RemoteException) { }
    }

    private fun startSession(callback: IJieqiCallback): Long {
        if (callback == null) return 0
        if (destroyed) { finished(callback, "Jieqi service shutting down"); return 0 }
        val id = nextId.incrementAndGet()
        val session = Session(callback)
        sessions[id] = session
        worker.execute {
            var failure = ""
            try {
                JieqiAssets.prepare(this@JieqiService)
                val bin = JieqiAssets.engineFile(this@JieqiService)
                val workDir = JieqiAssets.engineDir(this@JieqiService)
                val proc = ProcessBuilder(bin.absolutePath)
                    .directory(workDir)
                    .redirectErrorStream(false)
                    .start()
                session.process = proc
                session.writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
                callback.asBinder().linkToDeath({ killSession(id, "") }, 0)

                // stdout 泵：逐行回调 UI 进程
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        try { callback.onLine(line) } catch (ignored: RemoteException) { }
                    }
                }
                if (sessions.containsKey(id)) failure = "engine exited unexpectedly"
            } catch (e: Exception) {
                failure = e.toString()
            } finally {
                if (sessions.remove(id, session)) {
                    session.process?.destroy()
                    finished(callback, failure)
                }
            }
        }
        return id
    }

    private fun killSession(session: Long, error: String) {
        val s = sessions.remove(session) ?: return
        try { s.process?.destroy() } catch (ignored: Exception) { }
        finished(s.callback, error)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        destroyed = true
        for (id in sessions.keys.toList()) killSession(id, "service destroyed")
        worker.shutdown()
        super.onDestroy()
    }

    companion object { private val nextId = java.util.concurrent.atomic.AtomicLong(1) }
}
