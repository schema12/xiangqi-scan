package com.xiangqiscan.app.ml

import android.graphics.Bitmap
import com.xiangqiscan.app.core.PieceType
import com.xiangqiscan.app.core.Side

/**
 * 单个交叉点的分类结果。
 * type == null 表示"有棋子但认不出字"，需要在编辑器里人工指定。
 */
data class CellResult(
    val empty: Boolean,
    val side: Side? = null,
    val type: PieceType? = null,
    val confidence: Float = 0f
)

/** 棋子分类器接口：输入为以交点为中心的方形小图 */
interface PieceClassifier {
    fun classify(crop: Bitmap): CellResult
    fun close() {}
}
