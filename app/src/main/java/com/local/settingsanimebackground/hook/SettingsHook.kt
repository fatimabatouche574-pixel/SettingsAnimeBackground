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
    }

    private fun hookLifecycle(methodName: String, delayMillis: Long) {
        XposedBridge.hookAllMethods(
            Activity::class.java,
            methodName,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    ActivityRefreshRegistry.track(activity)
                    Handler(activity.mainLooper).postDelayed(
                        { ActivityRefreshRegistry.refresh(activity) },
                        delayMillis,
                    )
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
                    Handler(activity.mainLooper).postDelayed(
                        { ActivityRefreshRegistry.refresh(activity) },
                        CREATE_FALLBACK_DELAY_MS,
                    )
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

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val POST_RESUME_DELAY_MS = 180L
        private const val CREATE_FALLBACK_DELAY_MS = 230L
        private val hooksInstalled = AtomicBoolean(false)
    }
}

private object ActivityRefreshRegistry {
    private val activities = CopyOnWriteArrayList<WeakReference<Activity>>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        activities.removeAll { reference ->
            val candidate = reference.get()
            candidate == null || candidate === activity
        }
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
