package com.zfdang.chess.views

import android.app.Dialog
import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zfdang.chess.R

/**
 * 棋局设置弹窗（对弈页「棋局设置/新局」与识图页「校准完成」共用）。
 * 三项均可点击文本切换：先手方(红方/黑方)、红方(棋手/电脑)、黑方(棋手/电脑)。
 */
class GameSetupDialog(
    private val context: Context,
    redFirstInit: Boolean,
    redHumanInit: Boolean,
    blackHumanInit: Boolean,
    private val onConfirm: (redFirst: Boolean, redHuman: Boolean, blackHuman: Boolean) -> Unit
) {
    private var redFirst = redFirstInit
    private var redHuman = redHumanInit
    private var blackHuman = blackHumanInit

    fun show() {
        val d = Dialog(context)
        d.setContentView(R.layout.dialog_game_setup)
        d.setCancelable(true)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvRedFirst = d.findViewById<TextView>(R.id.tvRedFirst)
        val tvRedSide = d.findViewById<TextView>(R.id.tvRedSide)
        val tvBlackSide = d.findViewById<TextView>(R.id.tvBlackSide)

        fun refresh() {
            tvRedFirst.text = if (redFirst) "红方" else "黑方"
            tvRedFirst.setTextColor(
                if (redFirst) ContextCompat.getColor(context, R.color.ui_red)
                else ContextCompat.getColor(context, R.color.ui_ink)
            )
            tvRedSide.text = if (redHuman) "棋手" else "电脑"
            tvBlackSide.text = if (blackHuman) "棋手" else "电脑"
        }

        tvRedFirst.setOnClickListener { redFirst = !redFirst; refresh() }
        tvRedSide.setOnClickListener { redHuman = !redHuman; refresh() }
        tvBlackSide.setOnClickListener { blackHuman = !blackHuman; refresh() }

        d.findViewById<TextView>(R.id.btnCancel).setOnClickListener { d.dismiss() }
        d.findViewById<TextView>(R.id.btnConfirm).setOnClickListener {
            d.dismiss()
            onConfirm(redFirst, redHuman, blackHuman)
        }

        refresh()
        d.show()
    }
}
