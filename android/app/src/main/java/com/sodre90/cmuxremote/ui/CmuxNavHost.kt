package com.sodre90.cmuxremote.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sodre90.cmuxremote.ui.sessions.notificationTarget
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

    // Hoisted above NavHost (not local to the SETTINGS composable) so both
    // SETTINGS and PAIR can bump it. SETTINGS's own composition survives a
    // push-to-PAIR-and-pop-back round trip unchanged -- it doesn't re-execute
    // from scratch just because PAIR was on top of it -- so without an
    // explicit bump on successful pairing, ConnectionSettingsScreen keeps
    // showing whatever paired/unpaired status it read before the navigation.
    var forgetGeneration by remember { mutableIntStateOf(0) }

    // A notification tap carries which workspace needs attention (cmux never
    // tells us the exact pane). Resolve it once after launch, but only when the
    // bridge is configured — otherwise onboarding must come first: if the
    // surface id is already known, jump straight there; otherwise fetch the
    // live session list and resolve a target pane via notificationTarget — one
    // pane opens directly, several resolve to cmux's own focused pane if there
    // is exactly one, and anything still ambiguous falls back to the
    // (attention-striped) sessions list. That fallback must navigate there
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
                ?.let { notificationTarget(it) }
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
            val relayConfigured = remember(forgetGeneration) { container.settings.bridgeConfig(ConnectionSlot.RELAY) != null }
            val directConfigured = remember(forgetGeneration) { container.settings.bridgeConfig(ConnectionSlot.DIRECT) != null }
            ConnectionSettingsScreen(
                relayConfigured = relayConfigured,
                directConfigured = directConfigured,
                onPair = { slot -> navController.navigate(Routes.pair(slot)) },
                onForget = { slot ->
                    container.forgetSlot(slot)
                    forgetGeneration++
                },
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
                onPaired = {
                    forgetGeneration++ // ConnectionSettingsScreen must re-read to show this slot as paired
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.SESSIONS) {
            val vm: SessionsViewModel = viewModel(
                factory = viewModelFactory { initializer { SessionsViewModel(container, container) } },
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
