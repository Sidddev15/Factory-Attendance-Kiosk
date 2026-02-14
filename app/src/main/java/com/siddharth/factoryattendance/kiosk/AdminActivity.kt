package com.siddharth.factoryattendance.kiosk

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class AdminActivity : AppCompatActivity() {

    private lateinit var dbHelper: AttendanceDbHelper
    private lateinit var reportText: TextView
    private lateinit var btnAddIn: Button
    private lateinit var btnAddOut: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnBattery: Button

    private val dbExecutor = Executors.newSingleThreadExecutor()

    private val autoLockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val AUTO_LOCK_MS = 60_000L

    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isAuthorized = intent.getBooleanExtra("ADMIN_AUTH", false)
        if (!isAuthorized) {
            finish()
            return
        }

        // ✅ MUST be before any kiosk logic
        AppState.isAdminModeActive = true

        // ✅ FIX 1: inflate layout FIRST
        setContentView(R.layout.activity_admin)

        // ✅ Now it is SAFE to bind views
        reportText = findViewById(R.id.reportText)
        btnAddIn = findViewById(R.id.btnAddIn)
        btnAddOut = findViewById(R.id.btnAddOut)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnBattery = findViewById(R.id.btnBattery)

        btnAddIn.setOnClickListener { showManualPunchDialog(PunchType.IN) }
        btnAddOut.setOnClickListener { showManualPunchDialog(PunchType.OUT) }
        btnExportCsv.setOnClickListener { exportCsvToDownloads() }
        btnBattery.setOnClickListener { showBatteryStatus() }

        resetAutoLock()

        dbHelper = AttendanceDbHelper(this)
        ensureDefaultWorkerExists()
        refreshReportAsync()
    }

    private fun ensureDefaultWorkerExists() {
        val db = dbHelper.writableDatabase
        db.execSQL(
            """
            INSERT OR IGNORE INTO workers (id, code, display_name, rfid_uid)
            VALUES (1, 'EMP-000', 'Siddharth', '0030676666')
            """.trimIndent()
        )
    }

    private fun showManualPunchDialog(type: PunchType) {
        val input = EditText(this)
        input.hint = "Reason (required)"

        AlertDialog.Builder(this)
            .setTitle("Manual ${type.name}")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isEmpty()) {
                    reportText.text = "❌ Reason required"
                    reportText.setTextColor(Color.RED)
                } else {
                    addManualPunchAsync(type, reason)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addManualPunchAsync(type: PunchType, reason: String) {
        reportText.text = "Saving..."
        reportText.setTextColor(Color.YELLOW)

        dbExecutor.execute {
            try {
                val db = dbHelper.writableDatabase
                val now = System.currentTimeMillis()

                val c = db.rawQuery("SELECT id FROM workers ORDER BY id LIMIT 1", null)
                if (!c.moveToFirst()) {
                    runOnUiThread {
                        reportText.text = "❌ No workers"
                        reportText.setTextColor(Color.RED)
                    }
                    c.close()
                    return@execute
                }

                val workerId = c.getInt(0)
                c.close()

                db.beginTransaction()

                db.execSQL(
                    "INSERT INTO punches (worker_id,type,timestamp) VALUES (?,?,?)",
                    arrayOf(workerId, type.name, now)
                )

                db.execSQL(
                    "INSERT INTO audit_log (event_type,worker_id,details,timestamp) VALUES (?,?,?,?)",
                    arrayOf("MANUAL_${type.name}", workerId, reason, now)
                )

                db.setTransactionSuccessful()
                db.endTransaction()

                runOnUiThread {
                    reportText.text = "✅ Saved ${type.name} @ ${fmt.format(Date(now))}"
                    reportText.setTextColor(Color.GREEN)
                }

                refreshReportAsync()

            } catch (e: Exception) {
                Log.e("ADMIN", "Manual punch failed", e)
                runOnUiThread {
                    reportText.text = "❌ Error: ${e.message}"
                    reportText.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun refreshReportAsync() {
        dbExecutor.execute {
            val db = dbHelper.readableDatabase
            val c = db.rawQuery(
                """
                SELECT COALESCE(w.display_name,'Unknown'), p.type, p.timestamp
                FROM punches p
                LEFT JOIN workers w ON w.id = p.worker_id
                ORDER BY p.timestamp DESC
                LIMIT 200
                """.trimIndent(),
                null
            )

            val sb = StringBuilder("RECENT ATTENDANCE\n\n")
            while (c.moveToNext()) {
                sb.append(
                    "${c.getString(0)} - ${c.getString(1)} @ ${fmt.format(Date(c.getLong(2)))}\n"
                )
            }
            c.close()

            runOnUiThread {
                reportText.text = sb.toString()
                reportText.setTextColor(Color.WHITE)
            }
        }
    }

    private fun exportCsvToDownloads() {
        dbExecutor.execute {
            try {
                val fileName = "attendance_${System.currentTimeMillis()}.csv"
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    android.net.Uri.fromFile(File(dir, fileName))
                } ?: throw IllegalStateException("File create failed")

                contentResolver.openOutputStream(uri)!!.use {
                    it.write("Export OK\n".toByteArray())
                }

                runOnUiThread {
                    reportText.text = "✅ CSV saved: $fileName"
                    reportText.setTextColor(Color.GREEN)
                }

            } catch (e: Exception) {
                Log.e("CSV", "Export failed", e)
                runOnUiThread {
                    reportText.text = "❌ Export failed"
                    reportText.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun showBatteryStatus() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        BatteryAlertMailer.sendLowBatteryEmail(this, level, force = true)

        AlertDialog.Builder(this)
            .setTitle("Battery Status")
            .setMessage("Battery: $level%")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun resetAutoLock() {
        autoLockHandler.removeCallbacksAndMessages(null)
        autoLockHandler.postDelayed({ finish() }, AUTO_LOCK_MS)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        resetAutoLock()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppState.isAdminModeActive = false
        dbExecutor.shutdown()
    }
}
