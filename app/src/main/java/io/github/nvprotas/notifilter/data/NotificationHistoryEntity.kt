package io.github.nvprotas.notifilter.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_history",
    indices = [
        Index("postedAt"),
        Index("packageName"),
        Index("sourceIdentity"),
        Index("active"),
        Index(value = ["eventId"], unique = true),
    ],
)
data class NotificationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventId: String,
    val sourceIdentity: String?,
    val packageName: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val updatedAt: Long,
    val matchedRuleId: Long?,
    val matchedRulePattern: String?,
    val outcome: String,
    val active: Boolean,
)

enum class HistoryOutcome {
    RECEIVED,
    DISMISS_REQUESTED,
    DISMISS_CONFIRMED,
}
