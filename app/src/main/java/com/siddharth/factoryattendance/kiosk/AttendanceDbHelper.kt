package com.siddharth.factoryattendance.kiosk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class AttendanceDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAllTables(db)
        migrateDisplayNameToAssignmentsIfNeeded(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d("DB", "onUpgrade old=$oldVersion new=$newVersion")
        createAllTables(db)
        migrateDisplayNameToAssignmentsIfNeeded(db)
    }

    private fun createAllTables(db: SQLiteDatabase) {

        // --------------------
        // WORKERS (KEEP AS-IS to avoid breaking running installs)
        // --------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                rfid_uid TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )

        // --------------------
        // WORKER ASSIGNMENTS (HISTORICAL NAME TRACKING)
        // start_ts inclusive, end_ts exclusive (NULL = active)
        // --------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS worker_assignments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER,
                FOREIGN KEY(worker_id) REFERENCES workers(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // Helpful indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assign_worker_active ON worker_assignments(worker_id, end_ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assign_worker_time ON worker_assignments(worker_id, start_ts, end_ts)")

        // --------------------
        // PUNCHES (WITH PHOTO)
        // --------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS punches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                photo_path TEXT,
                FOREIGN KEY(worker_id) REFERENCES workers(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // Backward compatibility: ensure photo_path exists
        try {
            db.execSQL("ALTER TABLE punches ADD COLUMN photo_path TEXT")
        } catch (_: Exception) { }

        // --------------------
        // AUDIT LOG
        // --------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                worker_id INTEGER,
                details TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // --------------------
        // INDEXES
        // --------------------
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_punches_worker_time ON punches(worker_id, timestamp)")
    }

    /**
     * One-time migration:
     * For each worker, create an "active" assignment using current workers.display_name
     * if no assignment exists yet.
     */
    private fun migrateDisplayNameToAssignmentsIfNeeded(db: SQLiteDatabase) {
        try {
            val c = db.rawQuery("SELECT COUNT(*) FROM worker_assignments", null)
            val hasAny = c.moveToFirst() && c.getInt(0) > 0
            c.close()
            if (hasAny) return

            val now = System.currentTimeMillis()
            val w = db.rawQuery("SELECT id, display_name FROM workers", null)
            db.beginTransaction()
            try {
                while (w.moveToNext()) {
                    val workerId = w.getInt(0)
                    val name = w.getString(1)

                    db.execSQL(
                        """
                        INSERT INTO worker_assignments (worker_id, display_name, start_ts, end_ts)
                        VALUES (?, ?, ?, NULL)
                        """.trimIndent(),
                        arrayOf(workerId, name, now)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                w.close()
                if (db.inTransaction()) db.endTransaction()
            }

            Log.d("DB", "Migrated workers.display_name -> worker_assignments")

        } catch (e: Exception) {
            Log.e("DB", "migrateDisplayNameToAssignmentsIfNeeded failed", e)
        }
    }

//    fun autoCloseOpenPunches() {
//        val db = writableDatabase
//
//        val todayStart = getStartOfToday()
//        val yesterdayEnd = todayStart - 1
//
//        val cursor = db.rawQuery(
//            """
//        SELECT w.id
//        FROM workers w
//        WHERE EXISTS (
//            SELECT 1 FROM punches p
//            WHERE p.worker_id = w.id
//            AND p.type = 'IN'
//            AND p.timestamp <= ?
//        )
//        AND NOT EXISTS (
//            SELECT 1 FROM punches p2
//            WHERE p2.worker_id = w.id
//            AND p2.type = 'OUT'
//            AND DATE(p2.timestamp / 1000, 'unixepoch', 'localtime') =
//                DATE(? / 1000, 'unixepoch', 'localtime')
//        )
//        """.trimIndent(),
//            arrayOf(yesterdayEnd.toString(), yesterdayEnd.toString())
//        )
//
//        db.beginTransaction()
//        try {
//            while (cursor.moveToNext()) {
//                val workerId = cursor.getInt(0)
//
//                db.execSQL(
//                    "INSERT INTO punches (worker_id, type, timestamp) VALUES (?, 'OUT', ?)",
//                    arrayOf(workerId, yesterdayEnd)
//                )
//
//                db.execSQL(
//                    "INSERT INTO audit_log (event_type, worker_id, details, timestamp) VALUES (?, ?, ?, ?)",
//                    arrayOf("AUTO_OUT", workerId, "Auto closed at 23:59", System.currentTimeMillis())
//                )
//            }
//
//            db.setTransactionSuccessful()
//        } finally {
//            cursor.close()
//            db.endTransaction()
//        }
//    }


    private fun getStartOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        private const val DB_NAME = "attendance.db"
        private const val DB_VERSION = 12
    }
}