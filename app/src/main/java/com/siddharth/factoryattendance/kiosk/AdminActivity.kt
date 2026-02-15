package com.siddharth.factoryattendance.kiosk

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class AdminActivity : AppCompatActivity() {

    private lateinit var dbHelper: AttendanceDbHelper

    private lateinit var reportText: TextView
    private lateinit var btnRegisterCard: Button
    private lateinit var btnSendReport: Button
    private lateinit var btnBattery: Button
    private lateinit var btnAddIn: Button
    private lateinit var btnAddOut: Button

    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isAuthorized = intent.getBooleanExtra("ADMIN_AUTH", false)
        if (!isAuthorized) {
            finish()
            return
        }

        setContentView(R.layout.activity_admin)
        AppState.isAdminModeActive = true

        dbHelper = AttendanceDbHelper(this)

        reportText = findViewById(R.id.reportText)
        btnRegisterCard = findViewById(R.id.btnRegisterCard)
        btnSendReport = findViewById(R.id.btnSendReport)
        btnBattery = findViewById(R.id.btnBattery)
        btnAddIn = findViewById(R.id.btnAddIn)
        btnAddOut = findViewById(R.id.btnAddOut)

        btnRegisterCard.setOnClickListener { showRegisterCardDialog() }
        btnSendReport.setOnClickListener { exportAndShareReport() }
        btnBattery.setOnClickListener { showBatteryStatus() }
        btnAddIn.setOnClickListener { showManualPunchDialog(PunchType.IN) }
        btnAddOut.setOnClickListener { showManualPunchDialog(PunchType.OUT) }

        reportText.text = "✅ Admin Ready"
        reportText.setTextColor(Color.WHITE)
    }

    private fun normalizeUid(rawUid: String): String {
        return rawUid.trim().replace(Regex("[^0-9]"), "")
    }

    private fun showRegisterCardDialog() {
        val codeInput = EditText(this).apply { hint = "EMP Code (e.g. EMP-019)" }
        val nameInput = EditText(this).apply { hint = "Display Name (e.g. Pooja)" }
        val uidInput = EditText(this).apply {
            hint = "Tap UID here then scan card"
            isFocusableInTouchMode = true
            requestFocus()
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(codeInput)
            addView(nameInput)
            addView(uidInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Register New Card")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                val uid = normalizeUid(uidInput.text.toString())

                if (code.isBlank() || name.isBlank() || uid.isBlank()) {
                    toast("❌ Code/Name/UID required")
                    return@setPositiveButton
                }

                registerOrUpdateWorker(code, name, uid)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun registerOrUpdateWorker(code: String, displayName: String, rfidUid: String) {
        dbExecutor.execute {
            val db = dbHelper.writableDatabase
            try {
                db.beginTransaction()

                try {
                    db.execSQL(
                        """
                        INSERT INTO workers (code, display_name, rfid_uid)
                        VALUES (?, ?, ?)
                        ON CONFLICT(rfid_uid) DO UPDATE SET
                            code = excluded.code,
                            display_name = excluded.display_name
                        """.trimIndent(),
                        arrayOf(code, displayName, rfidUid)
                    )
                } catch (e: Exception) {
                    Log.w("ADMIN", "Insert by UID failed, trying update-by-code", e)
                    db.execSQL(
                        """
                        UPDATE workers
                        SET display_name = ?, rfid_uid = ?
                        WHERE code = ?
                        """.trimIndent(),
                        arrayOf(displayName, rfidUid, code)
                    )
                }

                db.setTransactionSuccessful()

                runOnUiThread {
                    reportText.text = "✅ Registered: $displayName ($code)\nUID=$rfidUid"
                    reportText.setTextColor(Color.GREEN)
                }

            } catch (e: Exception) {
                Log.e("ADMIN", "Register failed", e)
                runOnUiThread {
                    reportText.text = "❌ Register failed: ${e.message}"
                    reportText.setTextColor(Color.RED)
                }
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }

    private fun showManualPunchDialog(type: PunchType) {
        val reasonInput = EditText(this).apply { hint = "Reason (required)" }

        AlertDialog.Builder(this)
            .setTitle("Manual ${type.name}")
            .setView(reasonInput)
            .setPositiveButton("Confirm") { _, _ ->
                val reason = reasonInput.text.toString().trim()
                if (reason.isBlank()) {
                    toast("❌ Reason required")
                    return@setPositiveButton
                }
                addManualPunch(type, reason)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addManualPunch(type: PunchType, reason: String) {
        dbExecutor.execute {
            val now = System.currentTimeMillis()
            val db = dbHelper.writableDatabase
            try {
                db.beginTransaction()

                val c = db.rawQuery("SELECT id FROM workers ORDER BY id LIMIT 1", null)
                val workerId = if (c.moveToFirst()) c.getInt(0) else null
                c.close()

                if (workerId == null) {
                    runOnUiThread { toast("❌ No workers in DB") }
                    return@execute
                }

                db.execSQL(
                    "INSERT INTO punches (worker_id, type, timestamp) VALUES (?, ?, ?)",
                    arrayOf(workerId, type.name, now)
                )
                db.execSQL(
                    "INSERT INTO audit_log (event_type, worker_id, details, timestamp) VALUES (?, ?, ?, ?)",
                    arrayOf("MANUAL_${type.name}", workerId, reason, now)
                )

                db.setTransactionSuccessful()

                runOnUiThread {
                    reportText.text = "✅ Manual ${type.name} saved @ ${fmt.format(Date(now))}"
                    reportText.setTextColor(Color.GREEN)
                }
            } catch (e: Exception) {
                Log.e("ADMIN", "Manual punch failed", e)
                runOnUiThread {
                    reportText.text = "❌ Manual failed: ${e.message}"
                    reportText.setTextColor(Color.RED)
                }
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }

    private fun exportAndShareReport() {
        dbExecutor.execute {
            try {
                val csvFile = buildSummaryCsv()
                runOnUiThread { shareCsv(csvFile) }
            } catch (e: Exception) {
                Log.e("CSV", "Export failed", e)
                runOnUiThread {
                    reportText.text = "❌ Export failed: ${e.message}"
                    reportText.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun buildSummaryCsv(): File {
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

        val dir = getExternalFilesDir(null) ?: throw IllegalStateException("external files dir is null")
        val fileName = "attendance_summary_${System.currentTimeMillis()}.csv"
        val file = File(dir, fileName)

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
        Log.d("CSV", "Built report at: ${file.absolutePath}")
        return file
    }

    private fun shareCsv(file: File) {

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"

            // ✅ Auto-fill recipient emails
            putExtra(
                Intent.EXTRA_EMAIL,
                arrayOf(
                    "accounts@eecfilters.com",
                    "eecfilters3@gmail.com",
                    "siddharthpersonal1506@gmail.com"
                )
            )

            putExtra(Intent.EXTRA_SUBJECT, "Factory Attendance Report")
            putExtra(Intent.EXTRA_TEXT, "Attendance CSV attached.")
            putExtra(Intent.EXTRA_STREAM, uri)

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(sendIntent, "Send report via"))
    }

    private fun showBatteryStatus() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        AlertDialog.Builder(this)
            .setTitle("Battery Status")
            .setMessage("Battery: $level%")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        AppState.isAdminModeActive = false
        try { dbExecutor.shutdown() } catch (_: Exception) {}
    }
}