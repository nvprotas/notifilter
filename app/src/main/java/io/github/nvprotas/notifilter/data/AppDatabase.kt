package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FilterRuleEntity::class,
        NotificationHistoryEntity::class,
        HistoryExclusionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun filterRuleDao(): FilterRuleDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun historyExclusionDao(): HistoryExclusionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "notifilter.db",
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(SEED_DEFAULT_EXCLUSION_CALLBACK)
                .build()
                .also { instance = it }
        }

        const val DEFAULT_OTP_PATTERN = "(?:^|[^0-9])[0-9]{4,6}(?:[^0-9]|$)"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                createHistoryTable(database)
                createExclusionTable(database)
                database.execSQL(
                    """
                    INSERT INTO notification_history (
                        eventId, sourceIdentity, packageName, title, body, postedAt, updatedAt,
                        matchedRuleId, matchedRulePattern, outcome, active
                    )
                    SELECT
                        notificationFingerprint, NULL, packageName, title, body, blockedAt, blockedAt,
                        matchedRuleId, NULLIF(matchedRulePattern, ''),
                        CASE status
                            WHEN 'DISMISS_CONFIRMED' THEN 'DISMISS_CONFIRMED'
                            ELSE 'DISMISS_REQUESTED'
                        END,
                        0
                    FROM blocked_notifications
                    """.trimIndent(),
                )
                seedDefaultExclusion(database)
                removeDefaultExcludedHistory(database)
                database.execSQL("DROP TABLE blocked_notifications")
            }
        }

        private val SEED_DEFAULT_EXCLUSION_CALLBACK = object : Callback() {
            override fun onCreate(database: SupportSQLiteDatabase) {
                seedDefaultExclusion(database)
            }
        }

        private fun createHistoryTable(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS notification_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    eventId TEXT NOT NULL,
                    sourceIdentity TEXT,
                    packageName TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    postedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    matchedRuleId INTEGER,
                    matchedRulePattern TEXT,
                    outcome TEXT NOT NULL,
                    active INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_history_postedAt ON notification_history(postedAt)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_history_packageName ON notification_history(packageName)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_history_sourceIdentity ON notification_history(sourceIdentity)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_history_active ON notification_history(active)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notification_history_eventId ON notification_history(eventId)")
        }

        private fun createExclusionTable(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_exclusions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    packageName TEXT,
                    pattern TEXT,
                    target TEXT NOT NULL,
                    ignoreCase INTEGER NOT NULL,
                    enabled INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_exclusions_enabled ON history_exclusions(enabled)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_exclusions_packageName ON history_exclusions(packageName)")
        }

        private fun seedDefaultExclusion(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                INSERT INTO history_exclusions (
                    packageName, pattern, target, ignoreCase, enabled, createdAt
                ) VALUES (NULL, ?, 'ALL_TEXT', 1, 1, 0)
                """.trimIndent(),
                arrayOf(DEFAULT_OTP_PATTERN),
            )
        }

        private fun removeDefaultExcludedHistory(database: SupportSQLiteDatabase) {
            val matcher = Regex(DEFAULT_OTP_PATTERN)
            val ids = mutableListOf<Long>()
            database.query("SELECT id, title, body FROM notification_history").use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow("id")
                val titleColumn = cursor.getColumnIndexOrThrow("title")
                val bodyColumn = cursor.getColumnIndexOrThrow("body")
                while (cursor.moveToNext()) {
                    val text = buildString {
                        append(cursor.getString(titleColumn))
                        append('\n')
                        append(cursor.getString(bodyColumn))
                    }
                    if (matcher.containsMatchIn(text)) ids += cursor.getLong(idColumn)
                }
            }
            ids.forEach { id ->
                database.execSQL("DELETE FROM notification_history WHERE id = ?", arrayOf(id))
            }
        }
    }
}
