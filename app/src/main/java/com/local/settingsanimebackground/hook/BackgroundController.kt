package com.local.settingsanimebackground.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import com.local.settingsanimebackground.config.ModuleConfig
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

class BackgroundController private constructor(
    private val layer: BackgroundLayer,
    activity: Activity,
) {
    private val activityRef = WeakReference(activity)
    private val transparencyManager = TransparencyManager()
    private val systemBarManager = SystemBarManager(activity.window)
    private var appliedConfigVersion = Long.MIN_VALUE
    private var expectedBackgroundVersion = Long.MIN_VALUE
    private var blurRadiusPx = Float.NaN
    private var hasUsableBitmap = false

    fun apply(snapshot: RemoteSnapshot) {
        val activity = activityRef.get() ?: return
        val config = snapshot.config
        HookLogger.diagnosticEnabled = config.diagnosticLogging

        if (!config.enabled || !snapshot.hasBackground || ExcludedPageDetector.isExcluded(activity)) {
            remove(activity)
            return
        }

        if (appliedConfigVersion != config.configVersion) {
            transparencyManager.restoreAll()
            appliedConfigVersion = config.configVersion
        }
        expectedBackgroundVersion = config.backgroundVersion
        applyLayerVisuals(config)
        systemBarManager.apply(config)

        val weakActivity = WeakReference(activity)
        BackgroundBitmapStore.load(activity, config.backgroundVersion) { bitmap ->
            val currentActivity = weakActivity.get() ?: return@load
            if (currentActivity.isDestroyed || !layer.isAttachedToWindow) return@load
            if (expectedBackgroundVersion != config.backgroundVersion) return@load
            if (bitmap == null) {
                hasUsableBitmap = false
                RemoteConfigClient.reportFault(currentActivity, "背景图片解码失败")
                return@load
            }
            setBitmap(bitmap)
            transparencyManager.apply(
                currentActivity.window.decorView,
                layer,
                config.clearOpaqueBackgrounds,
                config.cardTransparencyEnabled,
                config.cardAlpha,
            )
        }

        if (hasUsableBitmap) {
            transparencyManager.apply(
                activity.window.decorView,
                layer,
                config.clearOpaqueBackgrounds,
                config.cardTransparencyEnabled,
                config.cardAlpha,
            )
        }
    }

    fun restore() {
        transparencyManager.restoreAll()
        systemBarManager.restore()
        clearBlur()
        layer.imageView.setImageDrawable(null)
        hasUsableBitmap = false
    }

    private fun setBitmap(bitmap: Bitmap) {
        if (layer.imageView.drawable !is android.graphics.drawable.BitmapDrawable ||
            (layer.imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap
        ) {
            layer.imageView.setImageBitmap(bitmap)
        }
        hasUsableBitmap = true
    }

    private fun applyLayerVisuals(config: ModuleConfig) {
        layer.imageView.alpha = config.opacity
        layer.dimView.alpha = config.dimAmount
        layer.imageView.scaleType = when (config.scaleType) {
            ModuleConfig.SCALE_FIT_CENTER -> ImageView.ScaleType.FIT_CENTER
            ModuleConfig.SCALE_FIT_XY -> ImageView.ScaleType.FIT_XY
            else -> ImageView.ScaleType.CENTER_CROP
        }
        applyBlur(config.blurRadius)
    }

    private fun applyBlur(radiusDp: Float) {
        val px = if (radiusDp <= 0f) 0f else {
            radiusDp * layer.resources.displayMetrics.density
        }
        if (!blurRadiusPx.isNaN() && abs(blurRadiusPx - px) < 0.1f) return
        try {
            if (px <= 0f) {
                layer.imageView.setRenderEffect(null)
            } else {
                layer.imageView.setRenderEffect(
                    RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP),
                )
            }
            blurRadiusPx = px
        } catch (error: Throwable) {
            blurRadiusPx = Float.NaN
            HookLogger.error("设备 RenderEffect 实现异常，已跳过模糊", error)
        }
    }

    private fun clearBlur() {
        try {
            layer.imageView.setRenderEffect(null)
        } catch (_: Throwable) {
            // 恢复流程不能影响 Settings 页面退出。
        }
        blurRadiusPx = Float.NaN
    }

    companion object {
        fun applyTo(activity: Activity, snapshot: RemoteSnapshot) {
            if (!snapshot.config.enabled ||
                !snapshot.hasBackground ||
                ExcludedPageDetector.isExcluded(activity)
            ) {
                remove(activity)
                return
            }
            val existing = activity.window.decorView
                .findViewWithTag<View>(BackgroundLayer.LAYER_TAG) as? BackgroundLayer
            val layer = existing ?: createLayer(activity) ?: return
            if (!isControllerInitialized(layer)) {
                layer.controller = BackgroundController(layer, activity)
            }
            layer.controller.apply(snapshot)
        }

        fun remove(activity: Activity) {
            val layer = activity.window.decorView
                .findViewWithTag<View>(BackgroundLayer.LAYER_TAG) as? BackgroundLayer
                ?: return
            if (isControllerInitialized(layer)) layer.controller.restore()
            (layer.parent as? ViewGroup)?.removeView(layer)
        }

        private fun createLayer(activity: Activity): BackgroundLayer? {
            val layer = BackgroundLayer(activity)
            val params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val decor = activity.window.decorView as? ViewGroup
            if (decor != null) {
                try {
                    decor.addView(layer, 0, params)
                    HookLogger.diagnostic("背景层已插入 DecorView：${activity.javaClass.name}")
                    return layer
                } catch (error: Throwable) {
                    HookLogger.diagnostic("DecorView 插入失败，尝试 content：${error.javaClass.simpleName}")
                }
            }

            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            return try {
                content.addView(
                    layer,
                    0,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                HookLogger.diagnostic("背景层已回退插入 android.R.id.content")
                layer
            } catch (error: Throwable) {
                HookLogger.error("背景层注入失败", error)
                RemoteConfigClient.reportFault(activity, "背景层注入失败：${error.javaClass.simpleName}")
                null
            }
        }

        private val isControllerInitialized: (BackgroundLayer) -> Boolean = { candidate ->
            try {
                candidate.controller
                true
            } catch (_: UninitializedPropertyAccessException) {
                false
            }
        }
    }
}

private class TransparencyManager {
    private data class OriginalState(
        val background: Drawable?,
        val alpha: Float,
    )

    private val originals = WeakHashMap<View, OriginalState>()

    fun apply(
        root: View,
        backgroundLayer: BackgroundLayer,
        clearOpaque: Boolean,
        cardsEnabled: Boolean,
        cardAlpha: Float,
    ) {
        visit(root, backgroundLayer, 0, clearOpaque, cardsEnabled, cardAlpha)
    }

    fun restoreAll() {
        originals.entries.toList().forEach { (view, state) ->
            try {
                view.background = state.background
                view.alpha = state.alpha
            } catch (_: Throwable) {
                // 已销毁或被系统替换的 View 直接跳过。
            }
        }
        originals.clear()
    }

    private fun visit(
        view: View,
        backgroundLayer: BackgroundLayer,
        depth: Int,
        clearOpaque: Boolean,
        cardsEnabled: Boolean,
        cardAlpha: Float,
    ) {
        if (view === backgroundLayer || depth > MAX_DEPTH) return
        val isCard = isCardView(view)
        if (isCard && cardsEnabled) {
            makeCardTransparent(view, cardAlpha)
        } else if (!isCard && clearOpaque) {
            clearSimpleOpaqueContainer(view)
        }

        if (view is ViewGroup && depth < MAX_DEPTH) {
            for (index in 0 until view.childCount) {
                visit(
                    view.getChildAt(index),
                    backgroundLayer,
                    depth + 1,
                    clearOpaque,
                    cardsEnabled,
                    cardAlpha,
                )
            }
        }
    }

    private fun clearSimpleOpaqueContainer(view: View) {
        if (!isCandidateContainer(view) || isInteractive(view) || originals.containsKey(view)) return
        val background = view.background as? ColorDrawable ?: return
        if (background.alpha != 255 || Color.alpha(background.color) != 255) return
        originals[view] = OriginalState(view.background, view.alpha)
        view.background = ColorDrawable(Color.TRANSPARENT)
        HookLogger.diagnostic("透明化纯色容器：${view.javaClass.name}")
    }

    private fun makeCardTransparent(view: View, alpha: Float) {
        if (originals.containsKey(view)) return
        val background = view.background ?: return
        if (!isSupportedCardDrawable(background)) {
            HookLogger.diagnostic(
                "跳过未知卡片 Drawable：${view.javaClass.name} / ${background.javaClass.name}",
            )
            return
        }
        val clone = try {
            background.constantState?.newDrawable(view.resources)?.mutate()
        } catch (_: Throwable) {
            null
        } ?: run {
            HookLogger.diagnostic("卡片 Drawable 无法安全复制，已跳过：${view.javaClass.name}")
            return
        }
        originals[view] = OriginalState(background, view.alpha)
        clone.alpha = (alpha.coerceIn(0.1f, 1f) * 255f).toInt()
        view.background = clone
        HookLogger.diagnostic("调整卡片透明度：${view.javaClass.name}")
    }

    private fun isCandidateContainer(view: View): Boolean {
        val name = view.javaClass.name.lowercase()
        return CANDIDATE_NAMES.any(name::contains) ||
            (name.contains("coui") && (name.contains("layout") || name.contains("recycler"))) ||
            (name.contains("oplus") && name.contains("layout"))
    }

    private fun isInteractive(view: View): Boolean {
        if (view.isClickable || view.isLongClickable || view.isFocusable) return true
        val name = view.javaClass.name.lowercase()
        return INTERACTIVE_NAMES.any(name::contains)
    }

    private fun isCardView(view: View): Boolean {
        val name = view.javaClass.name.lowercase()
        return name.contains("cardview") ||
            name.contains("materialcard") ||
            name.contains("opluscard") ||
            name.contains("couicard") ||
            name.contains("preferencecard")
    }

    private fun isSupportedCardDrawable(drawable: Drawable): Boolean =
        drawable is ColorDrawable ||
            drawable is GradientDrawable ||
            drawable is RippleDrawable ||
            drawable is LayerDrawable ||
            drawable is InsetDrawable

    companion object {
        private const val MAX_DEPTH = 4
        private val CANDIDATE_NAMES = listOf(
            "framelayout",
            "linearlayout",
            "relativelayout",
            "coordinatorlayout",
            "recyclerview",
            "nestedscrollview",
            "scrollview",
            "preferencerecyclerview",
        )
        private val INTERACTIVE_NAMES = listOf(
            "button",
            "edittext",
            "textfield",
            "switch",
            "checkbox",
            "radiobutton",
            "seekbar",
            "spinner",
            "toolbar",
            "imagebutton",
        )
    }
}

private class SystemBarManager(private val window: Window) {
    private var applied = false
    private var originalStatusBarColor = Color.TRANSPARENT
    private var originalNavigationBarColor = Color.TRANSPARENT
    private var originalAppearance = 0
    private var originalSystemUiVisibility = 0

    fun apply(config: ModuleConfig) {
        if (!config.extendIntoSystemBars) {
            restore()
            return
        }
        try {
            if (!applied) {
                originalStatusBarColor = window.statusBarColor
                originalNavigationBarColor = window.navigationBarColor
                originalSystemUiVisibility = window.decorView.systemUiVisibility
                originalAppearance = window.insetsController?.systemBarsAppearance ?: 0
                applied = true
            }
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            val darkIcons = when (config.statusBarIconMode) {
                ModuleConfig.ICON_LIGHT -> false
                ModuleConfig.ICON_DARK -> true
                else -> config.topLuminance * (1f - config.dimAmount) >= AUTO_DARK_ICON_THRESHOLD
            }
            val appearance = if (darkIcons) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            }
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } catch (error: Throwable) {
            HookLogger.error("应用系统栏样式失败", error)
        }
    }

    fun restore() {
        if (!applied) return
        try {
            window.statusBarColor = originalStatusBarColor
            window.navigationBarColor = originalNavigationBarColor
            window.decorView.systemUiVisibility = originalSystemUiVisibility
            window.insetsController?.setSystemBarsAppearance(
                originalAppearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } catch (error: Throwable) {
            HookLogger.error("恢复系统栏样式失败", error)
        } finally {
            applied = false
        }
    }

    companion object {
        private const val AUTO_DARK_ICON_THRESHOLD = 0.55f
    }
}
