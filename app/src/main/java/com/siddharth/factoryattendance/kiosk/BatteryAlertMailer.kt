package com.siddharth.factoryattendance.kiosk

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Date

object BatteryAlertMailer {

    private var lastSentAt: Long = 0
    private const val COOLDOWN_MS = 60 * 60 * 1000 // 1 hour

    private const val ALERT_EMAIL = "siddharthpersonal1506@gmail.com"

    /**
     * Call this when:
     * 1) Battery < 20% (auto)
     * 2) Admin taps "Battery Status" (manual)
     */
    fun sendLowBatteryEmail(context: Context, percent: Int, force: Boolean = false) {

        val now = System.currentTimeMillis()

        // Cooldown protection (skip only if NOT forced)
        if (!force && now - lastSentAt < COOLDOWN_MS) {
            Log.d("BATTERY", "Email already sent recently, skipping")
            return
        }

        lastSentAt = now

        val subject = "⚠️ FACTORY KIOSK LOW BATTERY ALERT"

        val body = """
            🚨 BATTERY ALERT 🚨
            
            Factory Attendance Kiosk battery is LOW.
            
            Battery Level : $percent%
            
            Please connect charger immediately to avoid data loss.
            
            Device Model : ${android.os.Build.MODEL}
            Android Ver  : ${android.os.Build.VERSION.RELEASE}
            Time         : ${Date()}
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ALERT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(
                Intent.createChooser(intent, "Send battery alert via")
            )
        } catch (e: Exception) {
            Log.e("BATTERY", "No email app available", e)
        }
    }
}
