package com.lanshare.filesync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.lanshare.filesync.compress.PhotoPackager
import com.lanshare.filesync.util.FileIo
import java.io.File
import kotlin.concurrent.thread

/** 选照片 → 压缩 → 打包 ZIP → 存入共享目录（电脑端即可下载） */
class CompressActivity : Activity() {

    companion object {
        private const val PICK_IMAGES = 11
        private const val LEGACY_PERM_REQUEST = 12
        private const val PREFS = "settings"
        private const val KEY_ROOT = "root_path"
        private val EDGES = intArrayOf(1280, 1600, 1920, 2560)
    }

    private lateinit var dirText: TextView
    private lateinit var pickBtn: Button
    private lateinit var countText: TextView
    private lateinit var fileContainer: LinearLayout
    private lateinit var targetKbText: TextView
    private lateinit var targetKbBar: SeekBar
    private lateinit var edgeSpinner: Spinner
    private lateinit var seqCheck: CheckBox
    private lateinit var subDirEdit: EditText
    private lateinit var startBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var resultText: TextView

    private val uris = ArrayList<Uri>()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compress)

        dirText = findViewById(R.id.dirText)
        pickBtn = findViewById(R.id.pickBtn)
        countText = findViewById(R.id.countText)
        fileContainer = findViewById(R.id.fileContainer)
        targetKbText = findViewById(R.id.targetKbText)
        targetKbBar = findViewById(R.id.targetKbBar)
        edgeSpinner = findViewById(R.id.edgeSpinner)
        seqCheck = findViewById(R.id.seqCheck)
        subDirEdit = findViewById(R.id.subDirEdit)
        startBtn = findViewById(R.id.startBtn)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        resultText = findViewById(R.id.resultText)

        dirText.text = sharedRoot()

        edgeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            EDGES.map { "$it px" }
        )
        edgeSpinner.setSelection(2) // 默认 1920

        targetKbBar.max = 8
        targetKbBar.progress = 2 // 默认 200KB
        targetKbBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = updateKbText()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        updateKbText()

        pickBtn.setOnClickListener { pickImages() }
        startBtn.setOnClickListener { start() }
    }

    private fun updateKbText() {
        targetKbText.text = "单张目标体积：${targetKb()} KB"
    }

    private fun targetKb(): Int = 100 + targetKbBar.progress * 50

    private fun maxEdge(): Int = EDGES[edgeSpinner.selectedItemPosition.coerceIn(EDGES.indices)]

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun sharedRoot(): String {
        val p = prefs().getString(KEY_ROOT, null)
        return if (p.isNullOrEmpty()) Environment.getExternalStorageDirectory().absolutePath else p
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(i, "选择照片"), PICK_IMAGES)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGES || resultCode != RESULT_OK) return

        uris.clear()
        val clip = data?.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(k).uri)
            }
        } else {
            data?.data?.let { uris.add(it) }
        }

        if (uris.size > PhotoPackager.MAX_PICK) {
            Toast.makeText(this, "最多选择 ${PhotoPackager.MAX_PICK} 张，已自动截取前 ${PhotoPackager.MAX_PICK} 张", Toast.LENGTH_LONG).show()
            val sub = ArrayList(uris.subList(0, PhotoPackager.MAX_PICK))
            uris.clear()
            uris.addAll(sub)
        }
        refreshList()
    }

    private fun refreshList() {
        countText.text = "已选 ${uris.size} 张（上限 ${PhotoPackager.MAX_PICK}）"
        fileContainer.removeAllViews()
        uris.forEach { uri ->
            val name = FileIo.displayName(this, uri) ?: (uri.lastPathSegment ?: "图片")
            val size = FileIo.formatBytes(FileIo.sizeOf(this, uri))
            val tv = TextView(this)
            tv.text = "• $name（$size）"
            tv.textSize = 12f
            tv.setTextColor(0xFF666666.toInt())
            fileContainer.addView(tv)
        }
    }

    private fun start() {
        if (working) return
        if (uris.isEmpty()) {
            Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasStoragePermission()) {
            Toast.makeText(this, "需要先授予文件访问权限才能写入共享目录", Toast.LENGTH_SHORT).show()
            requestStoragePermission()
            return
        }

        working = true
        startBtn.isEnabled = false
        pickBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "准备中…"
        resultText.text = ""

        val cfg = PhotoPackager.Config(
            targetKb = targetKb(),
            maxEdge = maxEdge(),
            sequentialNames = seqCheck.isChecked,
            subDir = subDirEdit.text.toString()
        )
        val root = File(sharedRoot())
        val snapshot = ArrayList(uris)

        thread {
            try {
                val r = PhotoPackager.pack(this, cacheDir, root, snapshot, cfg) { percent, text ->
                    handler.post {
                        progressBar.progress = percent
                        progressText.text = "$percent%  $text"
                    }
                }
                handler.post { onDone(r) }
            } catch (e: Exception) {
                handler.post { onError(e) }
            }
        }
    }

    private fun onDone(r: PhotoPackager.Result) {
        working = false
        startBtn.isEnabled = true
        pickBtn.isEnabled = true
        progressBar.progress = 100

        val saved = r.totalOriginal - r.totalCompressed
        val sb = StringBuilder()
        sb.append("✓ 已生成：${r.zipFile.name}\n")
        sb.append("位置：${r.zipFile.parent ?: ""}\n")
        sb.append("${r.count} 张，${FileIo.formatBytes(r.totalOriginal)} → ${FileIo.formatBytes(r.totalCompressed)}")
        if (saved > 0) sb.append("（省下 ${FileIo.formatBytes(saved)}）")
        sb.append("\n压缩包：${FileIo.formatBytes(r.zipBytes)}")
        if (r.skipped.isNotEmpty()) {
            sb.append("\n\n跳过 ${r.skipped.size} 张：\n").append(r.skipped.joinToString("\n"))
        }
        sb.append("\n\n电脑端：确保首页服务已启动，浏览器打开 http://手机IP:8080 刷新即可看到并下载。")
        resultText.setTextColor(0xFF2E7D32.toInt())
        resultText.text = sb.toString()

        if (!ServerService.isRunning) {
            Toast.makeText(this, "压缩包已生成，返回首页启动服务后电脑即可下载", Toast.LENGTH_LONG).show()
        }
    }

    private fun onError(e: Exception) {
        working = false
        startBtn.isEnabled = true
        pickBtn.isEnabled = true
        progressBar.visibility = View.GONE
        progressText.text = ""
        resultText.setTextColor(0xFFC62828.toInt())
        resultText.text = "打包失败：${e.message ?: e.javaClass.simpleName}"
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    Toast.makeText(this, "请在 系统设置 → 应用管理 中手动开启\"所有文件访问\"", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                LEGACY_PERM_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LEGACY_PERM_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "已授权，可再次点击开始", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
