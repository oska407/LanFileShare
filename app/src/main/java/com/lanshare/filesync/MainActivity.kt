package com.lanshare.filesync

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    companion object {
        private const val PICK_DIR_REQUEST = 1
        private const val LEGACY_PERM_REQUEST = 2
        private const val NOTIF_PERM_REQUEST = 3
        private const val PREFS = "settings"
        private const val KEY_ROOT = "root_path"
        private const val KEY_AWAKE = "keep_awake"
    }

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var dirText: TextView
    private lateinit var permText: TextView
    private lateinit var tipText: TextView
    private lateinit var startBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var packBtn: Button
    private lateinit var permBtn: Button
    private lateinit var awakeSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunner = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        dirText = findViewById(R.id.dirText)
        permText = findViewById(R.id.permText)
        tipText = findViewById(R.id.tipText)
        startBtn = findViewById(R.id.startBtn)
        pickBtn = findViewById(R.id.pickBtn)
        packBtn = findViewById(R.id.packBtn)
        permBtn = findViewById(R.id.permBtn)
        awakeSwitch = findViewById(R.id.awakeSwitch)

        awakeSwitch.isChecked = prefs().getBoolean(KEY_AWAKE, true)
        awakeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_AWAKE, checked).apply()
            if (ServerService.isRunning) sendToService(ServerService.ACTION_LOCKS)
        }

        startBtn.setOnClickListener {
            if (ServerService.isRunning) {
                sendToService(ServerService.ACTION_STOP)
            } else {
                startServer()
            }
            handler.postDelayed({ refresh() }, 700)
        }

        pickBtn.setOnClickListener {
            if (!hasStoragePermission()) {
                Toast.makeText(this, "请先授权文件访问权限，再选择目录", Toast.LENGTH_SHORT).show()
                requestStoragePermission()
                return@setOnClickListener
            }
            startActivityForResult(Intent(this, FolderPickerActivity::class.java), PICK_DIR_REQUEST)
        }

        permBtn.setOnClickListener { requestStoragePermission() }

        packBtn.setOnClickListener {
            startActivity(Intent(this, CompressActivity::class.java))
        }

        urlText.setOnClickListener {
            val url = urlText.text.toString().split("\n").firstOrNull { it.startsWith("http") }
            if (url != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("url", url))
                Toast.makeText(this, "已复制：$url", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunner)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunner)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_DIR_REQUEST && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(FolderPickerActivity.EXTRA_PATH)
            if (!path.isNullOrEmpty()) {
                prefs().edit().putString(KEY_ROOT, path).apply()
                if (ServerService.isRunning) {
                    // 目录变更后自动重启服务
                    sendToService(ServerService.ACTION_STOP)
                    handler.postDelayed({ startServer() }, 900)
                    Toast.makeText(this, "共享目录已切换，服务重启中…", Toast.LENGTH_SHORT).show()
                }
            }
            refresh()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LEGACY_PERM_REQUEST) refresh()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun sharedRoot(): String {
        val p = prefs().getString(KEY_ROOT, null)
        return if (p.isNullOrEmpty()) Environment.getExternalStorageDirectory().absolutePath else p
    }

    private fun startServer() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "请先完成文件访问授权", Toast.LENGTH_SHORT).show()
            requestStoragePermission()
            refresh()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM_REQUEST)
            } catch (_: Exception) {
            }
        }
        sendToService(ServerService.ACTION_START)
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, ServerService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26 && action == ServerService.ACTION_START) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
                    Toast.makeText(this, "请在 系统设置 → 应用管理 → 手机文件共享 中手动开启\"所有文件访问\"", Toast.LENGTH_LONG).show()
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

    private fun refresh() {
        val running = ServerService.isRunning
        statusText.text = if (running) "● 服务运行中" else "○ 服务已停止"
        statusText.setTextColor(if (running) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
        startBtn.text = if (running) "停止服务" else "启动服务"
        startBtn.setBackgroundColor(if (running) 0xFFE05C50.toInt() else 0xFF43A047.toInt())
        startBtn.setTextColor(0xFFFFFFFF.toInt())

        val urls = localUrls()
        urlText.text = if (urls.isEmpty()) {
            "未检测到局域网地址\n请确认手机已连接 Wi-Fi / 热点 / USB 网络共享"
        } else {
            urls.joinToString("\n")
        }

        dirText.text = sharedRoot()

        val ok = hasStoragePermission()
        permText.text = if (ok) "✓ 文件访问权限已授予" else "✗ 尚未授予文件访问权限（共享手机目录必须授权）"
        permText.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        permBtn.visibility = if (ok) View.GONE else View.VISIBLE

        tipText.text = "使用方法：手机和电脑连同一个 Wi-Fi（或手机开热点给电脑连接、或手机 USB 网络共享给电脑），在电脑浏览器地址栏输入上面的地址即可，端口 8080。"
    }

    private fun localUrls(): List<String> {
        val out = ArrayList<String>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return out
            val candidates = ArrayList<Triple<Int, String, String>>() // 优先级, 接口名, IP
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val name = ni.name ?: ""
                        val rank = when {
                            name.startsWith("wlan") -> 0
                            name.startsWith("eth") || name.startsWith("usb") -> 1
                            name.startsWith("ap") || name.startsWith("swlan") -> 2
                            else -> 3
                        }
                        candidates.add(Triple(rank, name, addr.hostAddress ?: continue))
                    }
                }
            }
            candidates.sortWith(compareBy({ it.first }, { it.third }))
            candidates.forEach { out.add("http://${it.third}:8080") }
        } catch (_: Exception) {
        }
        return out
    }
}
