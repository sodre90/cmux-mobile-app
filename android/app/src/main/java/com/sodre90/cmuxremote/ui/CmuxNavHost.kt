package com.sodre90.cmuxremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.ui.inbox.InboxScreen
import com.sodre90.cmuxremote.ui.inbox.InboxViewModel
import com.sodre90.cmuxremote.ui.sessions.SessionsScreen
import com.sodre90.cmuxremote.ui.sessions.SessionsViewModel
import com.sodre90.cmuxremote.ui.settings.SettingsScreen
import com.sodre90.cmuxremote.ui.settings.SettingsViewModel
import com.sodre90.cmuxremote.ui.terminal.TerminalScreen
import com.sodre90.cmuxremote.ui.terminal.TerminalViewModel

@Composable
fun CmuxNavHost(container: AppContainer, initialRoute: String? = null) {
    val navController = rememberNavController()
    val configured = container.settings.bridgeConfig() != null
    val start = if (!configured) Routes.SETTINGS else Routes.SESSIONS

    // A notification deep link (e.g. EXTRA_NAV=inbox) navigates once after launch,
    // but only when the bridge is configured — otherwise onboarding must come first.
    LaunchedEffect(initialRoute, configured) {
        if (initialRoute != null && configured) {
            navController.navigate(initialRoute)
        }
    }

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
            val vm: TerminalViewModel = viewModel(
                factory = viewModelFactory { initializer { TerminalViewModel(container, id) } },
            )
            TerminalScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.INBOX) {
            val vm: InboxViewModel = viewModel(
                factory = viewModelFactory { initializer { InboxViewModel(container) } },
            )
            InboxScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
}
