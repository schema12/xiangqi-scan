package com.xiangqiscan.app.editor

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xiangqiscan.app.core.Piece
import com.xiangqiscan.app.core.PieceType
import com.xiangqiscan.app.core.Position
import com.xiangqiscan.app.core.Side
import com.xiangqiscan.app.databinding.ActivityEditorBinding
import com.xiangqiscan.app.io.Delivery
import com.xiangqiscan.app.io.Prefs
import com.xiangqiscan.app.scan.ScanBus
import kotlin.math.roundToInt

/**
 * 确认/编辑页：识别结果摆上棋盘，点子可改，确认后写入象棋 App。
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditorBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        val pos = ScanBus.position ?: Position()
        b.editor.position = pos
        for (i in 0 until 90) {
            val cell = ScanBus.cells.getOrNull(i)
            if (cell != null && !cell.empty && cell.type == null) b.editor.pending.add(i)
        }
        if (ScanBus.cells.isNotEmpty()) {
            Toast.makeText(this, "识别完成：标\"?\"的点请点选后指定棋子", Toast.LENGTH_LONG).show()
        }

        buildPalette()
        updateTurnLabel()

        b.editor.onSelect = { b.editor.invalidate() }
        b.btnFlip.setOnClickListener {
            b.editor.position.rotate180()
            val np = b.editor.pending.mapTo(mutableSetOf()) { i ->
                (9 - i / 9) * 9 + (8 - i % 9)
            }
            b.editor.pending.clear()
            b.editor.pending.addAll(np)
            b.editor.invalidate()
        }
        b.btnTurn.setOnClickListener {
            val p = b.editor.position
            p.turn = if (p.turn == Side.RED) Side.BLACK else Side.RED
            updateTurnLabel()
        }
        b.btnSave.setOnClickListener { save(false) }
        b.btnExportOnly.setOnClickListener { save(true) }
    }

    private fun updateTurnLabel() {
        b.btnTurn.setText(
            if (b.editor.position.turn == Side.RED) com.xiangqiscan.app.R.string.editor_turn_red
            else com.xiangqiscan.app.R.string.editor_turn_black
        )
    }

    private fun buildPalette() {
        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            Triple("红方", Side.RED, listOf("帅", "仕", "相", "马", "车", "炮", "兵")),
            Triple("黑方", Side.BLACK, listOf("将", "士", "象", "马", "车", "炮", "卒"))
        )
        val dp = resources.displayMetrics.density
        for ((label, side, glyphs) in rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(Button(ctx).apply {
                text = label; textSize = 11f; isAllCaps = false
                isEnabled = false
            })
            val types = listOf(PieceType.KING, PieceType.ADVISOR, PieceType.ELEPHANT,
                PieceType.HORSE, PieceType.ROOK, PieceType.CANNON, PieceType.PAWN)
            for ((gi, g) in glyphs.withIndex()) {
                row.addView(Button(ctx).apply {
                    text = g; textSize = 15f
                    setOnClickListener {
                        val sel = b.editor.selected
                        if (sel >= 0) {
                            b.editor.position.set(sel / 9, sel % 9, Piece(side, types[gi]))
                            b.editor.pending.remove(sel)
                            // 自动跳到下一个待定点
                            val next = b.editor.pending.firstOrNull()
                            b.editor.selected = next ?: sel
                            b.editor.invalidate()
                        } else {
                            Toast.makeText(ctx, "先点棋盘上的交点", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            root.addView(row)
        }
        val row3 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row3.addView(Button(ctx).apply {
            text = "清除该点"; textSize = 12f
            setOnClickListener {
                val sel = b.editor.selected
                if (sel >= 0) {
                    b.editor.position.set(sel / 9, sel % 9, null)
                    b.editor.pending.remove(sel)
                    b.editor.invalidate()
                }
            }
        })
        root.addView(row3)
        b.paletteRow.removeAllViews()
        b.paletteRow.orientation = LinearLayout.VERTICAL
        b.paletteRow.addView(root)
    }

    private fun save(exportOnly: Boolean) {
        val pos = b.editor.position
        if (pos.isEmpty()) {
            Toast.makeText(this, "棋盘是空的", Toast.LENGTH_SHORT).show()
            return
        }
        if (b.editor.pending.isNotEmpty()) {
            Toast.makeText(this, "还有 ${b.editor.pending.size} 个\"?\"未指定棋子", Toast.LENGTH_SHORT).show()
            return
        }
        val errs = pos.validate()
        if (errs.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("局面提示")
                .setMessage(errs.joinToString("\n") + "\n\n仍要保存吗？")
                .setPositiveButton("仍要保存") { _, _ -> doSave(pos, exportOnly) }
                .setNegativeButton("返回修改", null)
                .show()
        } else {
            doSave(pos, exportOnly)
        }
    }

    private fun doSave(pos: Position, exportOnly: Boolean) {
        val res = Delivery.deliver(this, pos, prefs)
        Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
        if (res.ok && prefs.autoOpenChess && !exportOnly) {
            val i = packageManager.getLaunchIntentForPackage(Delivery.CHESS_PKG)
            if (i != null) startActivity(i)
        }
        if (res.ok) finish()
    }
}
