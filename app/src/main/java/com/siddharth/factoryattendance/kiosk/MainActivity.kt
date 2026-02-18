package com.siddharth.factoryattendance.kiosk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

data class Worker(
    val id: Int,
    val code: String,
    val displayName: String,
    val rfidUid: String
)

data class Punch(
    val workerId: Int,
    val type: PunchType,
    val timestamp: Long
)

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: AttendanceDbHelper

    private lateinit var adminTouchZone: View
    private val adminHandler = Handler(Looper.getMainLooper())
    private var adminRunnable: Runnable? = null

    private val uidBuffer = StringBuilder()
    private lateinit var statusText: TextView

    private val COOLDOWN_MS = 5_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dbHelper = AttendanceDbHelper(this)
        setContentView(R.layout.activity_main)

        adminTouchZone = findViewById(R.id.adminTouchZone)
        statusText = findViewById(R.id.statusText)

        seedWorkersUpsert()
        debugDumpWorkers()

        setupAdminHold()

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText.text = "Tap RFID Card"
        statusText.setTextColor(Color.GREEN)
    }

    private fun normalizeUid(rawUid: String): String {
        return rawUid.trim().replace(Regex("[^0-9]"), "")
    }

    private fun setupAdminHold() {
        adminTouchZone.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    adminRunnable = Runnable { showAdminPinDialog() }
                    adminHandler.postDelayed(adminRunnable!!, 5000)
                    true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    adminRunnable?.let { adminHandler.removeCallbacks(it) }
                    true
                }

                else -> false
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {

            if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                val raw = uidBuffer.toString()
                uidBuffer.clear()
                if (raw.isNotBlank()) handleScannedCard(raw)
                return true
            }

            val ch = event.unicodeChar.toChar()
            if (ch.isDigit()) {
                uidBuffer.append(ch)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleScannedCard(rawUid: String) {
        val uid = normalizeUid(rawUid)

        Log.d("RFID", "Raw='$rawUid' | Clean='$uid' | len=${uid.length}")

        if (uid.isBlank()) {
            statusText.text = "❌ Invalid Card"
            statusText.setTextColor(Color.RED)
            return
        }

        val worker = getWorkerByUid(uid)
        if (worker == null) {
            statusText.text = "❌ Unknown Card"
            statusText.setTextColor(Color.RED)
            Log.d("RFID", "No worker found for uid='$uid'")
            // Helpful: show what’s actually in DB
            debugDumpWorkers()
            return
        }

        val now = System.currentTimeMillis()
        val lastPunch = getLastPunchForWorker(worker.id)

        if (lastPunch != null && now - lastPunch.timestamp < COOLDOWN_MS) {
            statusText.text = "⚠️ Please wait"
            statusText.setTextColor(Color.YELLOW)
            return
        }

        val nextType =
            if (lastPunch == null || lastPunch.type == PunchType.OUT) PunchType.IN else PunchType.OUT

        val punchId = savePunchAndReturnId(Punch(worker.id, nextType, now))

        statusText.text = "✅ ${worker.displayName} (${worker.code}) - ${nextType.name}"
        statusText.setTextColor(Color.CYAN)

        launchCameraForPunch(punchId)
    }

    private fun seedWorkersUpsert() {
        val db = dbHelper.writableDatabase

        // Keep your fixed seed list. New registrations won’t be overwritten unless UID/code matches.
        val workers = listOf(
            Triple("EMP-001", "Neha Bhatt", "0040115284"),
            Triple("EMP-002", "Navika", "0040114646"),
            Triple("EMP-003", "Mansi", "0040115273"),
            Triple("EMP-004", "Rina", "0040114657"),
            Triple("EMP-005", "Saakhi", "0040115262"),
            Triple("EMP-006", "Rama", "0040170647"),
            Triple("EMP-007", "Munesh", "0040170746"),
            Triple("EMP-008", "Anjali", "0040156193"),
            Triple("EMP-009", "Neha", "0040170999"),
            Triple("EMP-010", "Rihfat", "0040053772"),
            Triple("EMP-011", "Munni", "0040053761"),
            Triple("EMP-012", "Saroj", "0040052584"),
            Triple("EMP-013", "Rani", "0040105173"),
            Triple("EMP-014", "Aarti", "0040095795"),
            Triple("EMP-015", "Sudha", "0040095806"),
            Triple("EMP-016", "Dholi", "0040105162"),
            Triple("EMP-017", "Reena", "0040105151"),
            Triple("EMP-018", "Saakhi", "0040095784"),

            // Permanents recovered
            Triple("EMP-019", "Permanent 1", "0040115295"),
            Triple("EMP-020", "Permanent 2", "0040136248"),
            Triple("EMP-021", "Permanent 3", "0040136149"),

            // Temp recovered
            Triple("EMP-023", "Temp-023", "0040114624"),
            Triple("EMP-024", "Temp-024", "0040136237"),
            Triple("EMP-025", "Temp-025", "0040136160"),
            Triple("EMP-026", "Temp-026", "0040115306"),

            // Test Card
            Triple("EMP-027", "Temp-027", "0030653028"),
        )

        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                """
                INSERT INTO workers (code, display_name, rfid_uid)
                VALUES (?, ?, ?)
                ON CONFLICT(rfid_uid) DO UPDATE SET
                    code = excluded.code,
                    display_name = excluded.display_name
                """.trimIndent()
            )

            for ((code, name, rawUid) in workers) {
                val uid = normalizeUid(rawUid)
                if (uid.isBlank()) continue
                stmt.clearBindings()
                stmt.bindString(1, code)
                stmt.bindString(2, name)
                stmt.bindString(3, uid)
                stmt.executeInsert()
            }

            db.setTransactionSuccessful()
            Log.d("DB", "Seeded/Upserted ${workers.size} workers")
        } catch (e: Exception) {
            Log.e("DB", "seedWorkersUpsert failed", e)
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    }

    private fun getWorkerByUid(uid: String): Worker? {
        val db = dbHelper.readableDatabase

        // Find worker row
        val c = db.rawQuery(
            "SELECT id, code, display_name, rfid_uid FROM workers WHERE rfid_uid = ?",
            arrayOf(uid)
        )

        if (!c.moveToFirst()) {
            c.close()
            return null
        }

        val id = c.getInt(0)
        val code = c.getString(1)
        val fallbackName = c.getString(2)
        val rfidUid = c.getString(3)
        c.close()

        // Prefer ACTIVE assignment name (end_ts IS NULL)
        val a = db.rawQuery(
            """
        SELECT display_name
        FROM worker_assignments
        WHERE worker_id = ? AND end_ts IS NULL
        ORDER BY start_ts DESC
        LIMIT 1
        """.trimIndent(),
            arrayOf(id.toString())
        )

        val activeName = if (a.moveToFirst()) a.getString(0) else fallbackName
        a.close()

        return Worker(id = id, code = code, displayName = activeName, rfidUid = rfidUid)
    }

    private fun getLastPunchForWorker(workerId: Int): Punch? {
        val db = dbHelper.readableDatabase
        val c = db.rawQuery(
            "SELECT worker_id, type, timestamp FROM punches WHERE worker_id=? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(workerId.toString())
        )

        val punch =
            if (c.moveToFirst())
                Punch(
                    workerId = c.getInt(0),
                    type = PunchType.valueOf(c.getString(1)),
                    timestamp = c.getLong(2)
                )
            else null

        c.close()
        return punch
    }

    private fun savePunchAndReturnId(p: Punch): Long {
        val db = dbHelper.writableDatabase
        val stmt = db.compileStatement(
            "INSERT INTO punches (worker_id, type, timestamp) VALUES (?, ?, ?)"
        )
        stmt.bindLong(1, p.workerId.toLong())
        stmt.bindString(2, p.type.name)
        stmt.bindLong(3, p.timestamp)
        val id = stmt.executeInsert()
        Log.d("DB", "Saved punch id=$id worker=${p.workerId} type=${p.type} ts=${p.timestamp}")
        return id
    }

    private fun launchCameraForPunch(punchId: Long) {
        AppState.isCameraActive = true
        val intent = Intent(this, CameraCaptureActivity::class.java)
        intent.putExtra(CameraCaptureActivity.EXTRA_PUNCH_ID, punchId)
        startActivity(intent)
    }

    private fun showAdminPinDialog() {
        val input = EditText(this)
        input.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (input.text.toString() == "1234") launchAdmin()
                else {
                    statusText.text = "❌ Invalid PIN"
                    statusText.setTextColor(Color.RED)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchAdmin() {
        val intent = Intent(this, AdminActivity::class.java)
        intent.putExtra("ADMIN_AUTH", true)
        startActivity(intent)
    }

    private fun debugDumpWorkers() {
        val db = dbHelper.readableDatabase
        val c = db.rawQuery("SELECT id, code, display_name, rfid_uid FROM workers ORDER BY id", null)
        Log.d("DB", "---- WORKERS (${c.count}) ----")
        while (c.moveToNext()) {
            Log.d(
                "DB",
                "id=${c.getInt(0)} code=${c.getString(1)} name=${c.getString(2)} uid='${c.getString(3)}'"
            )
        }
        c.close()
    }

    override fun onPause() {
        super.onPause()
        if (!AppState.isAdminModeActive && !AppState.isCameraActive) {
            moveTaskToBack(false)
        }
    }
}