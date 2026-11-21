package com.zfdang.chess.jieqi.engine

import com.zfdang.chess.engine.IJieqiCallback

/** 揭棋引擎 JNI 桥（仅 :abjchess 服务进程加载）。接口与 Pikafish 的 NativeBridge 同形。 */
internal object JieqiNativeBridge {
    init { System.loadLibrary("abjchess") }

    /** 创建会话并注册 AIDL 回调（显式 Stub 对象；JNI 侧需要真实接口方法签名）。返回会话 id。 */
    external fun create(callback: IJieqiCallback): Long

    /** 向 UCI stdin 发送一行命令。 */
    external fun command(session: Long, command: String)

    /** 释放会话（引擎循环结束后调用）。 */
    external fun release(session: Long)

    /** 停止引擎（发送 quit 并解除阻塞）。 */
    external fun stop(session: Long)

    /** 在调用线程上阻塞运行 UCI 循环，直到 quit/stop。nnueDir 供引擎日志描述。 */
    external fun run(session: Long, nnueDir: String)
}
