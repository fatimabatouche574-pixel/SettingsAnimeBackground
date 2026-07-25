package com.local.settingsanimebackground.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.settingsanimebackground.config.ConfigContract
import com.local.settingsanimebackground.config.ConfigRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val backgroundFile = ConfigRepository(context).backgroundFile()

    @Before
    fun createSourceImage() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            FileOutputStream(backgroundFile).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun deleteSourceImage() {
        backgroundFile.delete()
    }

    @Test
    fun importsValidImageWhenBoundsDecodeReturnsNull() {
        val result = ImageImporter(context).import(ConfigContract.BACKGROUND_URI)

        assertEquals(64, result.width)
        assertEquals(32, result.height)
        assertTrue(backgroundFile.isFile)
    }
}
