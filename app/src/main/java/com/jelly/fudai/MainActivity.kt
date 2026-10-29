package com.jelly.fudai

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (isAccessibilityEnabled()) AccessibilityRecoveryService.start(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(42, 64, 42, 42)
            setBackgroundColor(Color.rgb(16, 25, 35))
        }

        root.addView(text("抖音福袋助手", 27f, Color.rgb(74, 222, 232)), wide())
        root.addView(text("安卓端自动化工具", 14f, Color.LTGRAY), wide())
        status = text("", 16f, Color.WHITE)
        status.setPadding(0, 30, 0, 30)
        root.addView(status, wide())

        root.addView(button("1. 授予悬浮窗权限").apply {
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(button("2. 开启无障碍服务").apply {
            setOnClickListener {
                if (isAccessibilityEnabled()) {
                    Toast.makeText(this@MainActivity, "无障碍服务已开启", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        })
        root.addView(button("3. 显示悬浮控制面板").apply {
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else if (!isAccessibilityEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else if (FudaiAccessibilityService.instance == null) {
                    AccessibilityRecoveryService.start(this@MainActivity)
                    Toast.makeText(this@MainActivity, "服务正在连接，请稍后再试", Toast.LENGTH_SHORT).show()
                } else {
                    sendBroadcast(Intent(FudaiAccessibilityService.ACTION_SHOW_PANEL).setPackage(packageName))
                    moveTaskToBack(true)
                }
            }
        })
        setContentView(root)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityEnabled()) AccessibilityRecoveryService.start(this)
        if (::status.isInitialized) updateStatus()
    }

    private fun updateStatus() {
        val accessibility = when {
            FudaiAccessibilityService.instance != null -> "已连接"
            isAccessibilityEnabled() -> "正在连接"
            else -> "未开启"
        }
        status.text = "悬浮窗：${if (Settings.canDrawOverlays(this)) "已授权" else "未授权"}\n无障碍：$accessibility"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, FudaiAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun text(v: String, s: Float, c: Int) = TextView(this).apply {
        text = v
        textSize = s
        setTextColor(c)
    }

    private fun button(v: String) = Button(this).apply {
        text = v
        textSize = 17f
        setTextColor(Color.BLACK)
        val bg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 16f
        }
        background = bg
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 16, 0, 16)
        }
        layoutParams = params
    }

    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}

