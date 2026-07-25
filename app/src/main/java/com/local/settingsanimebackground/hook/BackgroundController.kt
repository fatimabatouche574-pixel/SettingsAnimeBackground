package com.local.settingsanimebackground.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import java.lang.reflect.Field
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

class BackgroundController private constructor(
    private val layer: BackgroundLayer,
    activity: Activity,
) {
    private val activityRef = WeakReference(activity)
    private val transparencyManager = TransparencyManager()
    private val systemBarManager = SystemBarManager(activity.window)
    private var appliedConfigVersion = Long.MIN_VALUE
    private var expectedBackgroundVersion = Long.MIN_VALUE
    private var expectedConfigVersion = Long.MIN_VALUE
    private var blurRadiusPx = Float.NaN
    private var hasUsableBitmap = false
    private var currentConfig: ModuleConfig? = null

    fun apply(snapshot: RemoteSnapshot) {
        val activity = activityRef.get() ?: return
        val config = snapshot.config
        currentConfig = config
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
        expectedConfigVersion = config.configVersion
        applyLayerVisuals(config)
        systemBarManager.apply(config)

        val weakActivity = WeakReference(activity)
        BackgroundBitmapStore.load(activity, config.backgroundVersion) { bitmap ->
            val currentActivity = weakActivity.get() ?: return@load
            if (currentActivity.isDestroyed || !layer.isAttachedToWindow) return@load
            if (expectedBackgroundVersion != config.backgroundVersion) return@load
            if (expectedConfigVersion != config.configVersion) return@load
            if (bitmap == null) {
                hasUsableBitmap = false
                RemoteConfigClient.reportFault(currentActivity, "背景图片解码失败")
                remove(currentActivity)
                return@load
            }
            setBitmap(bitmap)
            transparencyManager.apply(
                currentActivity,
                layer,
                config.clearOpaqueBackgrounds,
                config.cardTransparencyEnabled,
                config.cardAlpha,
            )
        }

        if (hasUsableBitmap) {
            transparencyManager.apply(
                activity,
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
        currentConfig = null
    }

    private fun applyCouiCard(view: View) {
        val activity = activityRef.get() ?: return
        val config = currentConfig ?: return
        if (!config.cardTransparencyEnabled ||
            !config.enabled ||
            !hasUsableBitmap ||
            ExcludedPageDetector.isExcluded(activity)
        ) {
            return
        }
        transparencyManager.applyCouiCard(view, config.cardAlpha)
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

        fun onCouiCardReady(view: View) {
            val activity = findActivity(view.context) ?: return
            val layer = activity.window.decorView
                .findViewWithTag<View>(BackgroundLayer.LAYER_TAG) as? BackgroundLayer
                ?: return
            if (isControllerInitialized(layer)) {
                layer.controller.applyCouiCard(view)
            }
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

        private fun findActivity(context: Context?): Activity? {
            var candidate = context
            repeat(MAX_CONTEXT_UNWRAP_DEPTH) {
                when (candidate) {
                    is Activity -> return candidate as Activity
                    is ContextWrapper -> {
                        val base = (candidate as ContextWrapper).baseContext
                        if (base === candidate) return null
                        candidate = base
                    }
                    else -> return null
                }
            }
            return null
        }

        private const val MAX_CONTEXT_UNWRAP_DEPTH = 8
    }
}

private class TransparencyManager {
    private data class OriginalState(
        val background: Drawable?,
        val alpha: Float,
        val appliedBackground: Drawable? = null,
        val couiCardColor: Int? = null,
        val appliedCouiCardColor: Int? = null,
    )

    private val originals = WeakHashMap<View, OriginalState>()

    fun apply(
        activity: Activity,
        backgroundLayer: BackgroundLayer,
        clearOpaque: Boolean,
        cardsEnabled: Boolean,
        cardAlpha: Float,
    ) {
        val roots = contentRoots(activity, backgroundLayer)
        val visitedDepths = IdentityHashMap<View, Int>()
        roots.forEach { root ->
            visit(
                root,
                backgroundLayer,
                0,
                clearOpaque,
                cardsEnabled,
                cardAlpha,
                visitedDepths,
            )
        }
    }

    fun restoreAll() {
        originals.entries.toList().forEach { (view, state) ->
            try {
                if (state.couiCardColor != null && state.appliedCouiCardColor != null) {
                    val field = findCouiCardColorField(view)
                    if (field != null && field.getInt(view) == state.appliedCouiCardColor) {
                        field.setInt(view, state.couiCardColor)
                        view.invalidate()
                    }
                } else if (view.background === state.appliedBackground) {
                    view.background = state.background
                }
                if (abs(view.alpha - state.alpha) < ALPHA_EPSILON) {
                    view.alpha = state.alpha
                }
            } catch (_: Throwable) {
                // 已销毁或被系统替换的 View 直接跳过。
            }
        }
        originals.clear()
    }

    fun applyCouiCard(view: View, alpha: Float) {
        applyCouiCardColor(view, alpha.coerceIn(0.1f, 1f))
    }

    private fun visit(
        view: View,
        backgroundLayer: BackgroundLayer,
        depth: Int,
        clearOpaque: Boolean,
        cardsEnabled: Boolean,
        cardAlpha: Float,
        visitedDepths: IdentityHashMap<View, Int>,
    ) {
        if (view === backgroundLayer || depth > MAX_DEPTH) return
        val previousDepth = visitedDepths[view]
        if (previousDepth != null && previousDepth <= depth) return
        visitedDepths[view] = depth
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
                    visitedDepths,
                )
            }
        }
    }

    private fun clearSimpleOpaqueContainer(view: View) {
        if (!isCandidateContainer(view) || isInteractive(view)) return
        originals[view]?.let { state ->
            if (view.background === state.appliedBackground) return
            originals.remove(view)
        }
        val background = view.background as? ColorDrawable ?: return
        if (background.alpha != 255 || Color.alpha(background.color) != 255) return
        val replacement = ColorDrawable(Color.TRANSPARENT)
        originals[view] = OriginalState(
            background = view.background,
            alpha = view.alpha,
            appliedBackground = replacement,
        )
        view.background = replacement
        HookLogger.diagnostic("透明化纯色容器：${view.javaClass.name}")
    }

    private fun makeCardTransparent(view: View, alpha: Float) {
        if (applyCouiCardColor(view, alpha.coerceIn(0.1f, 1f))) return
        originals[view]?.let { state ->
            if (view.background === state.appliedBackground) return
            originals.remove(view)
        }
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
        originals[view] = OriginalState(
            background = background,
            alpha = view.alpha,
            appliedBackground = clone,
        )
        clone.alpha = (alpha.coerceIn(0.1f, 1f) * 255f).toInt()
        view.background = clone
        HookLogger.diagnostic("调整卡片透明度：${view.javaClass.name}")
    }

    private fun applyCouiCardColor(view: View, alpha: Float): Boolean {
        val field = findCouiCardColorField(view) ?: return false
        return try {
            val existing = originals[view]
            if (existing?.couiCardColor != null && existing.appliedCouiCardColor != null) {
                val current = field.getInt(view)
                if (current == existing.appliedCouiCardColor) return true
                originals.remove(view)
            }
            val originalColor = field.getInt(view)
            val appliedAlpha = (Color.alpha(originalColor) * alpha)
                .roundToInt()
                .coerceIn(0, 255)
            val appliedColor = Color.argb(
                appliedAlpha,
                Color.red(originalColor),
                Color.green(originalColor),
                Color.blue(originalColor),
            )
            originals[view] = OriginalState(
                background = view.background,
                alpha = view.alpha,
                couiCardColor = originalColor,
                appliedCouiCardColor = appliedColor,
            )
            field.setInt(view, appliedColor)
            view.invalidate()
            HookLogger.diagnostic("调整 COUI 卡片绘制颜色：${view.javaClass.name}")
            true
        } catch (error: Throwable) {
            HookLogger.diagnostic("COUI 卡片颜色调整失败：${error.javaClass.simpleName}")
            false
        }
    }

    private fun findCouiCardColorField(view: View): Field? {
        var type: Class<*>? = view.javaClass
        repeat(MAX_CLASS_HIERARCHY_DEPTH) {
            val current = type ?: return null
            try {
                return current.getDeclaredField(COUI_CARD_COLOR_FIELD).apply {
                    isAccessible = true
                }
            } catch (_: NoSuchFieldException) {
                type = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun contentRoots(
        activity: Activity,
        backgroundLayer: BackgroundLayer,
    ): LinkedHashSet<View> {
        val roots = LinkedHashSet<View>()
        SETTINGS_ROOT_IDS.forEach { entryName ->
            val id = try {
                activity.resources.getIdentifier(entryName, "id", SETTINGS_PACKAGE)
            } catch (_: Throwable) {
                0
            }
            if (id != 0) {
                activity.findViewById<View>(id)?.let { if (it !== backgroundLayer) roots += it }
            }
        }
        activity.findViewById<View>(android.R.id.content)
            ?.let { if (it !== backgroundLayer) roots += it }
        if (roots.isEmpty()) roots += activity.window.decorView
        return roots
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
        var type: Class<*>? = view.javaClass
        repeat(MAX_CLASS_HIERARCHY_DEPTH) {
            val name = type?.name?.lowercase() ?: return false
            if (CARD_NAMES.any(name::contains)) return true
            type = type?.superclass
        }
        return false
    }

    private fun isSupportedCardDrawable(drawable: Drawable): Boolean =
        drawable is ColorDrawable ||
            drawable is GradientDrawable ||
            drawable is RippleDrawable ||
            drawable is LayerDrawable ||
            drawable is InsetDrawable

    companion object {
        private const val MAX_DEPTH = 4
        private const val MAX_CLASS_HIERARCHY_DEPTH = 8
        private const val ALPHA_EPSILON = 0.001f
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val COUI_CARD_COLOR_FIELD = "mCardBackgroundColor"
        private val SETTINGS_ROOT_IDS = listOf(
            "recycler_view",
            "list_container",
            "content_frame",
            "content_parent",
            "settings_homepage_container",
        )
        private val CARD_NAMES = listOf(
            "cardview",
            "materialcard",
            "opluscard",
            "couicard",
            "preferencecard",
            "withcard",
        )
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
    private var originalNavigationBarDividerColor = Color.TRANSPARENT
    private var originalNavigationBarContrastEnforced = true
    private var originalStatusBarContrastEnforced = true
    private var originalAppearance = 0

    fun apply(config: ModuleConfig) {
        if (!config.extendIntoSystemBars) {
            restore()
            return
        }
        try {
            if (!applied) {
                originalStatusBarColor = window.statusBarColor
                originalNavigationBarColor = window.navigationBarColor
                originalNavigationBarDividerColor = window.navigationBarDividerColor
                originalNavigationBarContrastEnforced = window.isNavigationBarContrastEnforced
                originalStatusBarContrastEnforced = window.isStatusBarContrastEnforced
                originalAppearance = window.insetsController?.systemBarsAppearance ?: 0
                applied = true
            }
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.navigationBarDividerColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false

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
            window.navigationBarDividerColor = originalNavigationBarDividerColor
            window.isNavigationBarContrastEnforced = originalNavigationBarContrastEnforced
            window.isStatusBarContrastEnforced = originalStatusBarContrastEnforced
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
