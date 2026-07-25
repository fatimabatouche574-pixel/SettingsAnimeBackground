package com.local.settingsanimebackground.image

object ImageSampling {
    const val MAX_LONG_EDGE = 2160

    fun calculateInSampleSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Int {
        if (width <= 0 || height <= 0 || maxLongEdge <= 0) return 1
        var sample = 1
        val longEdge = maxOf(width, height)
        while (longEdge / sample > maxLongEdge && sample <= Int.MAX_VALUE / 2) {
            sample *= 2
        }
        return sample
    }

    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxLongEdge > 0)
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge.toDouble()
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
