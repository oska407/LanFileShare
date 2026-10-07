package com.lanshare.filesync.compress

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipWriter {

    private const val BUFFER = 64 * 1024

    /** 把已压缩的图片打成 zip；同名文件自动加后缀，避免覆盖。 */
    fun write(output: OutputStream, entries: List<Pair<String, File>>, onProgress: (done: Int, total: Int) -> Unit) {
        val used = HashSet<String>()
        ZipOutputStream(BufferedOutputStream(output, BUFFER)).use { zos ->
            entries.forEachIndexed { index, (name, file) ->
                val entryName = uniqueName(used, name)
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    var n = input.read(buf)
                    while (n > 0) {
                        zos.write(buf, 0, n)
                        n = input.read(buf)
                    }
                }
                zos.closeEntry()
                onProgress(index + 1, entries.size)
            }
        }
    }

    private fun uniqueName(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }
}
