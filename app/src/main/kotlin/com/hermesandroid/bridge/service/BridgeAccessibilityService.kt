package com.hermesandroid.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BridgeA11yService"

        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        fun isEnabledInSettings(
            context: android.content.Context
        ): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val expected =
                "${context.packageName}/$SERVICE_CLASS_NAME"

            return enabled
                .split(':')
                .any {
                    it.equals(
                        expected,
                        ignoreCase = true
                    )
                }
        }

        private const val SERVICE_CLASS_NAME =
            "com.hermesandroid.bridge.service.BridgeAccessibilityService"
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            com.hermesandroid.bridge.event.EventStore.add(event)
        }
    }

    private var isForeground = false
    private var foregroundTypes = 0

    fun startForeground(includeMediaProjection: Boolean = false) {
        val requestedTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (includeMediaProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
        } else {
            0
        }

        if (isForeground && (foregroundTypes and requestedTypes) == requestedTypes) return

        val channelId = "hermes_bridge_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Hermes Bridge",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hermes Bridge running"
            }
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
                .setContentTitle("Hermes Bridge")
                .setContentText("Connected to server")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("Hermes Bridge")
                .setContentText("Connected to server")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "Starting foreground service with types=$requestedTypes")
            startForeground(1, notification, requestedTypes)
        } else {
            startForeground(1, notification)
        }
        isForeground = true
        foregroundTypes = requestedTypes
    }

    fun stopForeground() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
        foregroundTypes = 0
    }

    override fun onInterrupt() {
        // No-op
    }

    /**
     * Ask Android to disable this AccessibilityService.
     *
     * Android 官方 AccessibilityService API。
     * 不需要 ADB/root。
     */
    fun disableSelfForIdle() {
        Log.i(
            TAG,
            "Disabling Accessibility service for idle state"
        )
        disableSelf()
    }

    /**
     * 打开 Android Accessibility 设置页面。
     *
     * Android 仍要求用户明确开启服务，
     * 不在后台偷偷授予 Accessibility。
     */
    fun openAccessibilitySettings() {
        val intent = android.content.Intent(
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        ).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }

        startActivity(intent)
    }


    override fun onDestroy() {
        com.hermesandroid.bridge.executor.ActionExecutor.shutdownTts()
        instance = null
        super.onDestroy()
    }


    /**
     * 点亮屏幕。
     *
     * 通过启动 WakeActivity 使用 Android 官方
     * setTurnScreenOn/showWhenLocked 机制。
     *
     * 注意：
     * - 不绕过 Keyguard
     * - 安全 PIN/密码仍然需要输入
     */
    fun wakeScreen(): Boolean {
        return try {
            android.util.Log.i(
                "HermesWake",
                "wakeScreen: starting PowerManager wake"
            )

            val powerManager =
                getSystemService(android.content.Context.POWER_SERVICE) as PowerManager

            /*
             * 真正唤醒 Android 屏幕。
             *
             * 这里只负责：
             *   Dozing -> Awake
             *
             * 不解除 Keyguard，
             * 不绕过 PIN，
             * 不调用 requestDismissKeyguard()。
             */
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "HermesBridge:WakeScreen"
            )

            wakeLock.acquire(3000L)

            android.util.Log.i(
                "HermesWake",
                "wakeScreen: WakeLock acquired, interactive=${powerManager.isInteractive}"
            )

            /*
             * 屏幕唤醒后启动 WakeActivity。
             *
             * WakeActivity 不解除锁屏，
             * 因此系统 PIN 仍然正常显示。
             */
            val intent = android.content.Intent(
                this,
                WakeActivity::class.java
            ).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_NO_HISTORY
                )
            }

            startActivity(intent)

            android.util.Log.i(
                "HermesWake",
                "wakeScreen: WakeActivity started"
            )

            true
        } catch (e: Exception) {
            android.util.Log.e(
                "HermesWake",
                "wakeScreen failed",
                e
            )
            false
        }
    }

}
