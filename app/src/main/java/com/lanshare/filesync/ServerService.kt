package com.lanshare.filesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import java.io.File

/** 前台服务：持有内嵌 HTTP 服务器与唤醒锁，保证锁屏后服务不被系统杀掉 */
class ServerService : Service() {

    companion object {
        const val ACTION_START = "com.lanshare.filesync.START"
        const val ACTION_STOP = "com.lanshare.filesync.STOP"
        const val ACTION_LOCKS = "com.lanshare.filesync.LOCKS"

        @Volatile
        var isRunning = false
            private set
    }

    private var server: LanFileServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServer()
            ACTION_LOCKS -> if (isRunning) {
                releaseLocks()
                if (prefs().getBoolean("keep_awake", true)) acquireLocks()
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun rootDir(): File {
        val p = prefs().getString("root_path", null)
        val f = if (p.isNullOrEmpty()) Environment.getExternalStorageDirectory() else File(p)
        return if (f.exists() && f.isDirectory) f else Environment.getExternalStorageDirectory()
    }

    private fun startServer() {
        if (isRunning) {
            startForegroundNotification()
            return
        }
        try {
            val html = assets.open("index.html").readBytes().toString(Charsets.UTF_8)
            val s = LanFileServer(rootDir(), 8080, html, cacheDir)
            s.start()
            server = s
            isRunning = true
            startForegroundNotification()
            if (prefs().getBoolean("keep_awake", true)) acquireLocks()
            Toast.makeText(this, "服务已启动：${rootDir().absolutePath}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            isRunning = false
            val msg = e.message ?: e.javaClass.simpleName
            Toast.makeText(this, "服务启动失败：$msg（可能是 8080 端口被占用）", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        releaseLocks()
        isRunning = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanfileshare:wake")
                .also { it.acquire() }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanfileshare:wifi")
                .also { it.acquire() }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundNotification() {
        val channelId = "file_server"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "文件共享服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("手机文件共享运行中")
            .setContentText("电脑浏览器访问 http://手机IP:8080")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "停止服务", stopIntent)

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1001, b.build())
        }
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        releaseLocks()
        isRunning = false
        super.onDestroy()
    }
}
