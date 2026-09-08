package com.sodre90.cmuxremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helper is only worth having if it really ends the lifetimes it hands out
 * -- a silent no-op would put the leak back with nothing to notice it.
 */
class TestViewModelHostTest {

    private class CountingViewModel : ViewModel() {
        var clearedCount = 0
        override fun onCleared() {
            clearedCount++
        }
    }

    @Test
    fun clearingRunsOnClearedAndCancelsTheViewModelScope() {
        val host = TestViewModelHost()
        val vm = host.hold(CountingViewModel::class.java) { CountingViewModel() }
        assertTrue(vm.viewModelScope.isActive)

        host.clearViewModels()

        assertEquals(1, vm.clearedCount)
        assertFalse(vm.viewModelScope.isActive)
    }

    @Test
    fun twoViewModelsOfTheSameClassStayDistinct() {
        val host = TestViewModelHost()

        val first = host.hold(CountingViewModel::class.java) { CountingViewModel() }
        val second = host.hold(CountingViewModel::class.java) { CountingViewModel() }

        assertNotSame(first, second)

        host.clearViewModels()

        assertEquals(1, first.clearedCount)
        assertEquals(1, second.clearedCount)
    }
}
