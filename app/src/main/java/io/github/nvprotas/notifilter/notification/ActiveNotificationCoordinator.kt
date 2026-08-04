package io.github.nvprotas.notifilter.notification

import io.github.nvprotas.notifilter.domain.ActiveNotificationSample
import io.github.nvprotas.notifilter.domain.ActiveNotificationsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps only the notification samples that are currently active in this process. */
object ActiveNotificationCoordinator {
    private val mutableState = MutableStateFlow<ActiveNotificationsState>(
        ActiveNotificationsState.Unavailable,
    )

    val state: StateFlow<ActiveNotificationsState> = mutableState.asStateFlow()

    fun publishAvailable(notifications: List<ActiveNotificationSample>) {
        mutableState.value = ActiveNotificationsState.Available(notifications.toList())
    }

    fun publishUnavailable() {
        mutableState.value = ActiveNotificationsState.Unavailable
    }
}
