package com.lanshare.filesync.compress

import android.content.Context
import android.net.Uri
import com.lanshare.filesync.util.FileIo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打包流水线：选中的图片 -> 复制进缓存 -> 逐张压缩 -> 打成 zip -> 落到共享目录。
 * 产物写入共享根目录后，电脑端刷新页面即可看到并下载。
 */
object PhotoPackager {

    /** 单次选图上限（需求：照片数量 <= 100） */
    const val MAX_PICK = 100

    data class Config(
        val targetKb: Int,          // 单张目标体积（KB）
        val maxEdge: Int,           // 长边像素上限
        val sequentialNames: Boolean, // 是否用 IMG_001 顺序命名
        val subDir: String          // 共享目录下的子目录名，留空表示直接放共享根目录
    )

    data class Result(
        val zipFile: File,
        val zipBytes: Long,
        val totalOriginal: Long,
        val totalCompressed: Long,
        val count: Int,
        val skipped: List<String>
    )

    /**
     * @param cacheBase  应用缓存目录（临时文件，用完即删）
     * @param sharedRoot 共享根目录（产物最终落点）
     * @param onProgress 进度回调：percent 0~100，text 为当前步骤描述
     */
    fun pack(
        context: Context,
        cacheBase: File,
        sharedRoot: File,
        uris: List<Uri>,
        cfg: Config,
        onProgress: (percent: Int, text: String) -> Unit
    ): Result {
        val importDir = File(cacheBase, "import")
        val outDir = File(cacheBase, "out")
        importDir.deleteRecursively()
        outDir.deleteRecursively()
        importDir.mkdirs()
        outDir.mkdirs()

        val entries = ArrayList<Pair<String, File>>()
        val skipped = ArrayList<String>()
        val usedNames = HashSet<String>()
        var totalOriginal = 0L
        var totalCompressed = 0L
        var seq = 1

        val n = uris.size
        val targetBytes = cfg.targetKb * 1024L

        uris.forEachIndexed { i, uri ->
            val rawName = FileIo.displayName(context, uri) ?: "image_${i + 1}.jpg"
            val percent = (i.toFloat() / n * 88f).toInt()
            onProgress(percent, "正在压缩第 ${i + 1}/$n 张：$rawName")

            val ext = rawName.substringAfterLast('.', "jpg").ifEmpty { "jpg" }
            val cacheName = "${System.nanoTime()}_${i}_${FileIo.safeBaseName(rawName)}.$ext"
            val cached = FileIo.copyToCache(context, uri, importDir, cacheName)
            if (cached == null) {
                skipped.add("$rawName（读取失败，已跳过）")
                return@forEachIndexed
            }
            totalOriginal += cached.length()

            val baseName = if (cfg.sequentialNames) {
                String.format(Locale.US, "IMG_%03d", seq++)
            } else {
                uniqueName(usedNames, FileIo.safeBaseName(rawName))
            }

            try {
                val r = ImageCompressor.compress(
                    cached, outDir, baseName,
                    CompressOptions(cfg.maxEdge, targetBytes)
                )
                entries.add("$baseName.jpg" to r.file)
                totalCompressed += r.bytes
            } catch (e: Exception) {
                skipped.add("$rawName（${e.message ?: "压缩失败"}）")
            }
        }

        if (entries.isEmpty()) {
            importDir.deleteRecursively()
            outDir.deleteRecursively()
            throw IllegalStateException("没有可用的图片，未生成压缩包")
        }

        // 产物目录：共享根目录 / 可选子目录
        val dir = if (cfg.subDir.isBlank()) {
            sharedRoot
        } else {
            File(sharedRoot, cfg.subDir.trim())
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录：${dir.absolutePath}")
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = nextAvailable(dir, "photos_$stamp.zip")

        onProgress(90, "正在打包 ${entries.size} 张图片…")
        FileOutputStream(zipFile).use { out ->
            ZipWriter.write(out, entries) { done, total ->
                val p = 90 + (done.toFloat() / total * 10f).toInt()
                onProgress(p, "正在打包 $done/$total")
            }
        }

        // 清理临时文件
        outDir.deleteRecursively()
        importDir.deleteRecursively()

        onProgress(100, "完成")
        return Result(
            zipFile = zipFile,
            zipBytes = zipFile.length(),
            totalOriginal = totalOriginal,
            totalCompressed = totalCompressed,
            count = entries.size,
            skipped = skipped
        )
    }

    /** 同名自动加 _2、_3，避免覆盖已有压缩包。 */
    private fun nextAvailable(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
            f = File(dir, candidate)
            if (!f.exists()) return f
            i++
        }
    }

    private fun uniqueName(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        var i = 2
        while (true) {
            val candidate = "${name}_$i"
            if (used.add(candidate)) return candidate
            i++
        }
    }
}
