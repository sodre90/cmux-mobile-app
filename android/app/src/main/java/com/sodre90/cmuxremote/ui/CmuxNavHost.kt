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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sodre90.cmuxremote.data.AppContainer
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
            SessionsPlaceholder(onSettings = { navController.navigate(Routes.SETTINGS) })
        }
    }
}

// Replaced by the real sessions screen in Task 7.
@Composable
private fun SessionsPlaceholder(onSettings: () -> Unit) {
    Scaffold { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connected. Sessions coming next.")
            Button(onClick = onSettings) { Text("Settings") }
        }
    }
}
