package io.github.nvprotas.notifilter.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nvprotas.notifilter.ui.theme.NotifilterTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RulesViewModel by viewModels()
    private var notificationAccessGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotifilterTheme {
                NotifilterScreen(
                    viewModel = viewModel,
                    notificationAccessGranted = notificationAccessGranted,
                    onOpenAccessSettings = ::openNotificationAccessSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessGranted = NotificationAccess.isGranted(this)
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(NotificationAccess.settingsIntent(this))
        } catch (_: ActivityNotFoundException) {
            startActivity(NotificationAccess.fallbackSettingsIntent())
        }
    }
}

