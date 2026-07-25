package com.local.settingsanimebackground.hook

import de.robv.android.xposed.XposedBridge

object HookLogger {
    const val TAG = "AnimeSettingsBg"

    @Volatile
    var diagnosticEnabled: Boolean = false

    fun diagnostic(message: String) {
        if (diagnosticEnabled) XposedBridge.log("$TAG: $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        XposedBridge.log("$TAG: $message")
        if (throwable != null) XposedBridge.log(throwable)
    }
}
