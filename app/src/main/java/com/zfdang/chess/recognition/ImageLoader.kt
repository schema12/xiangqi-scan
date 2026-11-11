package com.zfdang.chess.recognition

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** 图片解码 + EXIF 旋转矫正（相机 JPEG 竖拍带 orientation，BitmapFactory 不处理） */
object ImageLoader {

    /** 从文件路径或 content Uri 解码为 EXIF 已矫正、长边 ≤ maxDim 的位图；失败返回 null */
    fun decode(path: String?, uri: Uri?, maxDim: Int, resolver: ContentResolver?): Bitmap? {
        val raw: Bitmap = when {
            path != null -> BitmapFactory.decodeFile(path) ?: return null
            uri != null -> resolver?.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            else -> return null
        }
        val orientation = try {
            when {
                path != null -> ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                uri != null && resolver != null -> resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val oriented = applyOrientation(raw, orientation)
        val longEdge = maxOf(oriented.width, oriented.height)
        return if (longEdge > maxDim) {
            val s = maxDim.toFloat() / longEdge
            Bitmap.createScaledBitmap(oriented,
                (oriented.width * s).toInt().coerceAtLeast(1),
                (oriented.height * s).toInt().coerceAtLeast(1), true)
        } else oriented
    }

    private fun applyOrientation(b: Bitmap, orientation: Int): Bitmap = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rot(b, 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rot(b, 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rot(b, 270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flip(b, -1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> flip(b, 1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> flip(rot(b, 90f), -1f, 1f)
        ExifInterface.ORIENTATION_TRANSVERSE -> flip(rot(b, 270f), -1f, 1f)
        else -> b
    }

    private fun rot(b: Bitmap, deg: Float): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height,
            Matrix().apply { postRotate(deg) }, true)

    private fun flip(b: Bitmap, sx: Float, sy: Float): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height,
            Matrix().apply { postScale(sx, sy) }, true)
}
