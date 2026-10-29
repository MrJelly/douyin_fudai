package com.jelly.fudai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.concurrent.TimeUnit

class AccessibilityRecoveryService : Service() {
    companion object {
        private const val CHANNEL_ID = "accessibility_recovery"
        private const val NOTIFICATION_ID = 7314
        private const val HEARTBEAT_FILE = "accessibility.heartbeat"
        private const val COMPONENT = "com.jelly.fudai/.FudaiAccessibilityService"
        private const val SETTINGS = "fudai_settings"
        private const val AUTOMATION_WANTED = "automation_wanted"

        fun start(context: Context) {
            val intent = Intent(context, AccessibilityRecoveryService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun writeHeartbeat(context: Context) {
            try {
                File(context.noBackupFilesDir, HEARTBEAT_FILE).writeText(System.currentTimeMillis().toString())
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var staleChecks = 0
    private var lastRepairAt = 0L
    private var securePermissionGrantAttempted = false

    private val check = object : Runnable {
        override fun run() {
            checkAndRepair()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(check)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun protectRecoveryProcess() {
        Thread({
            try {
                val pid = android.os.Process.myPid()
                val process = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "echo -850 > /proc/$pid/oom_score_adj; renice -5 -p $pid"))
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
            } catch (_: Throwable) {
            }
        }, "recovery-process-protection").start()
    }

    private fun checkAndRepair() {
        val automationWanted = getSharedPreferences(SETTINGS, MODE_PRIVATE)
            .getBoolean(AUTOMATION_WANTED, false)
        if (!automationWanted) {
            staleChecks = 0
            stopSelf()
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            // Never open Magisk/superuser prompts from a background watchdog.
            // Without the optional permission the accessibility service keeps
            // using Android's normal lifecycle and manual settings fallback.
            stopSelf()
            return
        }
        val services = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val requested = services.split(':').any { it.equals(COMPONENT, ignoreCase = true) }
        val enabled = Settings.Secure.getInt(
            contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        // Removing the component in Android's accessibility settings is an
        // explicit user action. Never fight the user by silently enabling it
        // again; also cancel unattended automation until they start it again.
        if (!requested || !enabled) {
            getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(AUTOMATION_WANTED, false).apply()
            staleChecks = 0
            stopSelf()
            return
        }
        val heartbeat = File(noBackupFilesDir, HEARTBEAT_FILE)
        val heartbeatFresh = heartbeat.exists() &&
            System.currentTimeMillis() - heartbeat.lastModified() < 45_000L
        staleChecks = if (heartbeatFresh) 0 else staleChecks + 1
        if (staleChecks < 2 || SystemClock.elapsedRealtime() - lastRepairAt < 60_000L) return
        staleChecks = 0
        lastRepairAt = SystemClock.elapsedRealtime()
        repair(services)
    }

    private fun requestSecureSettingsPermissionFromRoot() {
        if (securePermissionGrantAttempted) return
        securePermissionGrantAttempted = true
        Thread({
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"
                    )
                )
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
                handler.post { checkAndRepair() }
            } catch (_: Throwable) {
                // A non-root phone cannot repair accessibility settings by
                // itself. The regular Android accessibility setting remains
                // the user's fallback and no repeated root prompts are made.
            }
        }, "grant-secure-settings").start()
    }

    private fun repair(originalServices: String) {
        try {
            val withoutOwn = originalServices.split(':')
                .filter { it.isNotBlank() && !it.equals(COMPONENT, ignoreCase = true) }
                .joinToString(":")
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                withoutOwn.ifBlank { null }
            )
            if (withoutOwn.isBlank()) {
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
            handler.postDelayed({
                try {
                    val current = Settings.Secure.getString(
                        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ).orEmpty()
                    val restored = current.split(':')
                        .filter { it.isNotBlank() }
                        .toMutableList()
                        .apply { if (none { it.equals(COMPONENT, true) }) add(COMPONENT) }
                        .joinToString(":")
                    Settings.Secure.putString(
                        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restored
                    )
                    Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (_: Exception) {
                }
            }, 800L)
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "无障碍自动恢复", NotificationManager.IMPORTANCE_LOW).apply {
                description = "防止系统清理后无障碍服务失效"
            }
        )
    }

    @Suppress("DEPRECATION") // Required only for the pre-Android O constructor branch.
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("抢福袋守护运行中")
            .setContentText("无障碍掉线时将自动恢复")
            .setOngoing(true)
            .build()
    }
}
