package com.siddharth.factoryattendance.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.FileProvider
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeeklyReportWorker(
    private val ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    override fun doWork(): Result {
        return try {
            val dbHelper = AttendanceDbHelper(ctx)
            val file = buildSummaryCsv(dbHelper)

            showNotificationToShare(file)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun buildSummaryCsv(dbHelper: AttendanceDbHelper): File {
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT
                COALESCE(w.display_name,'Unknown') AS name,
                COALESCE(w.code,'') AS code,
                DATE(p.timestamp / 1000, 'unixepoch', 'localtime') AS work_date,
                MIN(CASE WHEN p.type = 'IN'  THEN p.timestamp END)  AS in_time,
                MAX(CASE WHEN p.type = 'OUT' THEN p.timestamp END) AS out_time
            FROM punches p
            LEFT JOIN workers w ON w.id = p.worker_id
            GROUP BY w.id, work_date
            ORDER BY work_date, name
            """.trimIndent(),
            null
        )

        val fileName = "weekly_attendance_${System.currentTimeMillis()}.csv"
        val file = File(ctx.getExternalFilesDir(null), fileName)

        FileWriter(file).use { writer ->
            writer.append("Name,Code,Date,InTime,OutTime,WorkDuration\n")

            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val code = cursor.getString(1)
                val date = cursor.getString(2)
                val inTs = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val outTs = if (cursor.isNull(4)) 0L else cursor.getLong(4)

                val inTime = if (inTs > 0) fmt.format(Date(inTs)) else ""
                val outTime = if (outTs > 0) fmt.format(Date(outTs)) else ""
                val duration =
                    if (inTs > 0 && outTs > inTs) {
                        val diff = outTs - inTs
                        val hrs = diff / (1000 * 60 * 60)
                        val mins = (diff / (1000 * 60)) % 60
                        "%02dh %02dm".format(hrs, mins)
                    } else ""

                writer.append("\"$name\",\"$code\",\"$date\",\"$inTime\",\"$outTime\",\"$duration\"\n")
            }
        }

        cursor.close()
        return file
    }

    private fun showNotificationToShare(file: File) {
        val channelId = "weekly_report"

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Weekly Report", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Weekly Attendance Report")
            putExtra(Intent.EXTRA_TEXT, "Weekly CSV attached.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Send weekly report")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pending = PendingIntentCompat.getActivity(
            ctx,
            1002,
            chooser,
            0,
            true
        )

        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Weekly Attendance Report Ready")
            .setContentText("Tap to send CSV via Email/WhatsApp")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        nm.notify(2002, notif)
    }
}