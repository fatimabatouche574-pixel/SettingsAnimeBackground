package com.local.settingsanimebackground.config

import android.net.Uri

object ConfigContract {
    const val AUTHORITY = "com.local.settingsanimebackground.provider"
    val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")
    val CONFIG_URI: Uri = BASE_URI.buildUpon().appendPath("config").build()
    val BACKGROUND_URI: Uri = BASE_URI.buildUpon().appendPath("background").build()

    const val METHOD_GET_CONFIG = "getConfig"
    const val METHOD_REPORT_FAULT = "reportFault"
    const val EXTRA_FAULT = "fault"
}
