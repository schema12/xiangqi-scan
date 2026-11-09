package com.xiangqiscan.app.io

import android.content.Context
import android.content.SharedPreferences

/** 简单设置存储 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("xiangqiscan", Context.MODE_PRIVATE)

    /** root 命令名：本机实测为 suu；可改 su 或自定义 */
    var suCommand: String
        get() = sp.getString("su_cmd", "suu") ?: "suu"
        set(v) = sp.edit().putString("su_cmd", v).apply()

    /** 目标棋谱目录（绝对路径），默认为 cnvcs 中国象棋的"其它"目录 */
    var targetDir: String
        get() = sp.getString(
            "target_dir",
            "/storage/emulated/0/Android/data/com.cnvcs.xiangqi/files/棋谱/其它"
        ) ?: ""
        set(v) = sp.edit().putString("target_dir", v).apply()

    /** 写入成功后自动打开象棋 App */
    var autoOpenChess: Boolean
        get() = sp.getBoolean("auto_open", true)
        set(v) = sp.edit().putBoolean("auto_open", v).apply()
}
