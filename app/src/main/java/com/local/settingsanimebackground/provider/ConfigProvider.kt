package com.local.settingsanimebackground.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.local.settingsanimebackground.config.ConfigContract
import com.local.settingsanimebackground.config.ConfigRepository
import java.io.FileNotFoundException

class ConfigProvider : ContentProvider() {
    private lateinit var repository: ConfigRepository

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        repository = ConfigRepository(providerContext)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceAllowedUid()
        require(arg == null) { "该操作不接受 arg 参数" }
        return when (method) {
            ConfigContract.METHOD_GET_CONFIG -> {
                require(extras == null || extras.isEmpty) { "读取配置不接受 extras 参数" }
                repository.read().toBundle(repository.hasBackground())
            }

            ConfigContract.METHOD_REPORT_FAULT -> {
                require(extras != null) { "reportFault 缺少 extras" }
                require(extras.keySet() == setOf(ConfigContract.EXTRA_FAULT)) {
                    "reportFault 包含未知参数"
                }
                val fault = extras.getString(ConfigContract.EXTRA_FAULT)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("故障内容为空")
                repository.reportFault(fault)
                Bundle.EMPTY
            }

            else -> throw IllegalArgumentException("不支持的 Provider 操作：$method")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceAllowedUid()
        validateBackgroundUri(uri)
        if (mode != "r") throw SecurityException("背景图片只允许只读访问")
        val file = repository.backgroundFile()
        if (!file.isFile) throw FileNotFoundException("尚未导入背景图片")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        enforceAllowedUid()
        validateBackgroundUri(uri)
        return "image/webp"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceAllowedUid()
        throw UnsupportedOperationException("请使用 ContentProvider.call 读取配置")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        enforceAllowedUid()
        throw SecurityException("禁止通过 Provider 写入")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceAllowedUid()
        throw SecurityException("禁止通过 Provider 写入")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceAllowedUid()
        throw SecurityException("禁止通过 Provider 删除")
    }

    private fun validateBackgroundUri(uri: Uri) {
        require(uri.scheme == "content") { "URI scheme 非法" }
        require(uri.authority == ConfigContract.AUTHORITY) { "URI authority 非法" }
        require(uri.pathSegments == listOf("background")) { "背景 URI 路径非法" }
        require(uri.query == null && uri.fragment == null) { "背景 URI 不接受附加参数" }
    }

    private fun enforceAllowedUid() {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return

        val providerContext = context ?: throw SecurityException("Provider 尚未初始化")
        val settingsUid = try {
            @Suppress("DEPRECATION")
            providerContext.packageManager
                .getApplicationInfo(SETTINGS_PACKAGE, PackageManager.MATCH_SYSTEM_ONLY)
                .uid
        } catch (_: PackageManager.NameNotFoundException) {
            -1
        }
        if (callingUid != settingsUid) {
            throw SecurityException("UID $callingUid 无权访问模块配置")
        }
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
