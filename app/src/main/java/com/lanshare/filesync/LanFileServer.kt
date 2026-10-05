package com.lanshare.filesync

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.LocalFileContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.toOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 局域网文件服务器（Ktor/CIO），监听 0.0.0.0:8080
 * root: 共享根目录；html: 内嵌网页；cacheDir: 上传临时文件目录
 */
class LanFileServer(
    private val root: File,
    private val port: Int,
    private val html: String,
    private val cacheDir: File
) {

    private var engine: ApplicationEngine? = null
    private val lock = Any()

    private val maxTextSize = 5 * 1024 * 1024

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }
            .also { it.start(wait = false) }
    }

    fun stop() {
        val e = engine
        engine = null
        Thread {
            try {
                e?.stop(500, 2000)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun Application.module() {
        install(PartialContent) { maxRangeCount = 16 }
        routing {
            get("/") { call.respondText(html, ContentType.Text.Html) }
            get("/api/list") { handleList(call) }
            get("/api/file") { handleFile(call) }
            get("/api/zip") { handleZipGet(call) }
            post("/api/zip") { handleZipPost(call) }
            post("/api/upload") { handleUpload(call) }
            post("/api/mkdir") { handleMkdir(call) }
            post("/api/newfile") { handleNewFile(call) }
            post("/api/save") { handleSave(call) }
            post("/api/rename") { handleRename(call) }
            post("/api/delete") { handleDelete(call) }
        }
    }

    // ---------- 各接口 ----------

    private suspend fun handleList(call: ApplicationCall) {
        val rel = call.request.queryParameters["path"] ?: ""
        val dir = resolve(rel)
        if (dir == null || !dir.isDirectory) {
            call.bad("目录不存在")
            return
        }
        val arr = JSONArray()
        val list = dir.listFiles()
        if (list != null) {
            list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
                arr.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("isDir", f.isDirectory)
                        .put("size", if (f.isDirectory) -1L else f.length())
                        .put("mtime", f.lastModified())
                )
            }
        }
        call.respondText(
            JSONObject().put("path", rel.trim('/')).put("entries", arr).toString(),
            ContentType.Application.Json
        )
    }

    private suspend fun handleFile(call: ApplicationCall) {
        val f = resolve(call.request.queryParameters["path"] ?: "")
        if (f == null || !f.isFile) {
            call.bad("文件不存在")
            return
        }
        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            "attachment; filename*=UTF-8''" + enc(f.name)
        )
        call.respond(LocalFileContent(f, mimeOf(f.name)))
    }

    private suspend fun handleZipGet(call: ApplicationCall) {
        val f = resolve(call.request.queryParameters["path"] ?: "")
        if (f == null || !f.exists()) {
            call.bad("路径不存在")
            return
        }
        val base = if (f.isDirectory) f.name else f.nameWithoutExtension.ifEmpty { "file" }
        call.respondZip("$base.zip") { zos ->
            if (f.isDirectory) zipDir(f, f.name, zos) else zipFile(f, f.name, zos)
        }
    }

    private suspend fun handleZipPost(call: ApplicationCall) {
        val body = jsonBody(call)
        if (body == null) {
            call.bad("参数错误")
            return
        }
        val arr = body.optJSONArray("paths") ?: JSONArray()
        val files = (0 until arr.length()).mapNotNull { resolve(arr.optString(it)) }.filter { it.exists() }
        if (files.isEmpty()) {
            call.bad("没有可下载的内容")
            return
        }
        call.respondZip("共享文件.zip") { zos ->
            val used = HashSet<String>()
            for (f in files) {
                var n = f.name
                var k = 1
                while (!used.add(n)) {
                    n = f.name + " (" + k++ + ")"
                }
                if (f.isDirectory) zipDir(f, n, zos) else zipFile(f, n, zos)
            }
        }
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        class Tmp(val tmpFile: File, val rel: String)
        val fields = HashMap<String, String>()
        val temps = ArrayList<Tmp>()
        try {
            val multipart = call.receiveMultipart()
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name != null) fields[part.name!!] = part.value
                        }
                        is PartData.FileItem -> {
                            val tmp = File.createTempFile("upl", ".part", cacheDir)
                            part.streamProvider().use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output, 65536) }
                            }
                            temps.add(Tmp(tmp, part.originalFileName ?: tmp.name))
                        }
                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }
        } catch (e: Exception) {
            temps.forEach { it.tmpFile.delete() }
            call.bad("上传失败：" + (e.message ?: "未知错误"))
            return
        }

        val target = resolve(fields["path"] ?: "")
        if (target == null || !target.isDirectory) {
            temps.forEach { it.tmpFile.delete() }
            call.bad("目标目录不存在")
            return
        }

        val saved = ArrayList<String>()
        try {
            synchronized(lock) {
                for (t in temps) {
                    val parts = sanitizeRel(t.rel)
                    if (parts.isEmpty()) {
                        t.tmpFile.delete()
                        continue
                    }
                    val name = parts.last()
                    val parent = if (parts.size > 1) {
                        val d = File(target, parts.subList(0, parts.size - 1).joinToString(File.separator))
                        d.mkdirs()
                        d
                    } else {
                        target
                    }
                    val dest = uniqueFile(parent, name)
                    moveTo(t.tmpFile, dest)
                    saved.add(dest.name)
                }
            }
            call.respondText(
                JSONObject().put("ok", true).put("saved", saved.size).toString(),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.bad("保存失败：" + (e.message ?: "未知错误"))
        } finally {
            temps.forEach { if (it.tmpFile.exists()) it.tmpFile.delete() }
        }
    }

    private suspend fun handleMkdir(call: ApplicationCall) {
        val body = jsonBody(call)
        if (body == null) {
            call.bad("参数错误")
            return
        }
        val dir = resolve(body.optString("dir"))
        val name = sanitizeName(body.optString("name"))
        if (dir == null || !dir.isDirectory || name == null) {
            call.bad("目录不存在或名称不合法")
            return
        }
        val created: File = synchronized(lock) {
            val d = uniqueFile(dir, name)
            d.mkdirs()
            d
        }
        if (!created.isDirectory) {
            call.bad("创建失败")
            return
        }
        call.respondText(
            JSONObject().put("ok", true).put("name", created.name).toString(),
            ContentType.Application.Json
        )
    }

    private suspend fun handleNewFile(call: ApplicationCall) {
        val body = jsonBody(call)
        if (body == null) {
            call.bad("参数错误")
            return
        }
        val dir = resolve(body.optString("dir"))
        val name = sanitizeName(body.optString("name"))
        val content = body.optString("content")
        if (dir == null || !dir.isDirectory || name == null) {
            call.bad("目录不存在或名称不合法")
            return
        }
        if (content.toByteArray(Charsets.UTF_8).size > maxTextSize) {
            call.bad("文本内容超过 5MB 限制")
            return
        }
        val f: File = synchronized(lock) {
            val u = uniqueFile(dir, name)
            u.writeText(content, Charsets.UTF_8)
            u
        }
        call.respondText(
            JSONObject().put("ok", true).put("name", f.name).toString(),
            ContentType.Application.Json
        )
    }

    private suspend fun handleSave(call: ApplicationCall) {
        val body = jsonBody(call)
        if (body == null) {
            call.bad("参数错误")
            return
        }
        val f = resolve(body.optString("path"))
        val content = body.optString("content")
        if (f == null || !f.isFile) {
            call.bad("文件不存在")
            return
        }
        if (content.toByteArray(Charsets.UTF_8).size > maxTextSize) {
            call.bad("文本内容超过 5MB 限制")
            return
        }
        f.writeText(content, Charsets.UTF_8)
        call.respondText(
            JSONObject().put("ok", true).toString(),
            ContentType.Application.Json
        )
    }

    private suspend fun handleRename(call: ApplicationCall) {
        val body = jsonBody(call)
        if (body == null) {
            call.bad("参数错误")
            return
        }
        val f = resolve(body.optString("path"))
        val name = sanitizeName(body.optString("name"))
        if (f == null || !f.exists() || name == null) {
            call.bad("路径不存在或名称不合法")
            return
        }
        val dest = File(f.parentFile, name)
        if (dest.exists()) {
            call.bad("同名文件/文件夹已存在")
            return
        }
        if (!f.renameTo(dest)) {
            call.bad("重命名失败")
            return
        }
        call.respondText(
            JSONObject().put("ok", true).put("name", dest.name).toString(),
            ContentType.Application.Json
        )
    }

    private suspend fun handleDelete(call: ApplicationCall) {
        val body = jsonBody(call)
        if (body == null) {
            call.bad("参数错误")
            return
        }
        val arr = body.optJSONArray("paths") ?: JSONArray()
        var n = 0
        for (i in 0 until arr.length()) {
            val f = resolve(arr.optString(i))
            if (f != null && f.exists() && f.absolutePath != root.canonicalFile.absolutePath) {
                if (f.deleteRecursively()) n++
            }
        }
        call.respondText(
            JSONObject().put("ok", true).put("deleted", n).toString(),
            ContentType.Application.Json
        )
    }

    // ---------- 工具 ----------

    private suspend fun ApplicationCall.respondZip(name: String, body: (ZipOutputStream) -> Unit) {
        response.headers.append(
            HttpHeaders.ContentDisposition,
            "attachment; filename*=UTF-8''" + enc(name)
        )
        respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType = ContentType.Application.OctetStream
            override suspend fun writeTo(channel: ByteWriteChannel) {
                val out = channel.toOutputStream()
                ZipOutputStream(out, Charsets.UTF_8).use { zos -> body(zos) }
            }
        })
    }

    private suspend fun ApplicationCall.bad(msg: String) {
        respondText(
            JSONObject().put("error", msg).toString(),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
    }

    private suspend fun jsonBody(call: ApplicationCall): JSONObject? {
        return try {
            JSONObject(call.receiveText())
        } catch (e: Exception) {
            null
        }
    }

    /** 把相对路径安全地解析为 root 内的文件，防止目录穿越 */
    private fun resolve(rel: String?): File? {
        if (rel == null) return null
        val r = root.canonicalFile
        val clean = rel.replace('\\', '/').trim('/')
        if (clean.isEmpty()) return r
        val rawParts = clean.split('/').filter { it.isNotEmpty() }
        val parts = rawParts.filter { it != "." && it != ".." }
        if (parts.size != rawParts.size) return null
        var f = r
        for (p in parts) f = File(f, p)
        val c = f.canonicalFile
        return if (c.path == r.path || c.path.startsWith(r.path + File.separator)) c else null
    }

    private fun sanitizeName(n: String?): String? {
        if (n == null) return null
        val t = n.trim()
        if (t.isEmpty() || t == "." || t == "..") return null
        if (t.contains('/') || t.contains('\\') || t.contains(':')) return null
        return t
    }

    private fun sanitizeRel(rel: String): List<String> {
        return rel.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." && !it.contains(':') }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var k = 1
        while (f.exists()) {
            f = File(dir, base + " (" + k + ")" + ext)
            k++
        }
        return f
    }

    private fun moveTo(src: File, dest: File) {
        if (src.renameTo(dest)) return
        src.copyTo(dest, overwrite = true)
        src.delete()
    }

    private fun zipFile(f: File, entryName: String, zos: ZipOutputStream) {
        val e = ZipEntry(entryName)
        e.time = f.lastModified()
        zos.putNextEntry(e)
        f.inputStream().use { it.copyTo(zos, 8192) }
        zos.closeEntry()
    }

    private fun zipDir(dir: File, prefix: String, zos: ZipOutputStream) {
        val list = dir.listFiles() ?: return
        if (list.isEmpty()) {
            val e = ZipEntry("$prefix/")
            e.time = dir.lastModified()
            zos.putNextEntry(e)
            zos.closeEntry()
            return
        }
        list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
            if (f.isDirectory) zipDir(f, prefix + "/" + f.name, zos)
            else zipFile(f, prefix + "/" + f.name, zos)
        }
    }

    private fun enc(s: String): String {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    private val mimeMap = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "heic" to "image/heic",
        "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
        "m4v" to "video/x-m4v", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
        "flac" to "audio/flac", "aac" to "audio/aac", "amr" to "audio/amr",
        "txt" to "text/plain", "log" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "xml" to "text/xml", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "js" to "text/javascript", "pdf" to "application/pdf",
        "apk" to "application/vnd.android.package-archive"
    )

    private fun mimeOf(name: String): ContentType {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ContentType.parse(mimeMap[ext] ?: "application/octet-stream")
    }
}
