package com.zfdang.chess

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.content.res.AppCompatResources
import com.zfdang.chess.controllers.GameController

/**
 * 激进策略设置(第二级页面)。总开关 + 功能开关 + Risk/棋力保护;
 * 「策略详细设置」打开第三级(α/β/γ 维度覆盖)。
 * 只写入自己的设置键,与第三级互不覆盖;保存后通知控制器,
 * 以便总开关被改到关闭时使进行中的激进决策安全失效。
 */
class StrategySettingsDialog : DialogFragment() {

    private lateinit var settings: Settings
    private lateinit var controller: GameController

    /** 弹窗关闭(保存或取消)时回调宿主,用于刷新一级页面的摘要/提示文本。 */
    var onDismissCallback: (() -> Unit)? = null

    fun setController(controller: GameController) {
        this.controller = controller
        this.settings = controller.settings
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Chess_Dialog)
            .setBackground(AppCompatResources.getDrawable(requireContext(), R.drawable.ui_dialog_background))
        val view: View = android.view.LayoutInflater.from(builder.context)
            .inflate(R.layout.strategy_settings_dialog, null)
        debugMirror("L2 created settings@" + System.identityHashCode(settings) +
            " strat=" + settings.strategyEnabled + " risk=" + settings.riskLevel)

        val master = view.findViewById<CompoundButton>(R.id.boolean_strategy_enabled)
        val riskBar = view.findViewById<SeekBar>(R.id.seekbar_risk)
        val riskText = view.findViewById<TextView>(R.id.textview_risk)
        val guard = view.findViewById<CompoundButton>(R.id.boolean_strength_guard)
        val candidate = view.findViewById<CompoundButton>(R.id.boolean_candidate_search)
        val wdl = view.findViewById<CompoundButton>(R.id.boolean_wdl)
        val attack = view.findViewById<CompoundButton>(R.id.boolean_attack)
        val sacrifice = view.findViewById<CompoundButton>(R.id.boolean_sacrifice)
        val aggressiveBook = view.findViewById<CompoundButton>(R.id.boolean_aggressive_book)
        val openingStyle = view.findViewById<CompoundButton>(R.id.boolean_opening_style)
        val diversity = view.findViewById<CompoundButton>(R.id.boolean_diversity)
        val detailEntry = view.findViewById<TextView>(R.id.textview_detail_entry)

        master.isChecked = settings.strategyEnabled
        riskBar.progress = settings.riskLevel
        riskText.text = "${settings.riskLevel} · " + presetName(settings.riskLevel)
        guard.isChecked = settings.strengthGuard
        candidate.isChecked = settings.candidateSearchEnabled
        wdl.isChecked = settings.showWdl
        attack.isChecked = settings.attackFeaturesEnabled
        sacrifice.isChecked = settings.sacrificeAnalysisEnabled
        aggressiveBook.isChecked = settings.aggressiveBookEnabled
        openingStyle.isChecked = settings.openingStyleEnabled
        diversity.isChecked = settings.diversityEnabled

        riskBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                riskText.text = "$progress · " + presetName(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        detailEntry.setOnClickListener {
            val detail = StrategyDetailDialog()
            detail.setController(controller)
            detail.show(parentFragmentManager, "StrategyDetailDialog")
        }

        builder.setView(view)
            .setTitle("激进策略设置")
            .setPositiveButton("保存设置") { _, _ ->
                settings.strategyEnabled = master.isChecked
                settings.riskLevel = riskBar.progress
                settings.strengthGuard = guard.isChecked
                settings.candidateSearchEnabled = candidate.isChecked
                settings.showWdl = wdl.isChecked
                settings.attackFeaturesEnabled = attack.isChecked
                settings.sacrificeAnalysisEnabled = sacrifice.isChecked
                settings.aggressiveBookEnabled = aggressiveBook.isChecked
                settings.openingStyleEnabled = openingStyle.isChecked
                settings.diversityEnabled = diversity.isChecked
                settings.saveSettings()
                debugMirror("L2 saved settings@" + System.identityHashCode(settings) +
                    " strat=" + settings.strategyEnabled)
                // 总开关被改到关闭时,使进行中的激进决策安全失效并回退基线
                controller.applyEngineSetting()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.cancel() }
        return builder.create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        debugMirror("L2 dismissed")
        onDismissCallback?.invoke()
    }

    private fun debugMirror(msg: String) {
        try {
            val dir = requireContext().getExternalFilesDir(null) ?: return
            java.io.File(dir, "ui_debug.log").appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date()) + " " + msg + "\n")
        } catch (ignored: Throwable) {
        }
    }

    private fun presetName(risk: Int): String = when {
        risk <= 20 -> "稳健"
        risk <= 40 -> "均衡"
        risk <= 60 -> "主动进攻"
        risk <= 85 -> "激进争胜"
        else -> "极端冒险"
    }
}
