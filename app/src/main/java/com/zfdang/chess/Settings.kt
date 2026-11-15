package com.zfdang.chess

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.Properties

class Settings(private val context: Context) {
    private val properties = Properties()

    companion object {
        private const val FILE_NAME = "settings.ini"
    }

    private val openbook_key: String = "openbook"
    var openbook = true

    private val sound_effect_key: String = "sound_effect"
    var sound_effect = false
    private val history_moves_key: String = "history_moves"
    var history_moves = 0
    private val go_depth_key: String = "go_depth"
    var go_depth = false
    private val go_depth_value_key: String = "go_depth_value"
    var go_depth_value = 0
    private val go_time_key: String = "go_time"
    var go_time = false
    private val go_time_value_key: String = "go_time_value"
    var go_time_value = 0
    private val go_infinite_key: String = "go_infinite"
    var go_infinite = false

    private val red_go_first_key: String = "red_go_first"
    var red_go_first = true

    private val hash_size_key: String = "hash_size"
    var hash_size = 512

    private val random_move_key: String = "random_move"
    var random_move = false

    // ---- aggressive-strategy layer (see docs/aggressive-ai.md) ----
    // risk_level 0 keeps the app exactly on the original Pikafish bestmove path.
    private val risk_level_key: String = "risk_level"
    var riskLevel = 0
    private val strength_guard_key: String = "strength_guard"
    var strengthGuard = true
    private val show_wdl_key: String = "show_wdl"
    var showWdl = true
    private val thinking_mode_key: String = "thinking_mode"
    var thinkingMode = "A"
    // advanced per-dimension overrides: -1 = follow the risk slider, 0..100 = explicit
    private val alpha_override_key: String = "alpha_override"
    var alphaOverride = -1
    private val beta_override_key: String = "beta_override"
    var betaOverride = -1
    private val gamma_override_key: String = "gamma_override"
    var gammaOverride = -1

    // 总开关:新安装默认关闭,升级后原版对弈体验不变
    private val strategy_enabled_key: String = "strategy_enabled"
    var strategyEnabled = false
    // 独立功能开关(总开关关闭时全部不生效,但配置保留)
    private val candidate_search_key: String = "candidate_search_enabled"
    var candidateSearchEnabled = true
    private val attack_features_key: String = "attack_features_enabled"
    var attackFeaturesEnabled = true
    private val sacrifice_key: String = "sacrifice_analysis_enabled"
    var sacrificeAnalysisEnabled = true
    private val aggressive_book_key: String = "aggressive_book_enabled"
    var aggressiveBookEnabled = true
    private val opening_style_key: String = "opening_style_enabled"
    var openingStyleEnabled = true
    private val diversity_key: String = "diversity_enabled"
    var diversityEnabled = true

    init {
        loadSettings()

        // create variables for settings
        openbook = getBoolean(openbook_key, true)
        sound_effect = getBoolean(sound_effect_key, true)
        history_moves = getInt(history_moves_key, 2)
        go_depth = getBoolean(go_depth_key, true)
        go_depth_value = getInt(go_depth_value_key, 20)
        go_time = getBoolean(go_time_key, false)
        go_time_value = getInt(go_time_value_key, 3000)
        go_infinite = getBoolean(go_infinite_key, false)
        red_go_first = getBoolean(red_go_first_key, true)
        hash_size = getInt(hash_size_key, 256)
        random_move = getBoolean(random_move_key, false)
        riskLevel = getInt(risk_level_key, 0)
        strengthGuard = getBoolean(strength_guard_key, true)
        showWdl = getBoolean(show_wdl_key, true)
        thinkingMode = getString(thinking_mode_key, "A")
        alphaOverride = getInt(alpha_override_key, -1)
        betaOverride = getInt(beta_override_key, -1)
        gammaOverride = getInt(gamma_override_key, -1)
        strategyEnabled = getBoolean(strategy_enabled_key, false)
        candidateSearchEnabled = getBoolean(candidate_search_key, true)
        attackFeaturesEnabled = getBoolean(attack_features_key, true)
        sacrificeAnalysisEnabled = getBoolean(sacrifice_key, true)
        aggressiveBookEnabled = getBoolean(aggressive_book_key, true)
        openingStyleEnabled = getBoolean(opening_style_key, true)
        diversityEnabled = getBoolean(diversity_key, true)
    }

    private fun loadSettings() {
        try {
            context.openFileInput(Settings.Companion.FILE_NAME).use { fis ->
                properties.load(fis)
            }
        } catch (e: IOException) {
            Log.e("SettingsManager", "Failed to load settings: " + e.message)
        }
    }


    fun saveSettings() {
        // save variables for settings
        setBoolean(openbook_key, openbook)
        setBoolean(sound_effect_key, sound_effect)
        setInt(history_moves_key, history_moves)
        setBoolean(go_depth_key, go_depth)
        setInt(go_depth_value_key, go_depth_value)
        setBoolean(go_time_key, go_time)
        setInt(go_time_value_key, go_time_value)
        setBoolean(go_infinite_key, go_infinite)
        setBoolean(red_go_first_key, red_go_first)
        setInt(hash_size_key, hash_size)
        setBoolean(random_move_key, random_move)
        setInt(risk_level_key, riskLevel)
        setBoolean(strength_guard_key, strengthGuard)
        setBoolean(show_wdl_key, showWdl)
        setString(thinking_mode_key, thinkingMode)
        setInt(alpha_override_key, alphaOverride)
        setInt(beta_override_key, betaOverride)
        setInt(gamma_override_key, gammaOverride)
        setBoolean(strategy_enabled_key, strategyEnabled)
        setBoolean(candidate_search_key, candidateSearchEnabled)
        setBoolean(attack_features_key, attackFeaturesEnabled)
        setBoolean(sacrifice_key, sacrificeAnalysisEnabled)
        setBoolean(aggressive_book_key, aggressiveBookEnabled)
        setBoolean(opening_style_key, openingStyleEnabled)
        setBoolean(diversity_key, diversityEnabled)

        // save to file
        try {
            context.openFileOutput(Settings.Companion.FILE_NAME, Context.MODE_PRIVATE).use { fos ->
                properties.store(fos, null)
            }
        } catch (e: IOException) {
            Log.e("SettingsManager", "Failed to save settings" + e.message)
        }
    }

    fun getGoCmd(): String {
        var goCmd = "go"
        if (go_depth) {
            goCmd += " depth $go_depth_value"
        } else if (go_time) {
            goCmd += " movetime $go_time_value"
        } else if (go_infinite) {
            goCmd += " infinite"
        }
        return goCmd
    }

    fun getString(key: String?, defaultValue: String?): String {
        return properties.getProperty(key, defaultValue)
    }

    fun setString(key: String?, value: String?) {
        properties.setProperty(key, value)
    }

    fun getInt(key: String?, defaultValue: Int): Int {
        return try {
            properties.getProperty(key, defaultValue.toString()).toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    fun setInt(key: String?, value: Int) {
        properties.setProperty(key, value.toString())
    }

    fun getBoolean(key: String?, defaultValue: Boolean): Boolean {
        return properties.getProperty(key, defaultValue.toString()).toBoolean()
    }

    fun setBoolean(key: String?, value: Boolean) {
        properties.setProperty(key, value.toString())
    }


}