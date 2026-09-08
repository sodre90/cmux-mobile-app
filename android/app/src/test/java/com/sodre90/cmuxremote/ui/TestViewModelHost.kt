package com.sodre90.cmuxremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain

/**
 * Gives the ViewModels a test builds a real lifetime: [clearViewModels] ends it,
 * running `onCleared()` and cancelling every `viewModelScope` coroutine.
 *
 * Without it a ViewModel outlives the test that made it. Its refresh loops and
 * reconnect retries keep running for the rest of the suite -- against a
 * [okhttp3.mockwebserver.MockWebServer] the test has already shut down -- and if
 * the class also hands `Dispatchers.Main` back, the work they dispatch onto a
 * Main that no longer exists throws on whichever `runTest` JUnit starts next.
 * The red test then sits in a class with no bug in it (observed: SettledSizesTest
 * reporting UncaughtExceptionsBeforeTest while passing in isolation).
 *
 * `ViewModel.clear()` is package-private, so ending a lifetime means going
 * through the [ViewModelStore] that owns it -- hence [hold] rather than calling
 * the ViewModel's constructor directly.
 *
 * Main deliberately stays installed afterwards. Every class here wants the same
 * `Dispatchers.Default`-backed Main and none uses a TestDispatcher for it, so
 * resetting it buys nothing and reintroduces the race this type exists to close;
 * a future test wanting its own Main just calls `setMain` itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestViewModelHost {

    private val store = ViewModelStore()

    // A store keys by type, so two ViewModels of the same class would collide
    // and the second hold() would hand back the first instance. Tests that
    // compare two differently-configured ViewModels need them distinct.
    private var heldCount = 0

    init {
        Dispatchers.setMain(Dispatchers.Default)
    }

    fun <T : ViewModel> hold(type: Class<T>, build: () -> T): T =
        ViewModelProvider(store, singletonFactory(build))["held-${heldCount++}", type]

    fun clearViewModels() = store.clear()

    private fun <T : ViewModel> singletonFactory(build: () -> T) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = build() as VM
    }
}
