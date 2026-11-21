package com.zfdang.chess.engine;

interface IJieqiCallback {
    /** 引擎输出一行（stdout）。 */
    void onLine(String line);

    /** 引擎进程退出（error 非空 = 异常）。回调后本会话失效。 */
    void onFinished(String error);
}
