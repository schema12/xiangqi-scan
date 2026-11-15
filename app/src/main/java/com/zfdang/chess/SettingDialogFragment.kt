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
import com.zfdang.chess.openbook.AggressiveOpenBook
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

    /** 二级弹窗保存/取消返回时刷新摘要与开局库提示(弹窗叠放不触发 onResume,须用 dismiss 回调)。 */
    override fun onResume() {
        super.onResume()
        refreshDynamicTexts()
    }

    private fun refreshDynamicTexts() {
        if (!this::settings.isInitialized) return
        view?.findViewById<TextView>(R.id.textview_strategy_summary)
            ?.text = strategySummaryText(settings)
        view?.findViewById<TextView>(R.id.textViewOpenBook)
            ?.text = bookHintText(settings)
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
        // 路由感知提示:显示当前模式实际使用的开局库(激进模式 = 激进专用库)
        textviewOpenbook.text = bookHintText(this.settings)

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

        // ---- 激进策略设置入口(二级弹窗) ----
        val strategyEntry = view.findViewById<View>(R.id.strategy_entry)
        val strategySummary = view.findViewById<TextView>(R.id.textview_strategy_summary)
        strategySummary.text = strategySummaryText(this.settings)

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
            strategySummary.text = strategySummaryText(this.settings)
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
        strategyEntry.setOnClickListener {
            val dialog = StrategySettingsDialog()
            dialog.setController(controller)
            // 二级弹窗关闭(保存或取消)时刷新一级页面的动态文本
            dialog.onDismissCallback = { refreshDynamicTexts() }
            dialog.show(parentFragmentManager, "StrategySettingsDialog")
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

    /** 一级入口的摘要文案:总开关状态 + 当前风险档位。 */
    private fun strategySummaryText(settings: Settings): String =
        if (settings.strategyEnabled) {
            "开启 · 风险 ${settings.riskLevel} · " + presetName(settings.riskLevel)
        } else {
            "关闭"
        }

    /**
     * 「使用开局库」下方提示:按当前路由显示实际使用的库(与 OpeningBookRouter 一致)。
     * 激进模式(Risk>0、激进库开、资产在)显示激进专用库;否则显示原版库状态。
     */
    private fun bookHintText(settings: Settings): String {
        val aggressiveAvailable = AggressiveOpenBook.assetExists(requireContext())
        val source = com.zfdang.chess.openbook.OpeningBookRouter.select(
            settings.strategyEnabled, settings.riskLevel,
            settings.aggressiveBookEnabled, aggressiveAvailable)
        return when (source) {
            com.zfdang.chess.openbook.OpeningBookRouter.Book.AGGRESSIVE ->
                "(当前使用激进专用开局库 aggressive_book_v1 · 已随包)"
            else -> if (BHDatabase.assetExists(requireContext())) {
                "(" + BHDatabase.OPENBOOK_NAME + ")"
            } else {
                "(无原版开局库:未随包提供,由引擎直接搜索)"
            }
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