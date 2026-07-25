package com.local.settingsanimebackground.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.local.settingsanimebackground.config.ConfigContract
import com.local.settingsanimebackground.config.ModuleConfig
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteSnapshot(
    val config: ModuleConfig,
    val hasBackground: Boolean,
)

object RemoteConfigClient {
    private val observerRegistered = AtomicBoolean(false)

    fun read(context: Context): RemoteSnapshot? = try {
        val bundle = context.contentResolver.call(
            ConfigContract.BASE_URI,
            ConfigContract.METHOD_GET_CONFIG,
            null,
            null,
        ) ?: return null
        RemoteSnapshot(
            config = ModuleConfig.fromBundle(bundle),
            hasBackground = bundle.getBoolean(ModuleConfig.KEY_HAS_BACKGROUND, false),
        )
    } catch (error: Throwable) {
        HookLogger.error("读取跨进程配置失败", error)
        null
    }

    fun registerObserver(context: Context, onChanged: () -> Unit) {
        if (!observerRegistered.compareAndSet(false, true)) return
        try {
            context.applicationContext.contentResolver.registerContentObserver(
                ConfigContract.CONFIG_URI,
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        onChanged()
                    }
                },
            )
            HookLogger.diagnostic("配置观察器已注册")
        } catch (error: Throwable) {
            observerRegistered.set(false)
            HookLogger.error("注册配置观察器失败", error)
        }
    }

    fun reportFault(context: Context, message: String) {
        try {
            context.contentResolver.call(
                ConfigContract.BASE_URI,
                ConfigContract.METHOD_REPORT_FAULT,
                null,
                Bundle().apply { putString(ConfigContract.EXTRA_FAULT, message.take(1000)) },
            )
        } catch (_: Throwable) {
            // 故障上报不能影响系统设置。
        }
    }
}
