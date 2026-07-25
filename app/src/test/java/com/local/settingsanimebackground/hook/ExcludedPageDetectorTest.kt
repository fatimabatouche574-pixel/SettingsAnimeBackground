package com.local.settingsanimebackground.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedPageDetectorTest {
    @Test
    fun sensitiveNamesAreExcludedIgnoringCase() {
        assertTrue(
            ExcludedPageDetector.containsSensitiveToken(
                "com.android.settings.password.ChooseLockPassword",
            ),
        )
        assertTrue(
            ExcludedPageDetector.containsSensitiveToken(
                "com.oplus.settings.biometrics.FingerprintEnrollActivity",
            ),
        )
        assertTrue(
            ExcludedPageDetector.containsSensitiveToken(
                "com.android.permissioncontroller.GrantPermissionsActivity",
            ),
        )
    }

    @Test
    fun ordinarySettingsPageIsAllowed() {
        assertFalse(
            ExcludedPageDetector.containsSensitiveToken(
                "com.android.settings.Settings\$WifiSettingsActivity",
            ),
        )
    }
}
