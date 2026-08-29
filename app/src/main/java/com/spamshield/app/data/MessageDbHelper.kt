package com.spamshield.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLiteOpenHelper instead of Room: this app has exactly one table and a handful of
 * queries, so hand-written SQL avoids pulling in an annotation-processor toolchain (KSP/kapt)
 * whose version has to be kept in lockstep with the Kotlin/AGP versions this project pins.
 */
class MessageDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "spamshield.db"
        private const val DB_VERSION = 1

        const val TABLE = "messages"
        const val COL_ID = "id"
        const val COL_SENDER = "sender"
        const val COL_BODY = "body"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_SPAM = "is_spam"
        const val COL_CONFIDENCE = "confidence"
        const val COL_REVIEWED_LABEL = "reviewed_label" // NULL = not reviewed, 0 = ham, 1 = spam
        const val COL_SYNCED = "synced" // 0/1 - has the backend accepted this row's telemetry yet
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SENDER TEXT NOT NULL,
                $COL_BODY TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_SPAM INTEGER NOT NULL,
                $COL_CONFIDENCE REAL NOT NULL,
                $COL_REVIEWED_LABEL INTEGER,
                $COL_SYNCED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_timestamp ON $TABLE($COL_TIMESTAMP DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(record: MessageRecord): Long {
        val values = ContentValues().apply {
            put(COL_SENDER, record.sender)
            put(COL_BODY, record.body)
            put(COL_TIMESTAMP, record.timestamp)
            put(COL_IS_SPAM, if (record.isSpam) 1 else 0)
            put(COL_CONFIDENCE, record.confidence)
            put(COL_SYNCED, if (record.synced) 1 else 0)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    fun updateReviewedLabel(id: Long, reviewedSpam: Boolean) {
        val values = ContentValues().apply {
            put(COL_REVIEWED_LABEL, if (reviewedSpam) 1 else 0)
            put(COL_SYNCED, 0) // corrections need their own telemetry round-trip
        }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun markSynced(id: Long) {
        val values = ContentValues().apply { put(COL_SYNCED, 1) }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getAll(): List<MessageRecord> {
        val results = mutableListOf<MessageRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE ORDER BY $COL_TIMESTAMP DESC", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.toMessageRecord())
            }
        }
        return results
    }

    /** Spam or corrected rows the backend hasn't acknowledged yet — used by the retry worker. */
    fun getPendingSync(): List<MessageRecord> {
        val results = mutableListOf<MessageRecord>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM $TABLE
            WHERE $COL_SYNCED = 0 AND ($COL_IS_SPAM = 1 OR $COL_REVIEWED_LABEL IS NOT NULL)
            ORDER BY $COL_TIMESTAMP ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.toMessageRecord())
            }
        }
        return results
    }

    private fun android.database.Cursor.toMessageRecord(): MessageRecord {
        val reviewedIdx = getColumnIndexOrThrow(COL_REVIEWED_LABEL)
        return MessageRecord(
            id = getLong(getColumnIndexOrThrow(COL_ID)),
            sender = getString(getColumnIndexOrThrow(COL_SENDER)),
            body = getString(getColumnIndexOrThrow(COL_BODY)),
            timestamp = getLong(getColumnIndexOrThrow(COL_TIMESTAMP)),
            isSpam = getInt(getColumnIndexOrThrow(COL_IS_SPAM)) == 1,
            confidence = getFloat(getColumnIndexOrThrow(COL_CONFIDENCE)),
            reviewedLabel = if (isNull(reviewedIdx)) null else getInt(reviewedIdx) == 1,
            synced = getInt(getColumnIndexOrThrow(COL_SYNCED)) == 1
        )
    }
}
