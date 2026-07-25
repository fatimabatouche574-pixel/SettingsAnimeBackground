package com.local.settingsanimebackground.hook

import android.app.Activity
import android.app.Fragment

object ExcludedPageDetector {
    private val sensitiveTokens = listOf(
        "Confirm",
        "Password",
        "ChooseLock",
        "LockPassword",
        "LockPattern",
        "Biometric",
        "FingerprintEnroll",
        "Credential",
        "Keyguard",
        "Emergency",
        "Permission",
        "GrantPermissions",
        "FaceEnroll",
    )

    fun containsSensitiveToken(className: String): Boolean =
        sensitiveTokens.any { className.contains(it, ignoreCase = true) }

    fun isExcluded(activity: Activity): Boolean {
        if (containsSensitiveToken(activity.javaClass.name)) return true
        if (platformFragments(activity).any(::isSensitiveFragmentTree)) return true
        if (supportFragments(activity).any(::isSensitiveObjectTree)) return true
        return containsSensitiveViewClass(activity.window.decorView, 0)
    }

    @Suppress("DEPRECATION")
    private fun platformFragments(activity: Activity): List<Fragment> = try {
        activity.fragmentManager.fragments.orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun isSensitiveFragmentTree(fragment: Fragment): Boolean {
        if (containsSensitiveToken(fragment.javaClass.name)) return true
        return try {
            fragment.childFragmentManager.fragments.orEmpty().any(::isSensitiveFragmentTree)
        } catch (_: Throwable) {
            false
        }
    }

    private fun supportFragments(activity: Activity): List<Any> = try {
        val managerMethod = activity.javaClass.methods.firstOrNull {
            it.name == "getSupportFragmentManager" && it.parameterCount == 0
        } ?: return emptyList()
        fragmentsFromManager(managerMethod.invoke(activity))
    } catch (_: Throwable) {
        emptyList()
    }

    private fun fragmentsFromManager(manager: Any?): List<Any> {
        if (manager == null) return emptyList()
        return try {
            val method = manager.javaClass.methods.firstOrNull {
                it.name == "getFragments" && it.parameterCount == 0
            } ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (method.invoke(manager) as? List<Any>).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isSensitiveObjectTree(fragment: Any): Boolean {
        if (containsSensitiveToken(fragment.javaClass.name)) return true
        return try {
            val childMethod = fragment.javaClass.methods.firstOrNull {
                it.name == "getChildFragmentManager" && it.parameterCount == 0
            } ?: return false
            fragmentsFromManager(childMethod.invoke(fragment)).any(::isSensitiveObjectTree)
        } catch (_: Throwable) {
            false
        }
    }

    private fun containsSensitiveViewClass(view: android.view.View, depth: Int): Boolean {
        if (containsSensitiveToken(view.javaClass.name)) return true
        if (depth >= MAX_VIEW_DEPTH || view !is android.view.ViewGroup) return false
        for (index in 0 until view.childCount) {
            if (containsSensitiveViewClass(view.getChildAt(index), depth + 1)) return true
        }
        return false
    }

    private const val MAX_VIEW_DEPTH = 4
}
