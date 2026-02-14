package com.siddharth.factoryattendance.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log

class BatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        if (level < 0 || scale <= 0) return

        if (level in 1..20) {
            BatteryAlertMailer.sendLowBatteryEmail(context, level)
        }

        val percent = (level * 100) / scale
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

        Log.d("BATTERY", "Battery=$percent% charging=$isCharging")

        // 🔥 Trigger ONLY when battery is low AND not charging
        if (percent <= 20 && !isCharging) {
            BatteryAlertMailer.sendLowBatteryEmail(context, percent)
        }
    }
}
