package com.lanshare.filesync.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

data class CompressOptions(
    val maxLongEdge: Int,   // 长边像素上限（1920 ≈ Word A4 满幅 230dpi）
    val targetBytes: Long   // 单张目标体积上限
)

data class CompressResult(
    val file: File,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val quality: Int
)

/**
 * 压缩策略（源自 PicZip，原样保留核心算法）：
 * 1) 按长边上限缩放分辨率；
 * 2) 二分搜索 JPEG 质量，在不超过目标体积的前提下取最高画质；
 * 3) 若最低质量仍超标，则逐级缩小分辨率重试。
 * 只读取源文件，绝不写回原图。
 */
object ImageCompressor {

    private const val MIN_QUALITY = 35
    private const val START_QUALITY = 82
    private const val MAX_QUALITY = 95

    fun compress(src: File, outDir: File, baseName: String, opt: CompressOptions): CompressResult {
        val (w0, h0) = bounds(src)
            ?: throw IllegalStateException("无法识别图片：${src.name}")

        val target = fit(w0, h0, opt.maxLongEdge)
        val raw = decode(src, target.first, target.second)
            ?: throw IllegalStateException("无法解码图片（格式可能不受支持）：${src.name}")

        var bmp = rotate(raw, exifRotation(src))

        var bytes = encode(bmp, START_QUALITY)
        var quality = START_QUALITY

        if (bytes.size > opt.targetBytes) {
            val r = highestQualityUnder(bmp, opt.targetBytes, MIN_QUALITY, START_QUALITY)
            quality = r.first
            bytes = r.second

            // 质量已到下限仍偏大 -> 继续降分辨率
            var scale = 0.82f
            var tries = 0
            while (bytes.size > opt.targetBytes && tries < 4 && bmp.width > 320) {
                bmp = scaleBitmap(bmp, scale)
                val r2 = highestQualityUnder(bmp, opt.targetBytes, MIN_QUALITY, MAX_QUALITY)
                quality = r2.first
                bytes = r2.second
                scale *= 0.82f
                tries++
            }
        } else if (bytes.size < opt.targetBytes * 0.75) {
            // 体积远小于目标，画质还有余量，把质量提上去用满预算
            val r = highestQualityUnder(bmp, opt.targetBytes, START_QUALITY, MAX_QUALITY)
            if (r.second.size > bytes.size) {
                quality = r.first
                bytes = r.second
            }
        }

        outDir.mkdirs()
        val out = File(outDir, "$baseName.jpg")
        FileOutputStream(out).use { it.write(bytes) }

        val result = CompressResult(out, bmp.width, bmp.height, out.length(), quality)
        bmp.recycle()
        return result
    }

    /** 返回 size <= targetBytes 的最大质量；即使最小质量仍超标也返回该结果。 */
    private fun highestQualityUnder(
        bmp: Bitmap,
        targetBytes: Long,
        lo: Int,
        hi: Int
    ): Pair<Int, ByteArray> {
        var bestQ = lo
        var bestBytes = encode(bmp, lo)
        if (bestBytes.size > targetBytes) return bestQ to bestBytes

        var l = lo
        var h = hi
        while (l < h) {
            val mid = (l + h + 1) / 2
            val candidate = encode(bmp, mid)
            if (candidate.size <= targetBytes) {
                l = mid
                bestQ = mid
                bestBytes = candidate
            } else {
                h = mid - 1
            }
        }
        return bestQ to bestBytes
    }

    private fun encode(src: Bitmap, quality: Int): ByteArray {
        // JPEG 不支持透明通道，先铺白底，避免 PNG/透明图变黑
        val bmp = if (src.hasAlpha()) flattenOnWhite(src) else src
        val bos = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (bmp !== src) bmp.recycle()
        if (!ok) throw IllegalStateException("JPEG 编码失败")
        return bos.toByteArray()
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun bounds(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return o.outWidth to o.outHeight
    }

    private fun fit(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longEdge = max(w, h)
        if (longEdge <= maxEdge) return w to h
        val ratio = maxEdge.toFloat() / longEdge.toFloat()
        return max(1, (w * ratio).toInt()) to max(1, (h * ratio).toInt())
    }

    private fun decode(file: File, reqW: Int, reqH: Int): Bitmap? {
        val (w, h) = bounds(file) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(w, h, reqW, reqH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (raw.width == reqW && raw.height == reqH) return raw
        val scaled = Bitmap.createScaledBitmap(raw, reqW, reqH, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        var halfW = w
        var halfH = h
        while (halfW / 2 >= reqW && halfH / 2 >= reqH) {
            s *= 2
            halfW /= 2
            halfH /= 2
        }
        return max(1, s)
    }

    private fun exifRotation(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /** 旋转并回收源位图，保证照片方向不正躺。 */
    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }

    /** 按比例缩小并回收源位图。 */
    private fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }
}
