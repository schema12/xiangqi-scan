package com.xiangqiscan.app

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    companion object {
        init {
            // root shell 配置：不内置 su 名字，运行时按用户设置尝试 suu / su
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }
    }
}
