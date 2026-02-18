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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook

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

    // Shift rules
    private val shiftStart = LocalTime.of(10, 0)      // 10:00 AM
    private val lateGraceMins = 30L                   // 30 mins
    private val lateAfter = shiftStart.plusMinutes(lateGraceMins) // 10:30
    private val shiftEnd = LocalTime.of(17, 30)       // 5:30 PM

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
        btnSendReport.setOnClickListener { exportAndShareMonthlyXlsx() }
        btnBattery.setOnClickListener { showBatteryStatus() }
        btnAddIn.setOnClickListener { showManualPunchDialog(PunchType.IN) }
        btnAddOut.setOnClickListener { showManualPunchDialog(PunchType.OUT) }

        // ✅ Fix missed-out for past days (safe, doesn't touch today)
        dbExecutor.execute { autoOutForPreviousDays() }

        reportText.text = "✅ Admin Ready"
        reportText.setTextColor(Color.WHITE)
    }

    private fun normalizeUid(rawUid: String): String =
        rawUid.trim().replace(Regex("[^0-9]"), "")

    // ---------------- REGISTER NEW CARD ----------------

    private fun showRegisterCardDialog() {
        val codeInput = EditText(this).apply { hint = "EMP Code (e.g. EMP-019)" }
        val nameInput = EditText(this).apply { hint = "Display Name (current assignee)" }
        val uidInput = EditText(this).apply {
            hint = "Tap here then scan card"
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
            .setTitle("Register / Assign Card")
            .setMessage("This creates a NEW assignment record (history preserved).")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                val uid = normalizeUid(uidInput.text.toString())

                if (code.isBlank() || name.isBlank() || uid.isBlank()) {
                    toast("❌ Code/Name/UID required")
                    return@setPositiveButton
                }

                registerOrUpdateWorkerAndAssignment(code, name, uid)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * ✅ SAFE behavior:
     * - worker identity = (code, uid)
     * - assignment history stored in worker_assignments
     *
     * Logic:
     * 1) Upsert worker row (by UID conflict OR by CODE update)
     * 2) Close current assignment (end_ts = now)
     * 3) Insert new assignment with start_ts = now
     */
    private fun registerOrUpdateWorkerAndAssignment(code: String, displayName: String, rfidUid: String) {
        dbExecutor.execute {
            val db = dbHelper.writableDatabase
            val now = System.currentTimeMillis()

            try {
                db.beginTransaction()

                // 1) Upsert worker (try by UID)
                var workerId: Long? = null
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
                    // Unique(code) hit → update by code
                    Log.w("ADMIN", "Insert by UID failed, updating by code", e)
                    db.execSQL(
                        """
                        UPDATE workers
                        SET display_name = ?, rfid_uid = ?
                        WHERE code = ?
                        """.trimIndent(),
                        arrayOf(displayName, rfidUid, code)
                    )
                }

                // Find workerId (by UID first, else by code)
                run {
                    val c = db.rawQuery(
                        "SELECT id FROM workers WHERE rfid_uid=?",
                        arrayOf(rfidUid)
                    )
                    if (c.moveToFirst()) workerId = c.getLong(0)
                    c.close()
                }
                if (workerId == null) {
                    val c = db.rawQuery("SELECT id FROM workers WHERE code=?", arrayOf(code))
                    if (c.moveToFirst()) workerId = c.getLong(0)
                    c.close()
                }
                if (workerId == null) throw IllegalStateException("WorkerId not found after upsert")

                // 2) Close active assignment (if exists)
                db.execSQL(
                    """
                    UPDATE worker_assignments
                    SET end_ts = ?
                    WHERE worker_id = ? AND end_ts IS NULL
                    """.trimIndent(),
                    arrayOf(now, workerId!!)
                )

                // 3) Create new assignment row
                db.execSQL(
                    """
                    INSERT INTO worker_assignments (worker_id, display_name, start_ts, end_ts)
                    VALUES (?, ?, ?, NULL)
                    """.trimIndent(),
                    arrayOf(workerId!!, displayName, now)
                )

                db.setTransactionSuccessful()

                runOnUiThread {
                    reportText.text = "✅ Assigned: $displayName ($code)\nUID=$rfidUid"
                    reportText.setTextColor(Color.GREEN)
                }

            } catch (e: Exception) {
                Log.e("ADMIN", "Register/Assign failed", e)
                runOnUiThread {
                    reportText.text = "❌ Failed: ${e.message}"
                    reportText.setTextColor(Color.RED)
                }
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }

    // ---------------- MANUAL IN/OUT ----------------

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

    // ---------------- MISSED OUT FIX (AUTO OUT 23:59 for PREVIOUS DAYS) ----------------

    /**
     * Inserts an OUT punch at 23:59:00 for any past date where a worker has IN but no OUT.
     * This protects salary calculations and prevents “missing OUT” permanently.
     *
     * SAFE: only affects days strictly before today.
     */
    private fun autoOutForPreviousDays() {
        val db = dbHelper.writableDatabase
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        try {
            // Find worker-date combos with IN but no OUT for past dates
            val cursor = db.rawQuery(
                """
                SELECT
                    p.worker_id,
                    DATE(p.timestamp / 1000, 'unixepoch', 'localtime') AS work_date,
                    MIN(CASE WHEN p.type='IN' THEN p.timestamp END) AS in_ts,
                    MAX(CASE WHEN p.type='OUT' THEN p.timestamp END) AS out_ts
                FROM punches p
                GROUP BY p.worker_id, work_date
                HAVING in_ts IS NOT NULL AND out_ts IS NULL
                """.trimIndent(),
                null
            )

            db.beginTransaction()
            try {
                while (cursor.moveToNext()) {
                    val workerId = cursor.getLong(0)
                    val workDateStr = cursor.getString(1) // yyyy-MM-dd
                    val outTs = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    if (outTs > 0L) continue

                    val workDate = LocalDate.parse(workDateStr)
                    if (!workDate.isBefore(today)) continue // only past dates

                    val outTime = LocalDateTime.of(workDate, LocalTime.of(23, 59, 0))
                    val outMillis = outTime.atZone(zone).toInstant().toEpochMilli()

                    db.execSQL(
                        "INSERT INTO punches (worker_id, type, timestamp) VALUES (?, 'OUT', ?)",
                        arrayOf(workerId, outMillis)
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                cursor.close()
                if (db.inTransaction()) db.endTransaction()
            }

            Log.d("AUTO_OUT", "Auto-out pass completed")

        } catch (e: Exception) {
            Log.e("AUTO_OUT", "autoOutForPreviousDays failed", e)
        }
    }

    // ---------------- XLSX EXPORT (PIVOT + TOTALS) ----------------

    private fun exportAndShareMonthlyXlsx() {
        dbExecutor.execute {
            try {
                // Ensure auto-out before exporting
                autoOutForPreviousDays()

                val xlsx = buildMonthlyWorkbook(LocalDate.now(ZoneId.systemDefault()))
                runOnUiThread { shareXlsx(xlsx) }

            } catch (e: Exception) {
                Log.e("XLSX", "Export failed", e)
                runOnUiThread {
                    reportText.text = "❌ Export failed: ${e.message}"
                    reportText.setTextColor(Color.RED)
                }
            }
        }
    }

    /**
     * Builds workbook with:
     * Sheet 1: Monthly Pivot (EMP rows, day columns)
     * Sheet 2: Monthly Totals (EMP, total duration, late count, early count, missed-out count)
     *
     * Each day cell: "IN-OUT (hh:mm) [LATE] [EARLY]"
     */
    private fun buildMonthlyWorkbook(anyDayInMonth: LocalDate): File {
        val zone = ZoneId.systemDefault()
        val monthStart = anyDayInMonth.withDayOfMonth(1)
        val monthEnd = anyDayInMonth.with(TemporalAdjusters.lastDayOfMonth())
        val daysInMonth = monthEnd.dayOfMonth

        val db = dbHelper.readableDatabase

        // Get all workers
        val workers = mutableListOf<Pair<Long, String>>() // (workerId, code)
        run {
            val c = db.rawQuery("SELECT id, code FROM workers ORDER BY code", null)
            while (c.moveToNext()) {
                workers.add(c.getLong(0) to c.getString(1))
            }
            c.close()
        }

        // Preload daily aggregates per worker per day
        // map[workerId][day] = DayAgg(...)
        data class DayAgg(val inTs: Long?, val outTs: Long?)
        val agg = HashMap<Long, Array<DayAgg?>>(workers.size)

        val startMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = monthEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val cur = db.rawQuery(
            """
            SELECT
                p.worker_id,
                DATE(p.timestamp / 1000, 'unixepoch', 'localtime') AS work_date,
                MIN(CASE WHEN p.type='IN' THEN p.timestamp END)  AS in_time,
                MAX(CASE WHEN p.type='OUT' THEN p.timestamp END) AS out_time
            FROM punches p
            WHERE p.timestamp >= ? AND p.timestamp < ?
            GROUP BY p.worker_id, work_date
            """.trimIndent(),
            arrayOf(startMillis.toString(), endMillis.toString())
        )

        while (cur.moveToNext()) {
            val workerId = cur.getLong(0)
            val dateStr = cur.getString(1) // yyyy-MM-dd
            val inTs = if (cur.isNull(2)) null else cur.getLong(2)
            val outTs = if (cur.isNull(3)) null else cur.getLong(3)
            val date = LocalDate.parse(dateStr)
            val day = date.dayOfMonth

            val arr = agg.getOrPut(workerId) { arrayOfNulls(daysInMonth + 1) }
            arr[day] = DayAgg(inTs, outTs)
        }
        cur.close()

        // Helper to get assignment name for a worker at a specific timestamp
        fun nameAt(workerId: Long, tsMillis: Long): String {
            val c = db.rawQuery(
                """
                SELECT display_name
                FROM worker_assignments
                WHERE worker_id = ?
                  AND start_ts <= ?
                  AND (end_ts IS NULL OR end_ts > ?)
                ORDER BY start_ts DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(workerId.toString(), tsMillis.toString(), tsMillis.toString())
            )
            val name =
                if (c.moveToFirst()) c.getString(0)
                else {
                    // fallback: workers.display_name
                    val c2 = db.rawQuery("SELECT display_name FROM workers WHERE id=?", arrayOf(workerId.toString()))
                    val n = if (c2.moveToFirst()) c2.getString(0) else "Unknown"
                    c2.close()
                    n
                }
            c.close()
            return name
        }

        val wb: Workbook = XSSFWorkbook()

        val headerStyle = wb.createCellStyle().apply {
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }
        val cellStyle = wb.createCellStyle().apply {
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
        }

        // ---------------- Sheet 1: Monthly Pivot ----------------
        val pivot = wb.createSheet("Monthly Pivot")

        // Header row
        run {
            val r = pivot.createRow(0)
            r.createCell(0).setCellValue("EMP Code")
            r.createCell(1).setCellValue("Name (for month)")
            for (d in 1..daysInMonth) {
                r.createCell(1 + d).setCellValue(d.toString())
            }
            val base = 1 + daysInMonth
            r.createCell(base + 1).setCellValue("Total (hh:mm)")
            r.createCell(base + 2).setCellValue("Late Days")
            r.createCell(base + 3).setCellValue("Early Days")
            r.createCell(base + 4).setCellValue("Missed OUT Days")

            for (i in 0..(base + 4)) r.getCell(i).cellStyle = headerStyle
        }

        // Totals sheet collector
        data class TotRow(
            val code: String,
            val monthName: String,
            val totalMinutes: Long,
            val lateDays: Int,
            val earlyDays: Int,
            val missedOutDays: Int
        )
        val totals = mutableListOf<TotRow>()

        val displayFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")

        fun fmtMinutes(mins: Long): String {
            val h = mins / 60
            val m = mins % 60
            return "%02dh %02dm".format(h, m)
        }

        // Rows per worker
        var rowIdx = 1
        for ((workerId, code) in workers) {
            val row = pivot.createRow(rowIdx++)
            row.createCell(0).setCellValue(code)

            // pick name "for month" as name at first day start
            val monthName = nameAt(workerId, monthStart.atStartOfDay(zone).toInstant().toEpochMilli())
            row.createCell(1).setCellValue(monthName)

            var totalMinutes = 0L
            var lateCount = 0
            var earlyCount = 0
            var missedOutCount = 0

            val arr = agg[workerId] ?: arrayOfNulls(daysInMonth + 1)

            for (d in 1..daysInMonth) {
                val dayDate = monthStart.withDayOfMonth(d)
                val dayAgg = arr[d]

                val cell = row.createCell(1 + d)
                cell.cellStyle = cellStyle

                if (dayAgg?.inTs == null) {
                    cell.setCellValue("")
                    continue
                }

                val inTs = dayAgg.inTs!!
                var outTs = dayAgg.outTs

                // If OUT missing for past days in this month, assume 23:59 (but also count missed-out)
                if (outTs == null) {
                    val isPastDay = dayDate.isBefore(LocalDate.now(zone))
                    if (isPastDay) {
                        val outTime = LocalDateTime.of(dayDate, LocalTime.of(23, 59, 0))
                        outTs = outTime.atZone(zone).toInstant().toEpochMilli()
                        missedOutCount += 1
                    }
                }

                val inLdt = Instant.ofEpochMilli(inTs).atZone(zone).toLocalDateTime()
                val outLdt = outTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }

                // Late/Early flags (only if we have both times realistically)
                val isLate = inLdt.toLocalTime().isAfter(lateAfter)
                val isEarly = outLdt?.toLocalTime()?.isBefore(shiftEnd) == true

                if (isLate) lateCount++
                if (isEarly) earlyCount++

                val durationMinutes =
                    if (outTs != null && outTs > inTs) ((outTs - inTs) / 60000L) else 0L
                totalMinutes += durationMinutes

                val inStr = inLdt.format(displayFmt)
                val outStr = outLdt?.format(displayFmt) ?: "--"
                val durStr = if (durationMinutes > 0) fmtMinutes(durationMinutes) else ""

                val flags = buildString {
                    if (isLate) append(" LATE")
                    if (isEarly) append(" EARLY")
                    if (dayAgg.outTs == null && outTs != null) append(" AUTO-OUT")
                }

                cell.setCellValue("$inStr - $outStr  ($durStr)$flags")
            }

            val base = 1 + daysInMonth
            row.createCell(base + 1).setCellValue(fmtMinutes(totalMinutes))
            row.createCell(base + 2).setCellValue(lateCount.toDouble())
            row.createCell(base + 3).setCellValue(earlyCount.toDouble())
            row.createCell(base + 4).setCellValue(missedOutCount.toDouble())

            totals.add(TotRow(code, monthName, totalMinutes, lateCount, earlyCount, missedOutCount))
        }

        // Autosize a few important columns (not all days)
        pivot.setColumnWidth(0, 14 * 256)
        pivot.setColumnWidth(1, 22 * 256)
        pivot.setColumnWidth(2, 22 * 256)

        // ---------------- Sheet 2: Monthly Totals ----------------
        val totSheet = wb.createSheet("Monthly Totals")
        run {
            val r = totSheet.createRow(0)
            r.createCell(0).setCellValue("EMP Code")
            r.createCell(1).setCellValue("Name (for month)")
            r.createCell(2).setCellValue("Total (hh:mm)")
            r.createCell(3).setCellValue("Late Days")
            r.createCell(4).setCellValue("Early Days")
            r.createCell(5).setCellValue("Missed OUT Days")

            for (i in 0..5) r.getCell(i).cellStyle = headerStyle
        }

        var tr = 1
        for (t in totals) {
            val r = totSheet.createRow(tr++)
            r.createCell(0).setCellValue(t.code)
            r.createCell(1).setCellValue(t.monthName)
            r.createCell(2).setCellValue(fmtMinutes(t.totalMinutes))
            r.createCell(3).setCellValue(t.lateDays.toDouble())
            r.createCell(4).setCellValue(t.earlyDays.toDouble())
            r.createCell(5).setCellValue(t.missedOutDays.toDouble())
        }

        totSheet.setColumnWidth(0, 14 * 256)
        totSheet.setColumnWidth(1, 22 * 256)
        totSheet.setColumnWidth(2, 14 * 256)
        totSheet.setColumnWidth(3, 10 * 256)
        totSheet.setColumnWidth(4, 10 * 256)
        totSheet.setColumnWidth(5, 14 * 256)

        // Write to file
        val dir = getExternalFilesDir(null) ?: throw IllegalStateException("external files dir is null")
        val fileName = "attendance_${monthStart.year}_${monthStart.monthValue}.xlsx"
        val file = File(dir, fileName)

        FileOutputStream(file).use { out ->
            wb.write(out)
        }
        wb.close()

        Log.d("XLSX", "Built XLSX at: ${file.absolutePath}")
        return file
    }

    private fun shareXlsx(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

            putExtra(
                Intent.EXTRA_EMAIL,
                arrayOf(
                    "accounts@eecfilters.com",
                    "eecfilters3@gmail.com",
                    "siddharthpersonal1506@gmail.com"
                )
            )

            putExtra(Intent.EXTRA_SUBJECT, "Factory Attendance Report (XLSX)")
            putExtra(Intent.EXTRA_TEXT, "Attendance workbook attached (Monthly Pivot + Monthly Totals).")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(sendIntent, "Send report via"))
    }

    // ---------------- BATTERY ----------------

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