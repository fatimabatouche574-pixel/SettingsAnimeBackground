package com.local.settingsanimebackground.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSamplingTest {
    @Test
    fun smallImageDoesNotSampleOrResize() {
        assertEquals(1, ImageSampling.calculateInSampleSize(1080, 1920))
        assertEquals(1080 to 1920, ImageSampling.targetSize(1080, 1920))
    }

    @Test
    fun largeImageUsesPowerOfTwoDecodeSampling() {
        assertEquals(4, ImageSampling.calculateInSampleSize(8000, 6000))
    }

    @Test
    fun longEdgeIsLimitedTo2160() {
        assertEquals(2160 to 1620, ImageSampling.targetSize(8000, 6000))
        assertEquals(1215 to 2160, ImageSampling.targetSize(3000, 5333))
    }

    @Test
    fun invalidBoundsFallBackToSafeSampling() {
        assertEquals(1, ImageSampling.calculateInSampleSize(0, 100))
    }

    @Test
    fun decodedLongEdgeDoesNotExceedLimit() {
        val sample = ImageSampling.calculateInSampleSize(100_000, 80_000)
        assertEquals(64, sample)
        assertTrue(100_000 / sample <= ImageSampling.MAX_LONG_EDGE)
    }
}
