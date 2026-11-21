package com.zfdang.chess.engine;

import com.zfdang.chess.engine.IJieqiCallback;

interface IJieqiService {
    /** 启动引擎会话（提取引擎与网络文件、拉起子进程）。返回会话 id，0 = 失败（回调 onFinished）。 */
    long start(IJieqiCallback callback);

    /** 向引擎 stdin 发送一行命令。 */
    void command(long session, String command);

    /** 终止该会话的引擎进程。 */
    void shutdown(long session);
}
