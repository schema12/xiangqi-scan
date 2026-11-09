package com.xiangqiscan.app.io

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.topjohnwu.superuser.Shell
import com.xiangqiscan.app.core.Fen
import com.xiangqiscan.app.core.Position
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把识别出的局面写入《中国象棋》(com.cnvcs.xiangqi) 的棋谱目录。
 * 主路径：root（suu/su）直接 cp 进 Android/data/.../棋谱/其它；
 * 回退：写入公共 Download/象棋摆谱/ 由用户手动导入。
 */
object Delivery {

    const val CHESS_PKG = "com.cnvcs.xiangqi"

    data class Result(val ok: Boolean, val viaRoot: Boolean, val message: String, val path: String?)

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun deliver(ctx: Context, pos: Position, prefs: Prefs, forceFallback: Boolean = false): Result {
        val pgn = Fen.buildPgn(pos)
        val name = "scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pgn"

        // 1) root 直写
        if (!forceFallback && tryRoot(ctx, pgn, name, prefs)) {
            return Result(true, true, "已写入棋谱目录：" + prefs.targetDir, prefs.targetDir + "/" + name)
        }

        // 2) 回退：Download
        val saved = saveToDownload(ctx, name, pgn)
        return if (saved) {
            Result(false, false, "已导出到 Download/象棋摆谱/$name，请在象棋App中手动导入", null)
        } else {
            Result(false, false, "写入失败", null)
        }
    }

    private fun tryRoot(ctx: Context, content: String, name: String, prefs: Prefs): Boolean {
        val dir = prefs.targetDir
        val tmp = File(ctx.getExternalFilesDir("exports") ?: ctx.filesDir, name)
        try {
            tmp.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val cmds = linkedSetOf(prefs.suCommand, "su")
        for (cmd in cmds) {
            try {
                val shell = Shell.Builder.create().build(cmd)
                if (!shell.isRoot) {
                    shell.close()
                    continue
                }
                val script = StringBuilder().apply {
                    append("mkdir -p ${quote(dir)} && ")
                    append("cp ${quote(tmp.absolutePath)} ${quote(dir + "/" + name)} && ")
                    // 属主与目录一致，权限 660（与 adb 实测通过的方式一致）
                    append("OWNER=$(stat -c '%U:%G' ${quote(dir)} 2>/dev/null) && ")
                    append("chown \"\${OWNER:-root:root}\" ${quote(dir + "/" + name)} 2>/dev/null; ")
                    append("chmod 660 ${quote(dir + "/" + name)}")
                }.toString()
                val res = shell.newJob().add(script).exec()
                val ok = res.isSuccess
                shell.close()
                if (ok) {
                    tmp.delete()
                    return true
                }
            } catch (e: Exception) {
                // 尝试下一个
            }
        }
        return false
    }

    private fun saveToDownload(ctx: Context, name: String, content: String): Boolean {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-chess-pgn")
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/象棋摆谱")
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
            ) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }
}
