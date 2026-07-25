package com.local.settingsanimebackground.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.local.settingsanimebackground.config.ConfigContract
import java.util.concurrent.Executors

object BackgroundBitmapStore {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AnimeSettingsBg-loader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var cachedVersion = Long.MIN_VALUE
    private var cachedBitmap: Bitmap? = null

    fun load(context: Context, version: Long, callback: (Bitmap?) -> Unit) {
        synchronized(lock) {
            if (cachedVersion == version) {
                val bitmap = cachedBitmap
                mainHandler.post { callback(bitmap) }
                return
            }
        }
        val appContext = context.applicationContext
        worker.execute {
            val bitmap = try {
                appContext.contentResolver.openFileDescriptor(
                    ConfigContract.BACKGROUND_URI,
                    "r",
                )?.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                }
            } catch (error: Throwable) {
                HookLogger.error("读取背景图片失败", error)
                null
            }
            synchronized(lock) {
                if (version >= cachedVersion) {
                    cachedVersion = version
                    cachedBitmap = bitmap
                }
            }
            mainHandler.post { callback(bitmap) }
        }
    }
}
