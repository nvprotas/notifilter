package io.github.nvprotas.notifilter.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_notifications",
    indices = [
        Index("blockedAt"),
        Index("packageName"),
        Index(value = ["notificationFingerprint"], unique = true),
    ],
)
data class BlockedNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val title: String,
    val body: String,
    val blockedAt: Long,
    val matchedRuleId: Long?,
    val matchedRulePattern: String,
    val notificationFingerprint: String,
    val status: String,
)

enum class JournalStatus {
    DISMISS_REQUESTED,
    DISMISS_CONFIRMED,
}
