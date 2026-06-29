package com.sodre90.cmuxremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.ui.sessions.SessionsScreen
import com.sodre90.cmuxremote.ui.sessions.SessionsViewModel
import com.sodre90.cmuxremote.ui.settings.SettingsScreen
import com.sodre90.cmuxremote.ui.settings.SettingsViewModel

@Composable
fun CmuxNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val start = if (container.settings.bridgeConfig() == null) Routes.SETTINGS else Routes.SESSIONS

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(container.settings) } },
            )
            SettingsScreen(
                vm = vm,
                onSaved = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.SESSIONS) {
            val vm: SessionsViewModel = viewModel(
                factory = viewModelFactory { initializer { SessionsViewModel(container) } },
            )
            SessionsScreen(
                vm = vm,
                // The session id is passed through to /terminal/{id} as the cmux
                // surface id (see bridge handleTerminal).
                onOpenTerminal = { navController.navigate(Routes.terminal(it.id)) },
                onOpenInbox = { navController.navigate(Routes.INBOX) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = "${Routes.TERMINAL}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TerminalPlaceholder(id = id, onBack = { navController.popBackStack() })
        }

        composable(Routes.INBOX) {
            InboxPlaceholder(onBack = { navController.popBackStack() })
        }
    }
}

// Replaced by the real terminal screen in Task 8.
@Composable
private fun TerminalPlaceholder(id: String, onBack: () -> Unit) = Placeholder("Terminal: $id", onBack)

// Replaced by the real inbox screen in Task 9.
@Composable
private fun InboxPlaceholder(onBack: () -> Unit) = Placeholder("Inbox", onBack)

@Composable
private fun Placeholder(label: String, onBack: () -> Unit) {
    Scaffold { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label)
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
