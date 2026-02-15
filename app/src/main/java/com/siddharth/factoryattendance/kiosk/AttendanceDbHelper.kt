package com.siddharth.factoryattendance.kiosk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class AttendanceDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Prevent orphan records + reduce lock issues
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAllTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d("DB", "onUpgrade old=$oldVersion new=$newVersion")
        createAllTables(db)
    }

    private fun createAllTables(db: SQLiteDatabase) {

        // --------------------
        // WORKERS
        // --------------------
        db.execSQL("""
    CREATE TABLE IF NOT EXISTS workers (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        code TEXT NOT NULL,
        display_name TEXT NOT NULL,
        rfid_uid TEXT NOT NULL UNIQUE
    )
""")

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

        // 🔒 Backward compatibility:
        // If punches table existed before, ensure photo_path exists
        try {
            db.execSQL("ALTER TABLE punches ADD COLUMN photo_path TEXT")
        } catch (_: Exception) {
            // Column already exists — safe to ignore
        }

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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_punches_worker_time ON punches(worker_id, timestamp)"
        )
    }

    companion object {
        private const val DB_NAME = "attendance.db"
        private const val DB_VERSION = 10
    }
}
