package com.siddharth.factoryattendance.kiosk

import android.content.Intent
import android.graphics.Color
import android.os.*
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.util.Log

// ---------------- DATA MODELS ----------------

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

// ---------------- MAIN ACTIVITY ----------------

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

        seedWorkers()        // 🔥 correct seeding
        debugDumpWorkers()

        setupAdminHold()

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText.text = "Tap RFID Card"
        statusText.setTextColor(Color.GREEN)
    }

    // ---------------- ADMIN HOLD ----------------

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

    // ---------------- RFID INPUT ----------------

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

    // ---------------- CORE FLOW ----------------

    private fun handleScannedCard(rawUid: String) {
        val uid = rawUid.trim().replace(Regex("[^0-9]"), "")
        Log.d("RFID", "UID=$uid")

        val worker = getWorkerByUid(uid)
        if (worker == null) {
            statusText.text = "❌ Unknown Card"
            statusText.setTextColor(Color.RED)
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
            if (lastPunch == null || lastPunch.type == PunchType.OUT)
                PunchType.IN else PunchType.OUT

        val punchId = savePunchAndReturnId(
            Punch(worker.id, nextType, now)
        )

        statusText.text =
            "✅ ${worker.displayName} (${worker.code}) - ${nextType.name}"
        statusText.setTextColor(Color.CYAN)

        launchCameraForPunch(punchId)
    }

    // ---------------- SEEDING ----------------

    private fun seedWorkers() {
        val db = dbHelper.writableDatabase
        db.beginTransaction()

        val workers = listOf(
            Triple("EMP-001", "Pooja",   "0040115284"),
            Triple("EMP-002", "Kiran",   "0040114646"),
            Triple("EMP-003", "Pooja 2", "0040115273"),
            Triple("EMP-004", "Munesh",  "0040114657"),
            Triple("EMP-005", "Guddi",   "0040115262"),
            Triple("EMP-006", "Rama",    "0040170647"),
            Triple("EMP-007", "Seela",   "0040170746"),
            Triple("EMP-008", "Anjali",  "0040156193"),
            Triple("EMP-009", "Neha",    "0040170999"),
            Triple("EMP-010", "Rihfat",  "0040053772"),
            Triple("EMP-011", "Munni",   "0040053761"),
            Triple("EMP-012", "Saroj",   "0040052584"),
            Triple("EMP-013", "Rani",    "0040105173"),
            Triple("EMP-014", "Aarti",   "0040095795"),
            Triple("EMP-015", "Sudha",   "0040095806"),
            Triple("EMP-016", "Dholi",   "0040105162"),
            Triple("EMP-017", "Reena",   "0040105151"),
            Triple("EMP-018", "Saakhi",  "0040095784"),
            Triple("EMP-019", "Ramakant",  "0040115295"),
            Triple("EMP-020", "Satrudhan",  "0040136248"),
            Triple("EMP-021", "Rahul",  "0040136149"),
        )

        try {
            var id = 1
            for ((code, name, uid) in workers) {
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO workers
                    (id, code, display_name, rfid_uid)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(id++, code, name, uid)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- DB HELPERS ----------------

    private fun getWorkerByUid(uid: String): Worker? {
        val db = dbHelper.readableDatabase
        val c = db.rawQuery(
            "SELECT id,code,display_name,rfid_uid FROM workers WHERE rfid_uid=?",
            arrayOf(uid)
        )

        val worker =
            if (c.moveToFirst())
                Worker(
                    c.getInt(0),
                    c.getString(1),
                    c.getString(2),
                    c.getString(3)
                )
            else null

        c.close()
        return worker
    }

    private fun getLastPunchForWorker(workerId: Int): Punch? {
        val db = dbHelper.readableDatabase
        val c = db.rawQuery(
            "SELECT worker_id,type,timestamp FROM punches WHERE worker_id=? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(workerId.toString())
        )

        val punch =
            if (c.moveToFirst())
                Punch(c.getInt(0), PunchType.valueOf(c.getString(1)), c.getLong(2))
            else null

        c.close()
        return punch
    }

    private fun savePunchAndReturnId(p: Punch): Long {
        val db = dbHelper.writableDatabase
        val stmt = db.compileStatement(
            "INSERT INTO punches (worker_id,type,timestamp) VALUES (?,?,?)"
        )
        stmt.bindLong(1, p.workerId.toLong())
        stmt.bindString(2, p.type.name)
        stmt.bindLong(3, p.timestamp)
        return stmt.executeInsert()
    }

    // ---------------- CAMERA ----------------

    private fun launchCameraForPunch(punchId: Long) {
        AppState.isCameraActive = true
        val intent = Intent(this, CameraCaptureActivity::class.java)
        intent.putExtra(CameraCaptureActivity.EXTRA_PUNCH_ID, punchId)
        startActivity(intent)
    }

    // ---------------- ADMIN ----------------

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
        val c = db.rawQuery("SELECT id,code,display_name,rfid_uid FROM workers", null)
        while (c.moveToNext()) {
            Log.d("DB",
                "${c.getInt(0)} ${c.getString(1)} ${c.getString(2)} ${c.getString(3)}")
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
