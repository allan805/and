package com.hermesandroid.bridge.service

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Hermes / WATrader 屏幕唤醒 Activity。
 *
 * 作用：
 *   1. 点亮熄灭的屏幕
 *   2. 允许 Activity 在锁屏状态下启动
 *   3. 不绕过 Android Keyguard
 *   4. PIN / 密码仍由系统正常处理
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 8.1+
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // 兼容旧版本
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        /*
         * 不调用 requestDismissKeyguard()。
         *
         * 我们这里只负责：
         *
         *      OFF → ON
         *
         * 如果手机有 PIN / 密码：
         *
         *      ON → 正常锁屏界面
         *
         * 后续由 AccessibilityService 正常点击 PIN 键。
         */

        window.decorView.postDelayed({
            if (!isFinishing) {
                finish()
            }
        }, 500)
    }
}
