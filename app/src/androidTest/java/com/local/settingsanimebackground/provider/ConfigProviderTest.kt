package com.local.settingsanimebackground.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.settingsanimebackground.config.ConfigContract
import com.local.settingsanimebackground.config.ModuleConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigProviderTest {
    @Test
    fun ownUidCanReadCompleteConfigBundle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundle = requireNotNull(
            context.contentResolver.call(
                ConfigContract.BASE_URI,
                ConfigContract.METHOD_GET_CONFIG,
                null,
                null,
            ),
        )
        assertTrue(bundle.containsKey(ModuleConfig.KEY_ENABLED))
        assertTrue(bundle.containsKey(ModuleConfig.KEY_BACKGROUND_VERSION))
        assertTrue(bundle.containsKey(ModuleConfig.KEY_CONFIG_VERSION))
    }
}
