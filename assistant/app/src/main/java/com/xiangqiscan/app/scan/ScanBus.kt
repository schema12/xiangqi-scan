package com.xiangqiscan.app.scan

import com.xiangqiscan.app.core.Position
import com.xiangqiscan.app.ml.CellResult

/** 页面间传递扫描结果的简单总线（同进程，够用） */
object ScanBus {
    var position: Position? = null
    /** 90 个识别结果（编辑器据此标出"待人工确认"的点） */
    var cells: Array<CellResult?> = arrayOfNulls(90)
    /** 90 个小图（编辑器放大显示用，可为空） */
    var crops: Array<android.graphics.Bitmap?> = arrayOfNulls(90)

    fun clear() {
        position = null
        cells = arrayOfNulls(90)
        crops = arrayOfNulls(90)
    }
}
