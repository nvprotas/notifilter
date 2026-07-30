package io.github.nvprotas.notifilter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedNotificationDao {
    @Query("SELECT * FROM blocked_notifications WHERE blockedAt >= :cutoff ORDER BY blockedAt DESC")
    fun observeSince(cutoff: Long): Flow<List<BlockedNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: BlockedNotificationEntity): Long

    @Query(
        """
        UPDATE blocked_notifications
        SET status = :status
        WHERE notificationFingerprint = :fingerprint
        """,
    )
    suspend fun updateStatus(fingerprint: String, status: String): Int

    @Delete
    suspend fun delete(entry: BlockedNotificationEntity)

    @Query("DELETE FROM blocked_notifications WHERE blockedAt <= :at")
    suspend fun deleteAtOrBefore(at: Long)

    @Query("DELETE FROM blocked_notifications WHERE blockedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query(
        """
        DELETE FROM blocked_notifications
        WHERE id NOT IN (
            SELECT id FROM blocked_notifications
            ORDER BY blockedAt DESC
            LIMIT :maximumEntries
        )
        """,
    )
    suspend fun trimToSize(maximumEntries: Int)

    @Transaction
    suspend fun insertAndPrune(
        entry: BlockedNotificationEntity,
        cutoff: Long,
        maximumEntries: Int,
    ) {
        val insertedId = insert(entry)
        if (insertedId == -1L && entry.status == JournalStatus.DISMISS_CONFIRMED.name) {
            updateStatus(entry.notificationFingerprint, JournalStatus.DISMISS_CONFIRMED.name)
        }
        deleteOlderThan(cutoff)
        trimToSize(maximumEntries)
    }

    @Transaction
    suspend fun clearJournal(at: Long) {
        deleteAtOrBefore(at)
    }
}
