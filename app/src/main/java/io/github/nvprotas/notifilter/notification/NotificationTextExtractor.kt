package io.github.nvprotas.notifilter.notification

import android.app.Notification
import android.os.Bundle
import io.github.nvprotas.notifilter.domain.NotificationContent

object NotificationTextExtractor {
    fun extract(packageName: String, notification: Notification): NotificationContent {
        return runCatching { extractSafely(packageName, notification) }
            .getOrElse {
                NotificationContent(
                    packageName = packageName,
                    title = "",
                    body = "",
                )
            }
    }

    fun journalSnapshot(notification: Notification): JournalSnapshot = runCatching {
        val extras = notification.extras ?: Bundle.EMPTY
        val title = (
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            )
            ?.toString()
            ?.trim()
            .orEmpty()
            .take(MAX_JOURNAL_TITLE_LENGTH)

        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.take(MAX_TEXT_LINES)
                    ?.joinToString(separator = "\n")
            )
            ?.toString()
            ?.trim()
            .orEmpty()
            .take(MAX_JOURNAL_BODY_LENGTH)

        JournalSnapshot(title = title, body = body)
    }.getOrDefault(JournalSnapshot.EMPTY)

    private fun extractSafely(
        packageName: String,
        notification: Notification,
    ): NotificationContent {
        val extras = notification.extras ?: Bundle.EMPTY

        val titles = buildList {
            addText(extras.getCharSequence(Notification.EXTRA_TITLE))
            addText(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
            addText(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        }.distinct()

        val bodies = buildList {
            addText(extras.getCharSequence(Notification.EXTRA_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            addText(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.take(MAX_TEXT_LINES)
                ?.forEach { line -> addText(line) }
            notification.tickerText?.let { ticker -> addText(ticker) }
        }.distinct().take(MAX_BODY_PARTS)

        return NotificationContent(
            packageName = packageName,
            title = titles.joinToString(separator = "\n").take(MAX_EXTRACTED_TITLE_LENGTH),
            body = bodies.joinToString(separator = "\n").take(MAX_EXTRACTED_BODY_LENGTH),
        )
    }

    private fun MutableList<String>.addText(value: CharSequence?) {
        if (size >= MAX_BODY_PARTS) return
        value?.toString()
            ?.trim()
            ?.take(MAX_PART_LENGTH)
            ?.takeIf(String::isNotEmpty)
            ?.let(::add)
    }

    private const val MAX_TEXT_LINES = 20
    private const val MAX_BODY_PARTS = 64
    private const val MAX_PART_LENGTH = 2_048
    private const val MAX_EXTRACTED_TITLE_LENGTH = 2_048
    private const val MAX_EXTRACTED_BODY_LENGTH = 8_192
    private const val MAX_JOURNAL_TITLE_LENGTH = 1_000
    private const val MAX_JOURNAL_BODY_LENGTH = 8_000
}

data class JournalSnapshot(
    val title: String,
    val body: String,
) {
    companion object {
        val EMPTY = JournalSnapshot(title = "", body = "")
    }
}
