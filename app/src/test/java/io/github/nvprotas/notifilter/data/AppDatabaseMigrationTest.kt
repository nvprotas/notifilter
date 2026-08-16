package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `migration preserves audit rows except default digit matches and seeds one rule`() {
        createVersionOneDatabase().close()
        val migrated = openVersionTwoDatabase()
        val database = migrated.writableDatabase

        database.query(
            "SELECT eventId, outcome, matchedRuleId, matchedRulePattern FROM notification_history",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("safe", cursor.getString(0))
            assertEquals(HistoryOutcome.DISMISS_CONFIRMED.name, cursor.getString(1))
            assertEquals(9L, cursor.getLong(2))
            assertNull(cursor.getString(3))
        }
        database.query("SELECT pattern, enabled FROM history_exclusions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(AppDatabase.DEFAULT_OTP_PATTERN, cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        migrated.close()
    }

    private fun createVersionOneDatabase(): SupportSQLiteOpenHelper {
        val helper = helper(
            object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE blocked_notifications (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            packageName TEXT NOT NULL,
                            title TEXT NOT NULL,
                            body TEXT NOT NULL,
                            blockedAt INTEGER NOT NULL,
                            matchedRuleId INTEGER,
                            matchedRulePattern TEXT NOT NULL,
                            notificationFingerprint TEXT NOT NULL,
                            status TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO blocked_notifications
                            (packageName, title, body, blockedAt, matchedRuleId,
                             matchedRulePattern, notificationFingerprint, status)
                        VALUES ('com.safe', 'Обычный текст', 'Без чисел', 100, 9, '', 'safe',
                                'DISMISS_CONFIRMED')
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO blocked_notifications
                            (packageName, title, body, blockedAt, matchedRuleId,
                             matchedRulePattern, notificationFingerprint, status)
                        VALUES ('com.otp', 'Код 123456', 'Не сообщайте его', 200, NULL, '', 'otp',
                                'DISMISS_REQUESTED')
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            },
        )
        helper.writableDatabase
        return helper
    }

    private fun openVersionTwoDatabase(): SupportSQLiteOpenHelper {
        val helper = helper(
            object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    AppDatabase.MIGRATION_1_2.migrate(db)
                }
            },
        )
        helper.writableDatabase
        return helper
    }

    private fun helper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(callback)
                .build(),
        )

    companion object {
        private const val DATABASE_NAME = "history-migration-test.db"
    }
}
