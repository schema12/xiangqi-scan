package com.zfdang.chess.jieqi.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 揭棋引擎（AB-JChess 可执行文件 + NNUE 网络）从 assets 提取到内部存储。
 * 二进制与网络的 SHA-256 与上游发布版逐一核对，损坏即拒绝启动。
 */
object JieqiAssets {
    const val ENGINE_VERSION = "AB-JChess-20261031"
    const val NET_NAME = "abjchess-20261030.nnue"
    const val NET_SHA256 = "fdb6245b868bb204cf2b64fc4f8440d7a1e22b73abdd2a13dce180b65b01619e"
    const val NET_SIZE = 580156L

    /** 引擎目录（NNUE 数据所在）：filesDir/abjchess */
    fun engineDir(context: Context): File = File(context.getFilesDir(), "abjchess")

    fun networkFile(context: Context): File = File(engineDir(context), NET_NAME)

    private fun extract(context: Context, assetName: String, target: File, sha256: String, size: Long) {
        if (verify(target, sha256, size)) return
        val dir = target.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create dir $dir")
        val tmp = File(dir, target.name + ".tmp")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            context.assets.open(assetName).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                    }
                    out.getFD().sync()
                }
            }
            val digest = StringBuilder().also { sb ->
                for (b in md.digest()) sb.append(String.format("%02x", b))
            }.toString()
            if (!digest.equals(sha256, ignoreCase = true) || tmp.length() != size)
                throw IOException("jieqi asset checksum mismatch: $assetName")
            if (!tmp.renameTo(target)) throw IOException("Cannot install $assetName")
        } finally {
            tmp.delete()
        }
    }

    private fun verify(f: File, sha256: String, size: Long): Boolean {
        if (!f.isFile || f.length() != size) return false
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val digest = StringBuilder().also { sb ->
            for (b in md.digest()) sb.append(String.format("%02x", b))
        }.toString()
        return digest.equals(sha256, ignoreCase = true)
    }

    /** 在 service worker 线程调用：确保 NNUE 网络文件就绪（引擎库由 jniLibs 装载）。 */
    fun prepare(context: Context) {
        extract(context, "jieqi/$NET_NAME", networkFile(context), NET_SHA256, NET_SIZE)
    }
}
