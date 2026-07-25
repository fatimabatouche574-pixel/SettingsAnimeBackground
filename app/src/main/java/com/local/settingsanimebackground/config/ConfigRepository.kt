package com.local.settingsanimebackground.config

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class ConfigRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ModuleConfig = ModuleConfig(
        enabled = prefs.getBoolean(ModuleConfig.KEY_ENABLED, true),
        opacity = prefs.getFloat(ModuleConfig.KEY_OPACITY, 1f).coerceIn(0.1f, 1f),
        dimAmount = prefs.getFloat(ModuleConfig.KEY_DIM_AMOUNT, 0.25f).coerceIn(0f, 0.8f),
        blurRadius = prefs.getFloat(ModuleConfig.KEY_BLUR_RADIUS, 0f).coerceIn(0f, 30f),
        scaleType = prefs.getInt(ModuleConfig.KEY_SCALE_TYPE, 0).coerceIn(0, 2),
        clearOpaqueBackgrounds = prefs.getBoolean(ModuleConfig.KEY_CLEAR_OPAQUE, true),
        cardTransparencyEnabled = prefs.getBoolean(ModuleConfig.KEY_CARD_ENABLED, false),
        cardAlpha = prefs.getFloat(ModuleConfig.KEY_CARD_ALPHA, 0.75f).coerceIn(0.1f, 1f),
        extendIntoSystemBars = prefs.getBoolean(ModuleConfig.KEY_EXTEND_BARS, true),
        statusBarIconMode = prefs.getInt(ModuleConfig.KEY_ICON_MODE, 0).coerceIn(0, 2),
        diagnosticLogging = prefs.getBoolean(ModuleConfig.KEY_DIAGNOSTIC, false),
        topLuminance = prefs.getFloat(ModuleConfig.KEY_TOP_LUMINANCE, 0.5f).coerceIn(0f, 1f),
        backgroundVersion = prefs.getLong(ModuleConfig.KEY_BACKGROUND_VERSION, 0L),
        configVersion = prefs.getLong(ModuleConfig.KEY_CONFIG_VERSION, 0L),
    )

    @Synchronized
    fun update(block: (MutableConfig) -> Unit) {
        val current = read()
        val mutable = MutableConfig(current)
        block(mutable)
        mutable.writeTo(prefs.edit())
            .putLong(ModuleConfig.KEY_CONFIG_VERSION, current.configVersion + 1L)
            .apply()
        notifyConfigChanged()
    }

    @Synchronized
    fun backgroundChanged(topLuminance: Float) {
        val current = read()
        prefs.edit()
            .putFloat(ModuleConfig.KEY_TOP_LUMINANCE, topLuminance.coerceIn(0f, 1f))
            .putLong(ModuleConfig.KEY_BACKGROUND_VERSION, current.backgroundVersion + 1L)
            .putLong(ModuleConfig.KEY_CONFIG_VERSION, current.configVersion + 1L)
            .apply()
        appContext.contentResolver.notifyChange(ConfigContract.BACKGROUND_URI, null)
        notifyConfigChanged()
    }

    @Synchronized
    fun deleteBackground(): Boolean {
        val deleted = !backgroundFile().exists() || backgroundFile().delete()
        if (deleted) backgroundChanged(0.5f)
        return deleted
    }

    @Synchronized
    fun restoreDefaults() {
        val current = read()
        prefs.edit()
            .clear()
            .putLong(ModuleConfig.KEY_BACKGROUND_VERSION, current.backgroundVersion)
            .putLong(ModuleConfig.KEY_CONFIG_VERSION, current.configVersion + 1L)
            .apply()
        notifyConfigChanged()
    }

    fun backgroundFile(): File = File(appContext.filesDir, BACKGROUND_FILE_NAME)

    fun hasBackground(): Boolean = backgroundFile().isFile

    fun reportFault(message: String) {
        prefs.edit().putString(KEY_LAST_FAULT, message.take(MAX_FAULT_LENGTH)).apply()
    }

    private fun notifyConfigChanged() {
        appContext.contentResolver.notifyChange(ConfigContract.CONFIG_URI, null)
    }

    class MutableConfig(config: ModuleConfig) {
        var enabled = config.enabled
        var opacity = config.opacity
        var dimAmount = config.dimAmount
        var blurRadius = config.blurRadius
        var scaleType = config.scaleType
        var clearOpaqueBackgrounds = config.clearOpaqueBackgrounds
        var cardTransparencyEnabled = config.cardTransparencyEnabled
        var cardAlpha = config.cardAlpha
        var extendIntoSystemBars = config.extendIntoSystemBars
        var statusBarIconMode = config.statusBarIconMode
        var diagnosticLogging = config.diagnosticLogging

        internal fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor
            .putBoolean(ModuleConfig.KEY_ENABLED, enabled)
            .putFloat(ModuleConfig.KEY_OPACITY, opacity.coerceIn(0.1f, 1f))
            .putFloat(ModuleConfig.KEY_DIM_AMOUNT, dimAmount.coerceIn(0f, 0.8f))
            .putFloat(ModuleConfig.KEY_BLUR_RADIUS, blurRadius.coerceIn(0f, 30f))
            .putInt(ModuleConfig.KEY_SCALE_TYPE, scaleType.coerceIn(0, 2))
            .putBoolean(ModuleConfig.KEY_CLEAR_OPAQUE, clearOpaqueBackgrounds)
            .putBoolean(ModuleConfig.KEY_CARD_ENABLED, cardTransparencyEnabled)
            .putFloat(ModuleConfig.KEY_CARD_ALPHA, cardAlpha.coerceIn(0.1f, 1f))
            .putBoolean(ModuleConfig.KEY_EXTEND_BARS, extendIntoSystemBars)
            .putInt(ModuleConfig.KEY_ICON_MODE, statusBarIconMode.coerceIn(0, 2))
            .putBoolean(ModuleConfig.KEY_DIAGNOSTIC, diagnosticLogging)
    }

    companion object {
        const val BACKGROUND_FILE_NAME = "background.webp"
        private const val PREFS_NAME = "module_config"
        private const val KEY_LAST_FAULT = "lastFault"
        private const val MAX_FAULT_LENGTH = 1000
    }
}
