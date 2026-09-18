package io.catpaw.kittytalk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 常驻保活前台服务：
 * 由用户在设置页主动开启（前台启动无系统限制），通过前台服务提高进程优先级，
 * 防止系统在后台回收进程导致无障碍服务掉线、核心替换功能失效。
 * 所有可能抛异常的操作均做防御，保证本服务任何情况下都不会导致进程崩溃。
 */
class LifelineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundCompat()
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate 保活服务初始化异常: ${e.message}")
            // 前台通知启动失败时仍保持服务存活（普通后台服务），不崩溃
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (e: Throwable) {
            Log.e(TAG, "onStartCommand 异常: ${e.message}")
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "常驻保活", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "保持无障碍替换服务在后台稳定运行"
            }
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_tile)
            .setContentTitle("言出化喵运行中")
            .setContentText("无障碍替换服务保持运行")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // 第一优先：specialUse 类型（需 manifest 中声明对应权限与 property）
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            return
        } catch (e: Throwable) {
            Log.e(TAG, "specialUse 前台启动失败，尝试兜底: ${e.message}")
        }
        // 兜底：无类型参数（使用 manifest 声明的类型），仍失败则保持普通服务不崩溃
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            Log.e(TAG, "兜底前台启动也失败，服务降级为普通后台服务: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LifelineService"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1001

        /** 前台启动（前台状态调用无限制）。任何失败静默，不影响调用方 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, LifelineService::class.java)
                )
            } catch (e: Throwable) {
                Log.e(TAG, "startForegroundService 失败: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LifelineService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
