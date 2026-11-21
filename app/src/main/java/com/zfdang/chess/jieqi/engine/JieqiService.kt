package com.zfdang.chess.jieqi.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import com.zfdang.chess.engine.IJieqiCallback
import com.zfdang.chess.engine.IJieqiService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 揭棋引擎宿主服务（独立进程 :abjchess）。
 *
 * 引擎 = AB-JChess JNI 库（libabjchess.so，stdio UCI 循环在库内线程运行）。
 * 与 Pikafish 的 JNI 服务完全隔离（独立进程/独立 AIDL/独立库），互不影响。
 */
class JieqiService : Service() {
    private class Session(val callback: IJieqiCallback)

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "Jieqi-Engine") }
    @Volatile private var destroyed = false

    private val binder = object : IJieqiService.Stub() {
        override fun start(callback: IJieqiCallback): Long = startSession(callback)
        override fun command(session: Long, command: String) { JieqiNativeBridge.command(session, command) }
        override fun shutdown(session: Long) { JieqiNativeBridge.stop(session) }
    }

    private fun finished(callback: IJieqiCallback, error: String) {
        try { callback.onFinished(error) } catch (ignored: RemoteException) { }
    }

    private fun startSession(callback: IJieqiCallback): Long {
        if (callback == null) return 0
        if (destroyed) { finished(callback, "Jieqi service shutting down"); return 0 }
        // 显式 Stub 对象（Kotlin lambda 会丢失接口方法签名，JNI GetMethodID 找不到）
        val bridge = object : IJieqiCallback.Stub() {
            override fun onLine(line: String) {
                try { callback.onLine(line) } catch (ignored: RemoteException) { }
            }
            override fun onFinished(error: String) {
                try { callback.onFinished(error) } catch (ignored: RemoteException) { }
            }
        }
        val id = JieqiNativeBridge.create(bridge)
        if (id == 0L) { finished(callback, "engine session already running"); return 0 }
        sessions[id] = Session(callback)
        try { callback.asBinder().linkToDeath({ JieqiNativeBridge.stop(id) }, 0) }
        catch (ignored: RemoteException) { }
        worker.execute {
            var failure = ""
            try {
                JieqiAssets.prepare(this@JieqiService)
                // 阻塞运行 UCI 循环（直到 quit/stop）
                JieqiNativeBridge.run(id, JieqiAssets.engineDir(this@JieqiService).absolutePath)
            } catch (e: Throwable) { failure = e.toString() }
            finally {
                if (sessions.remove(id) != null) {
                    try { JieqiNativeBridge.release(id) } catch (ignored: Throwable) { }
                    finished(callback, failure)
                }
            }
        }
        return id
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        destroyed = true
        for (id in sessions.keys.toList()) JieqiNativeBridge.stop(id)
        worker.shutdown()
        super.onDestroy()
    }
}
