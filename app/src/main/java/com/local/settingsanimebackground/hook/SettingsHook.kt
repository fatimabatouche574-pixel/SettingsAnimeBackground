package com.local.settingsanimebackground.hook

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SettingsHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SETTINGS_PACKAGE) return
        if (!hooksInstalled.compareAndSet(false, true)) return

        HookLogger.error("已加载设置萌化背景，进程：${lpparam.processName}")
        hookLifecycle("onPostResume", POST_RESUME_DELAY_MS)
        hookOnCreate()
        hookOnDestroy()
        hookSupportFragmentLifecycle(lpparam.classLoader)
        hookCouiCards(lpparam.classLoader)
    }

    private fun hookLifecycle(methodName: String, delayMillis: Long) {
        XposedBridge.hookAllMethods(
            Activity::class.java,
            methodName,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    ActivityRefreshRegistry.track(activity)
                    ActivityRefreshRegistry.schedule(activity, delayMillis)
                }
            },
        )
    }

    private fun hookOnCreate() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    ActivityRefreshRegistry.track(activity)
                    ActivityRefreshRegistry.schedule(activity, CREATE_FALLBACK_DELAY_MS)
                }
            },
        )
    }

    private fun hookOnDestroy() {
        XposedBridge.hookAllMethods(
            Activity::class.java,
            "onDestroy",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    ActivityRefreshRegistry.untrack(activity)
                    BackgroundController.remove(activity)
                }
            },
        )
    }

    private fun hookSupportFragmentLifecycle(classLoader: ClassLoader) {
        val fragmentClass = XposedHelpers.findClassIfExists(
            "androidx.fragment.app.Fragment",
            classLoader,
        ) ?: run {
            HookLogger.error("未找到 AndroidX Fragment，Fragment 页面刷新 Hook 未安装")
            return
        }
        val callbacks = XposedBridge.hookAllMethods(
            fragmentClass,
            "performResume",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    if (!ExcludedPageDetector.containsSensitiveToken(fragment.javaClass.name)) return
                    fragmentActivity(fragment)?.let { activity ->
                        ActivityRefreshRegistry.cancel(activity)
                        BackgroundController.remove(activity)
                        HookLogger.diagnostic("敏感 Fragment 恢复前已移除背景：${fragment.javaClass.name}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    val activity = fragmentActivity(fragment) ?: return
                    ActivityRefreshRegistry.track(activity)
                    ActivityRefreshRegistry.schedule(activity, FRAGMENT_RESUME_DELAY_MS)
                }
            },
        )
        if (callbacks.isEmpty()) {
            HookLogger.error("Fragment.performResume 不存在，页面切换刷新 Hook 未安装")
        } else {
            HookLogger.diagnostic("已安装 Fragment.performResume 页面刷新 Hook")
        }
    }

    private fun hookCouiCards(classLoader: ClassLoader) {
        val cardClass = XposedHelpers.findClassIfExists(
            "com.coui.appcompat.cardlist.COUICardListSelectedItemLayout",
            classLoader,
        ) ?: run {
            HookLogger.diagnostic("未找到 COUI 卡片类，跳过设备定向卡片 Hook")
            return
        }
        XposedBridge.hookAllConstructors(
            cardClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? android.view.View ?: return
                    view.post { BackgroundController.onCouiCardReady(view) }
                }
            },
        )
        XposedBridge.hookAllMethods(
            cardClass,
            "refreshCardBg",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? android.view.View)?.let(
                        BackgroundController::onCouiCardReady,
                    )
                }
            },
        )
        HookLogger.diagnostic("已安装 PGKM10 COUI 卡片透明度 Hook")
    }

    private fun fragmentActivity(fragment: Any): Activity? = try {
        XposedHelpers.callMethod(fragment, "getActivity") as? Activity
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val POST_RESUME_DELAY_MS = 180L
        private const val CREATE_FALLBACK_DELAY_MS = 230L
        private const val FRAGMENT_RESUME_DELAY_MS = 180L
        private val hooksInstalled = AtomicBoolean(false)
    }
}

private object ActivityRefreshRegistry {
    private val activities = CopyOnWriteArrayList<WeakReference<Activity>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRefreshes = WeakHashMap<Activity, Runnable>()

    fun track(activity: Activity) {
        var found = false
        activities.removeAll { reference ->
            val candidate = reference.get()
            if (candidate == null) {
                true
            } else {
                if (candidate === activity) found = true
                false
            }
        }
        if (!found) activities += WeakReference(activity)
        RemoteConfigClient.registerObserver(activity.applicationContext) {
            mainHandler.post { refreshAll() }
        }
    }

    fun untrack(activity: Activity) {
        cancel(activity)
        activities.removeAll { reference ->
            val candidate = reference.get()
            candidate == null || candidate === activity
        }
    }

    fun schedule(activity: Activity, delayMillis: Long) {
        cancel(activity)
        val activityRef = WeakReference(activity)
        val task = Runnable {
            pendingRefreshes.remove(activity)
            activityRef.get()?.let(::refresh)
        }
        pendingRefreshes[activity] = task
        mainHandler.postDelayed(task, delayMillis)
    }

    fun cancel(activity: Activity) {
        pendingRefreshes.remove(activity)?.let(mainHandler::removeCallbacks)
    }

    fun refresh(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val snapshot = RemoteConfigClient.read(activity)
        if (snapshot == null) {
            BackgroundController.remove(activity)
            return
        }
        try {
            BackgroundController.applyTo(activity, snapshot)
        } catch (error: Throwable) {
            HookLogger.error("刷新背景失败：${activity.javaClass.name}", error)
            RemoteConfigClient.reportFault(
                activity,
                "刷新背景失败：${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
            BackgroundController.remove(activity)
        }
    }

    private fun refreshAll() {
        activities.removeAll { it.get() == null }
        activities.forEach { reference ->
            reference.get()?.let(::refresh)
        }
    }
}
