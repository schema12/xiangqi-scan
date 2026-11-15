package com.zfdang.chess

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.DialogFragment
import com.zfdang.chess.controllers.GameController
import com.zfdang.chess.openbook.BHDatabase

class SettingDialogFragment : DialogFragment() {
    interface SettingDialogListener {
        fun onDialogPositiveClick()
        fun onDialogNegativeClick()
    }

    private lateinit var settings: Settings
    private lateinit var controller: GameController
    private lateinit var engineInfo: String
    var listener: SettingDialogListener? = null

    fun setController(controller: GameController) {
        this.controller = controller
        this.settings = controller.settings
    }

    fun setEngineInfo(info: String) {
        engineInfo = info
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Chess_Dialog)
            .setBackground(AppCompatResources.getDrawable(requireContext(), R.drawable.ui_dialog_background))
        val inflater = android.view.LayoutInflater.from(builder.context)
        val view: View = inflater.inflate(R.layout.setting_dialog, null)

        // bind all items in setting_dialog.xml
        val engineInfoTV = view.findViewById<TextView>(R.id.textView_engine_info)
        // limit the length of engineInfo
        if (engineInfo.length > 22) {
            engineInfoTV.text = "引擎: " + engineInfo.substring(0, 22) + ".."
        } else {
            engineInfoTV.text = "引擎: " + engineInfo
        }

        val booleanOpenbook = view.findViewById<CompoundButton>(R.id.boolean_openbook)
        val textviewOpenbook = view.findViewById<TextView>(R.id.textViewOpenBook)
        // 修复静默失败:无库时明示,而不是后台无限懒重试
        textviewOpenbook.text = if (BHDatabase.assetExists(requireContext())) {
            "(" + BHDatabase.OPENBOOK_NAME + ")"
        } else {
            "(无开局库:未随包提供,由引擎直接搜索)"
        }

        val booleanSound = view.findViewById<CompoundButton>(R.id.boolean_sound)

        val historyInput = view.findViewById<SeekBar>(R.id.seekbar_history)
        val historyText = view.findViewById<TextView>(R.id.textview_history)

        val radioDepth = view.findViewById<RadioButton>(R.id.radioButton_depth)
        val depthInput = view.findViewById<SeekBar>(R.id.seekBar_depth)
        val depthText = view.findViewById<TextView>(R.id.textView_depth)

        val radioTime = view.findViewById<RadioButton>(R.id.radioButton_time)
        val timeInput = view.findViewById<SeekBar>(R.id.seekBar_time)
        val timeText = view.findViewById<TextView>(R.id.textView_time)

        val radioInfinite = view.findViewById<RadioButton>(R.id.radioButton_infinite)

        val redgoFirst = view.findViewById<CompoundButton>(R.id.boolean_redgofirst)

        val randomMove = view.findViewById<CompoundButton>(R.id.boolean_randommove)

        val seekbarHash = view.findViewById<SeekBar>(R.id.seekbar_hash)
        val textviewHash = view.findViewById<TextView>(R.id.textview_hash)

        // ---- 激进策略区 ----
        val seekbarRisk = view.findViewById<SeekBar>(R.id.seekbar_risk)
        val textviewRisk = view.findViewById<TextView>(R.id.textview_risk)
        val guardSwitch = view.findViewById<CompoundButton>(R.id.boolean_strength_guard)
        val advancedSwitch = view.findViewById<CompoundButton>(R.id.boolean_advanced_override)
        val seekbarAlpha = view.findViewById<SeekBar>(R.id.seekbar_alpha)
        val textviewAlpha = view.findViewById<TextView>(R.id.textview_alpha)
        val seekbarBeta = view.findViewById<SeekBar>(R.id.seekbar_beta)
        val textviewBeta = view.findViewById<TextView>(R.id.textview_beta)
        val seekbarGamma = view.findViewById<SeekBar>(R.id.seekbar_gamma)
        val textviewGamma = view.findViewById<TextView>(R.id.textview_gamma)

        // set the initial values
        if (this.settings != null) {
            booleanOpenbook.isChecked = this.settings.openbook
            booleanSound.isChecked = this.settings.sound_effect
            historyInput.progress = this.settings.history_moves
            historyText.text = this.settings.history_moves.toString()

            depthInput.progress = this.settings.go_depth_value
            depthText.text = this.settings.go_depth_value.toString()
            timeInput.progress = this.settings.go_time_value
            timeText.text = this.settings.go_time_value.toString()
            if (this.settings.go_depth) {
                radioDepth.isChecked = true
            } else if (this.settings.go_time) {
                radioTime.isChecked = true
            } else if (this.settings.go_infinite) {
                radioInfinite.isChecked = true
            }
            redgoFirst.isChecked = this.settings.red_go_first
            seekbarHash.progress = this.settings.hash_size
            textviewHash.text = this.settings.hash_size.toString()
            randomMove.isChecked = this.settings.random_move
            seekbarRisk.progress = this.settings.riskLevel
            textviewRisk.text = "${this.settings.riskLevel} · " + presetName(this.settings.riskLevel)
            guardSwitch.isChecked = this.settings.strengthGuard
            advancedSwitch.isChecked = this.settings.alphaOverride >= 0
            seekbarAlpha.progress = if (this.settings.alphaOverride >= 0) this.settings.alphaOverride else 50
            seekbarBeta.progress = if (this.settings.betaOverride >= 0) this.settings.betaOverride else 50
            seekbarGamma.progress = if (this.settings.gammaOverride >= 0) this.settings.gammaOverride else 50
            updateAdvancedTexts(textviewAlpha, textviewBeta, textviewGamma,
                    seekbarAlpha, seekbarBeta, seekbarGamma, advancedSwitch.isChecked)
        }

        historyInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                historyText.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        depthInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                depthText.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        timeInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                timeText.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        seekbarHash.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                textviewHash.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        seekbarRisk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                textviewRisk.text = "$progress · " + presetName(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        val advancedListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateAdvancedTexts(textviewAlpha, textviewBeta, textviewGamma,
                        seekbarAlpha, seekbarBeta, seekbarGamma, advancedSwitch.isChecked)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }
        seekbarAlpha.setOnSeekBarChangeListener(advancedListener)
        seekbarBeta.setOnSeekBarChangeListener(advancedListener)
        seekbarGamma.setOnSeekBarChangeListener(advancedListener)
        advancedSwitch.setOnCheckedChangeListener { _, checked ->
            updateAdvancedTexts(textviewAlpha, textviewBeta, textviewGamma,
                    seekbarAlpha, seekbarBeta, seekbarGamma, checked)
        }


        builder.setView(view)
            .setTitle("对弈设置")
            .setPositiveButton("保存设置") { dialog, id ->
                settings.openbook = booleanOpenbook.isChecked
                settings.sound_effect = booleanSound.isChecked
                settings.history_moves = historyInput.progress
                settings.go_depth = radioDepth.isChecked
                settings.go_depth_value = depthInput.progress
                settings.go_time = radioTime.isChecked
                settings.go_time_value = timeInput.progress
                settings.go_infinite = radioInfinite.isChecked
                settings.red_go_first = redgoFirst.isChecked
                settings.hash_size = seekbarHash.progress
                settings.random_move = randomMove.isChecked
                settings.riskLevel = seekbarRisk.progress
                settings.strengthGuard = guardSwitch.isChecked
                // 关闭高级覆盖时写 -1,策略层自动跟随风险滑块的连续曲线
                settings.alphaOverride = if (advancedSwitch.isChecked) seekbarAlpha.progress else -1
                settings.betaOverride = if (advancedSwitch.isChecked) seekbarBeta.progress else -1
                settings.gammaOverride = if (advancedSwitch.isChecked) seekbarGamma.progress else -1
                settings.saveSettings()
                controller.applyEngineSetting()
                listener?.onDialogPositiveClick()
            }
            .setNegativeButton("取消") { dialog, id ->
                listener?.onDialogNegativeClick()
                dialog.cancel()
            }

        return builder.create()
    }

    private fun presetName(risk: Int): String = when {
        risk <= 20 -> "稳健"
        risk <= 40 -> "均衡"
        risk <= 60 -> "主动进攻"
        risk <= 85 -> "激进争胜"
        else -> "极端冒险"
    }

    private fun updateAdvancedTexts(alphaView: TextView, betaView: TextView, gammaView: TextView,
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