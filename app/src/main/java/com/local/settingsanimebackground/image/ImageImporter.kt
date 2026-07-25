package com.local.settingsanimebackground.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.local.settingsanimebackground.config.ConfigRepository
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ceil
import kotlin.math.sqrt

class ImageImporter(private val context: Context) {
    data class Result(val luminance: Float, val width: Int, val height: Int)

    fun import(uri: Uri): Result {
        require(uri.scheme == "content") { "请选择有效的文档图片" }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        if (mime != null && mime !in SUPPORTED_MIME_TYPES) {
            throw IllegalArgumentException("仅支持 JPEG、PNG 和 WEBP 图片")
        }

        val orientation = resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: throw IllegalArgumentException("无法打开所选图片")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsDescriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("无法读取所选图片")
        boundsDescriptor.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("图片格式无效或已损坏")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var decoded = resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
        } ?: throw IllegalArgumentException("无法解码所选图片")

        try {
            val corrected = applyExif(decoded, orientation)
            if (corrected !== decoded) {
                decoded.recycle()
                decoded = corrected
            }

            val (targetWidth, targetHeight) = ImageSampling.targetSize(decoded.width, decoded.height)
            val finalBitmap = if (targetWidth != decoded.width || targetHeight != decoded.height) {
                Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            } else {
                decoded
            }

            try {
                val luminance = calculateTopLuminance(finalBitmap)
                writeAtomically(finalBitmap)
                return Result(luminance, finalBitmap.width, finalBitmap.height)
            } finally {
                if (finalBitmap !== decoded) finalBitmap.recycle()
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun applyExif(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun calculateTopLuminance(bitmap: Bitmap): Float {
        val topHeight = maxOf(1, (bitmap.height * TOP_REGION_RATIO).toInt())
        val totalPixels = bitmap.width.toLong() * topHeight.toLong()
        val stride = maxOf(1, ceil(sqrt(totalPixels / MAX_LUMINANCE_SAMPLES.toDouble())).toInt())
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < topHeight) {
            var x = 0
            while (x < bitmap.width) {
                sum += Color.luminance(bitmap.getPixel(x, y))
                count++
                x += stride
            }
            y += stride
        }
        return if (count == 0) 0.5f else (sum / count).toFloat().coerceIn(0f, 1f)
    }

    private fun writeAtomically(bitmap: Bitmap) {
        val target = ConfigRepository(context).backgroundFile()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                val success = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, output)
                if (!success) throw IllegalStateException("设备无法编码 WEBP 图片")
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private const val WEBP_QUALITY = 88
        private const val TOP_REGION_RATIO = 0.15f
        private const val MAX_LUMINANCE_SAMPLES = 4096
    }
}
