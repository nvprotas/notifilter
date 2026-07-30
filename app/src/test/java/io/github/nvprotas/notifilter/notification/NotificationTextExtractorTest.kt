package io.github.nvprotas.notifilter.notification

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationTextExtractorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `extracts standard and expanded text`() {
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Заголовок")
            .setContentText("Короткий текст")
            .setStyle(Notification.BigTextStyle().bigText("Развёрнутый текст"))
            .build()

        val result = NotificationTextExtractor.extract("com.example", notification)

        assertEquals("com.example", result.packageName)
        assertEquals("Заголовок", result.title)
        assertTrue(result.body.contains("Короткий текст"))
        assertTrue(result.body.contains("Развёрнутый текст"))
    }

    @Test
    fun `journal stores only visible title and primary body`() {
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Чат")
            .setContentText("Новое сообщение")
            .build()
        notification.extras.putCharSequence(
            Notification.EXTRA_SUMMARY_TEXT,
            "Старая история",
        )

        val snapshot = NotificationTextExtractor.journalSnapshot(notification)

        assertEquals("Чат", snapshot.title)
        assertTrue(snapshot.body.contains("Новое сообщение"))
        assertFalse(snapshot.body.contains("Старая история"))
    }
}
