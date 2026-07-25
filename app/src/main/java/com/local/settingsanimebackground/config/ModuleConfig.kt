package com.local.settingsanimebackground.config

import android.os.Bundle
import kotlin.math.roundToInt

data class ModuleConfig(
    val enabled: Boolean = true,
    val opacity: Float = 1f,
    val dimAmount: Float = 0.25f,
    val blurRadius: Float = 0f,
    val scaleType: Int = SCALE_CENTER_CROP,
    val clearOpaqueBackgrounds: Boolean = true,
    val cardTransparencyEnabled: Boolean = false,
    val cardAlpha: Float = 0.75f,
    val extendIntoSystemBars: Boolean = true,
    val statusBarIconMode: Int = ICON_AUTO,
    val diagnosticLogging: Boolean = false,
    val topLuminance: Float = 0.5f,
    val backgroundVersion: Long = 0L,
    val configVersion: Long = 0L,
) {
    fun toBundle(hasBackground: Boolean): Bundle = Bundle().apply {
        putBoolean(KEY_ENABLED, enabled)
        putFloat(KEY_OPACITY, opacity)
        putFloat(KEY_DIM_AMOUNT, dimAmount)
        putFloat(KEY_BLUR_RADIUS, blurRadius)
        putInt(KEY_SCALE_TYPE, scaleType)
        putBoolean(KEY_CLEAR_OPAQUE, clearOpaqueBackgrounds)
        putBoolean(KEY_CARD_ENABLED, cardTransparencyEnabled)
        putFloat(KEY_CARD_ALPHA, cardAlpha)
        putBoolean(KEY_EXTEND_BARS, extendIntoSystemBars)
        putInt(KEY_ICON_MODE, statusBarIconMode)
        putBoolean(KEY_DIAGNOSTIC, diagnosticLogging)
        putFloat(KEY_TOP_LUMINANCE, topLuminance)
        putLong(KEY_BACKGROUND_VERSION, backgroundVersion)
        putLong(KEY_CONFIG_VERSION, configVersion)
        putBoolean(KEY_HAS_BACKGROUND, hasBackground)
    }

    companion object {
        const val SCALE_CENTER_CROP = 0
        const val SCALE_FIT_CENTER = 1
        const val SCALE_FIT_XY = 2

        const val ICON_AUTO = 0
        const val ICON_LIGHT = 1
        const val ICON_DARK = 2

        const val KEY_ENABLED = "enabled"
        const val KEY_OPACITY = "opacity"
        const val KEY_DIM_AMOUNT = "dimAmount"
        const val KEY_BLUR_RADIUS = "blurRadius"
        const val KEY_SCALE_TYPE = "scaleType"
        const val KEY_CLEAR_OPAQUE = "clearOpaqueBackgrounds"
        const val KEY_CARD_ENABLED = "cardTransparencyEnabled"
        const val KEY_CARD_ALPHA = "cardAlpha"
        const val KEY_EXTEND_BARS = "extendIntoSystemBars"
        const val KEY_ICON_MODE = "statusBarIconMode"
        const val KEY_DIAGNOSTIC = "diagnosticLogging"
        const val KEY_TOP_LUMINANCE = "topLuminance"
        const val KEY_BACKGROUND_VERSION = "backgroundVersion"
        const val KEY_CONFIG_VERSION = "configVersion"
        const val KEY_HAS_BACKGROUND = "hasBackground"

        fun fromBundle(bundle: Bundle): ModuleConfig = ModuleConfig(
            enabled = bundle.getBoolean(KEY_ENABLED, true),
            opacity = bundle.getFloat(KEY_OPACITY, 1f).coerceIn(0.1f, 1f),
            dimAmount = bundle.getFloat(KEY_DIM_AMOUNT, 0.25f).coerceIn(0f, 0.8f),
            blurRadius = bundle.getFloat(KEY_BLUR_RADIUS, 0f).coerceIn(0f, 30f),
            scaleType = bundle.getInt(KEY_SCALE_TYPE, SCALE_CENTER_CROP).coerceIn(0, 2),
            clearOpaqueBackgrounds = bundle.getBoolean(KEY_CLEAR_OPAQUE, true),
            cardTransparencyEnabled = bundle.getBoolean(KEY_CARD_ENABLED, false),
            cardAlpha = bundle.getFloat(KEY_CARD_ALPHA, 0.75f).coerceIn(0.1f, 1f),
            extendIntoSystemBars = bundle.getBoolean(KEY_EXTEND_BARS, true),
            statusBarIconMode = bundle.getInt(KEY_ICON_MODE, ICON_AUTO).coerceIn(0, 2),
            diagnosticLogging = bundle.getBoolean(KEY_DIAGNOSTIC, false),
            topLuminance = bundle.getFloat(KEY_TOP_LUMINANCE, 0.5f).coerceIn(0f, 1f),
            backgroundVersion = bundle.getLong(KEY_BACKGROUND_VERSION, 0L),
            configVersion = bundle.getLong(KEY_CONFIG_VERSION, 0L),
        )

        fun percent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).roundToInt()
    }
}
