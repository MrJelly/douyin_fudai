package com.jelly.fudai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val shouldResume = context.getSharedPreferences("fudai_settings", Context.MODE_PRIVATE)
            .getBoolean("automation_wanted", false)
        if (shouldResume) AccessibilityRecoveryService.start(context)
    }
}
