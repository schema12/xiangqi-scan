package com.zfdang.chess

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zfdang.chess.controllers.GameController

/**
 * 策略详细设置(第三级页面):α/β/γ 维度覆盖。
 * 只写 alphaOverride/betaOverride/gammaOverride 三个键,不覆盖第二级的任何设置。
 */
class StrategyDetailDialog : DialogFragment() {

    private lateinit var settings: Settings
    private lateinit var controller: GameController

    fun setController(controller: GameController) {
        this.controller = controller
        this.settings = controller.settings
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Chess_Dialog)
            .setBackground(AppCompatResources.getDrawable(requireContext(), R.drawable.ui_dialog_background))
        val view: View = android.view.LayoutInflater.from(builder.context)
            .inflate(R.layout.strategy_detail_dialog, null)

        val override = view.findViewById<CompoundButton>(R.id.boolean_advanced_override)
        val alphaBar = view.findViewById<SeekBar>(R.id.seekbar_alpha)
        val alphaText = view.findViewById<TextView>(R.id.textview_alpha)
        val betaBar = view.findViewById<SeekBar>(R.id.seekbar_beta)
        val betaText = view.findViewById<TextView>(R.id.textview_beta)
        val gammaBar = view.findViewById<SeekBar>(R.id.seekbar_gamma)
        val gammaText = view.findViewById<TextView>(R.id.textview_gamma)

        override.isChecked = settings.alphaOverride >= 0
        alphaBar.progress = if (settings.alphaOverride >= 0) settings.alphaOverride else 50
        betaBar.progress = if (settings.betaOverride >= 0) settings.betaOverride else 50
        gammaBar.progress = if (settings.gammaOverride >= 0) settings.gammaOverride else 50
        updateTexts(alphaText, betaText, gammaText, alphaBar, betaBar, gammaBar, override.isChecked)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateTexts(alphaText, betaText, gammaText, alphaBar, betaBar, gammaBar, override.isChecked)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }
        alphaBar.setOnSeekBarChangeListener(listener)
        betaBar.setOnSeekBarChangeListener(listener)
        gammaBar.setOnSeekBarChangeListener(listener)
        override.setOnCheckedChangeListener { _, checked ->
            updateTexts(alphaText, betaText, gammaText, alphaBar, betaBar, gammaBar, checked)
        }

        builder.setView(view)
            .setTitle("策略详细设置")
            .setPositiveButton("保存设置") { _, _ ->
                settings.alphaOverride = if (override.isChecked) alphaBar.progress else -1
                settings.betaOverride = if (override.isChecked) betaBar.progress else -1
                settings.gammaOverride = if (override.isChecked) gammaBar.progress else -1
                settings.saveSettings()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.cancel() }
        return builder.create()
    }

    private fun updateTexts(alphaView: TextView, betaView: TextView, gammaView: TextView,
                            alphaBar: SeekBar, betaBar: SeekBar, gammaBar: SeekBar,
                            enabled: Boolean) {
        if (!enabled) {
            alphaView.text = "自动"
            betaView.text = "自动"
            gammaView.text = "自动"
            return
        }
        alphaView.text = "${alphaBar.progress}%"
        betaView.text = "${betaBar.progress}%"
        gammaView.text = "${gammaBar.progress}%"
    }
}
