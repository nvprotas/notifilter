package io.github.nvprotas.notifilter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Query("SELECT * FROM notification_history WHERE postedAt >= :cutoff ORDER BY postedAt DESC, id DESC")
    fun observeSince(cutoff: Long): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT * FROM notification_history ORDER BY postedAt DESC, id DESC")
    suspend fun getAll(): List<NotificationHistoryEntity>

    @Query(
        """
        SELECT * FROM notification_history
        WHERE sourceIdentity = :sourceIdentity AND active = 1
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findActive(sourceIdentity: String): NotificationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: NotificationHistoryEntity): Long

    @Query(
        """
        UPDATE notification_history
        SET packageName = :packageName,
            title = :title,
            body = :body,
            postedAt = :postedAt,
            updatedAt = :updatedAt,
            matchedRuleId = CASE
                WHEN :outcome = 'RECEIVED' THEN matchedRuleId
                ELSE :matchedRuleId
            END,
            matchedRulePattern = CASE
                WHEN :outcome = 'RECEIVED' THEN matchedRulePattern
                ELSE :matchedRulePattern
            END,
            outcome = CASE
                WHEN outcome = 'DISMISS_CONFIRMED' THEN outcome
                WHEN outcome = 'DISMISS_REQUESTED' AND :outcome = 'RECEIVED' THEN outcome
                ELSE :outcome
            END,
            active = :active
        WHERE eventId = :eventId
        """,
    )
    suspend fun updateExisting(
        eventId: String,
        packageName: String,
        title: String,
        body: String,
        postedAt: Long,
        updatedAt: Long,
        matchedRuleId: Long?,
        matchedRulePattern: String?,
        outcome: String,
        active: Boolean,
    ): Int

    @Query(
        """
        UPDATE notification_history
        SET outcome = 'DISMISS_CONFIRMED', updatedAt = :updatedAt
        WHERE eventId = :eventId AND outcome != 'DISMISS_CONFIRMED'
        """,
    )
    suspend fun confirmRemoval(eventId: String, updatedAt: Long): Int

    @Query("UPDATE notification_history SET active = 0, updatedAt = :updatedAt WHERE eventId = :eventId")
    suspend fun closeEvent(eventId: String, updatedAt: Long): Int

    @Query("UPDATE notification_history SET active = 0 WHERE active = 1")
    suspend fun closeAllActive(): Int

    @Query("UPDATE notification_history SET active = 0 WHERE active = 1 AND sourceIdentity NOT IN (:activeIdentities)")
    suspend fun closeMissingActive(activeIdentities: List<String>): Int

    @Delete
    suspend fun delete(entry: NotificationHistoryEntity)

    @Query("DELETE FROM notification_history WHERE eventId = :eventId")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("DELETE FROM notification_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM notification_history WHERE postedAt <= :at")
    suspend fun deleteAtOrBefore(at: Long): Int

    @Query("DELETE FROM notification_history WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query(
        """
        DELETE FROM notification_history
        WHERE id NOT IN (
            SELECT id FROM notification_history
            ORDER BY postedAt DESC, id DESC
            LIMIT :maximumEntries
        )
        """,
    )
    suspend fun trimToSize(maximumEntries: Int): Int

    @Transaction
    suspend fun upsertAndPrune(
        entry: NotificationHistoryEntity,
        cutoff: Long,
        maximumEntries: Int,
    ) {
        if (insert(entry) == -1L) {
            updateExisting(
                eventId = entry.eventId,
                packageName = entry.packageName,
                title = entry.title,
                body = entry.body,
                postedAt = entry.postedAt,
                updatedAt = entry.updatedAt,
                matchedRuleId = entry.matchedRuleId,
                matchedRulePattern = entry.matchedRulePattern,
                outcome = entry.outcome,
                active = entry.active,
            )
        }
        deleteOlderThan(cutoff)
        trimToSize(maximumEntries)
    }

    @Transaction
    suspend fun clearHistory(at: Long) {
        deleteAtOrBefore(at)
    }
}
