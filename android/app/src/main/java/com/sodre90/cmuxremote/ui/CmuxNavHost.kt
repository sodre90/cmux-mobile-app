package com.sodre90.cmuxremote.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.ui.inbox.InboxScreen
import com.sodre90.cmuxremote.ui.inbox.InboxViewModel
import com.sodre90.cmuxremote.ui.sessions.SessionsScreen
import com.sodre90.cmuxremote.ui.sessions.SessionsViewModel
import com.sodre90.cmuxremote.ui.sessions.singlePaneTarget
import com.sodre90.cmuxremote.ui.pairing.ConnectionSettingsScreen
import com.sodre90.cmuxremote.ui.pairing.PairingScreen
import com.sodre90.cmuxremote.ui.pairing.PairingViewModel
import com.sodre90.cmuxremote.ui.terminal.TerminalScreen
import com.sodre90.cmuxremote.ui.terminal.TerminalViewModel

@Composable
fun CmuxNavHost(
    container: AppContainer,
    pendingWorkspaceId: String? = null,
    pendingSurfaceId: String? = null,
) {
    val navController = rememberNavController()
    val configured = container.anyBridgeConfigured()
    val start = if (!configured) Routes.SETTINGS else Routes.SESSIONS

    // A notification tap carries which workspace needs attention (cmux never
    // tells us the exact pane). Resolve it once after launch, but only when the
    // bridge is configured — otherwise onboarding must come first: if the
    // surface id is already known, jump straight there; otherwise fetch the
    // live session list and apply the same singlePaneTarget rule the sessions
    // list itself uses on tap — one pane opens directly, several fall back to
    // the (attention-striped) sessions list. That fallback must navigate there
    // explicitly: the tap can arrive while a different, unrelated terminal is
    // already open, so doing nothing would strand the user on it.
    LaunchedEffect(pendingWorkspaceId, pendingSurfaceId, configured) {
        if (!configured) return@LaunchedEffect
        if (pendingSurfaceId != null) {
            navController.navigate(Routes.terminal(pendingSurfaceId))
            return@LaunchedEffect
        }
        if (pendingWorkspaceId != null) {
            val target = runCatching { container.activeBridge()?.sessions() }
                .getOrNull()
                ?.firstOrNull { it.id == pendingWorkspaceId }
                ?.let { singlePaneTarget(it) }
            if (target != null) {
                navController.navigate(Routes.terminal(target))
            } else {
                navController.navigate(Routes.SESSIONS) {
                    popUpTo(Routes.SESSIONS) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    // Predictive back (enabled in the manifest) makes Navigation Compose
    // cross-fade between destinations by default. A fast repeated tap right
    // at the transition can interrupt that animation mid-flight, leaving
    // AnimatedContent paused between two screens with neither one's content
    // composed -- a blank, stuck pane. Every destination here already swaps
    // instantly with no animation, so disable the transition outright rather
    // than risk that stuck state.
    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.SETTINGS) {
            ConnectionSettingsScreen(
                relayConfigured = container.settings.bridgeConfig(ConnectionSlot.RELAY) != null,
                directConfigured = container.settings.bridgeConfig(ConnectionSlot.DIRECT) != null,
                onPair = { slot -> navController.navigate(Routes.pair(slot)) },
                onDone = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = "${Routes.PAIR}/{slot}",
            arguments = listOf(navArgument("slot") { type = NavType.StringType }),
        ) { entry ->
            val slot = ConnectionSlot.valueOf(entry.arguments?.getString("slot").orEmpty().uppercase())
            val vm: PairingViewModel = viewModel(
                factory = viewModelFactory { initializer { PairingViewModel(container, slot) } },
            )
            PairingScreen(
                vm = vm,
                title = if (slot == ConnectionSlot.RELAY) "Pair via relay" else "Pair via Tailscale (direct)",
                onPaired = { navController.popBackStack() }, // back to ConnectionSettingsScreen, now showing this slot as paired
            )
        }

        composable(Routes.SESSIONS) {
            val vm: SessionsViewModel = viewModel(
                factory = viewModelFactory { initializer { SessionsViewModel(container) } },
            )
            SessionsScreen(
                vm = vm,
                // The pane's surface id is passed through to /terminal/{id} as
                // the cmux terminal-surface id (see bridge handleTerminal).
                // launchSingleTop guards a fast double-tap on the same
                // workspace card (e.g. right as the previous terminal is
                // popping back to this screen) from pushing a duplicate
                // destination -- otherwise a single "back" only pops one
                // copy, leaving an identical-looking screen underneath that
                // looks stuck.
                onOpenTerminal = { surfaceId ->
                    navController.navigate(Routes.terminal(surfaceId)) { launchSingleTop = true }
                },
                onOpenInbox = { navController.navigate(Routes.INBOX) { launchSingleTop = true } },
                onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
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
