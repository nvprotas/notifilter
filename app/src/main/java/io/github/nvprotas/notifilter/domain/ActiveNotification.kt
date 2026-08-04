package io.github.nvprotas.notifilter.domain

data class ActiveNotificationSample(
    val key: String,
    val content: NotificationContent,
    val postedAt: Long,
    val eligibleForFiltering: Boolean,
)

sealed interface ActiveNotificationsState {
    data object Unavailable : ActiveNotificationsState

    data class Available(
        val notifications: List<ActiveNotificationSample>,
    ) : ActiveNotificationsState
}
