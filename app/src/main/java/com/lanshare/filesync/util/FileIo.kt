package com.lanshare.filesync.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileIo {

    /** 读取用户可见文件名，失败时返回 null。 */
    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i) else null
                    } else null
                }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取文件大小（字节），失败时返回 0。 */
    fun sizeOf(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 把 content:// 的图复制到应用缓存，后续只操作缓存副本，原图永不被修改。 */
    fun copyToCache(context: Context, uri: Uri, dir: File, suggestedName: String): File? {
        return try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, suggestedName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (file.length() <= 0L) {
                file.delete()
                null
            } else file
        } catch (e: Exception) {
            null
        }
    }

    /** 去掉扩展名并替换掉文件系统/zip 不友好的字符。 */
    fun safeBaseName(raw: String): String {
        val base = raw.substringBeforeLast('.').trim().ifEmpty { "image" }
        val cleaned = base.replace(Regex("""[^\w\-一-龥]"""), "_")
        return cleaned.ifEmpty { "image" }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> String.format("%.0f KB", kb)
            kb < 1024 * 1024 -> String.format("%.2f MB", kb / 1024.0)
            else -> String.format("%.2f GB", kb / 1024.0 / 1024.0)
        }
    }
}
