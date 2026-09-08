package com.sodre90.cmuxremote.ui.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resize-storm guard (cmux-app-dfl): opening a pane measured the viewport
 * a dozen times in a second as the insets and IME settled, and every distinct
 * size became its own resize RPC -- each one a full cmux surface re-layout and
 * replay frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettledSizesTest {

    private val settle = RESIZE_SETTLE_MS

    @Test
    fun aBurstOfMeasurementsCollapsesToTheOneItSettlesOn() = runTest {
        val measured = flow {
            emit(GridSize(80, 20))
            emit(GridSize(80, 24))
            emit(GridSize(80, 26))
            emit(GridSize(80, 28))
            delay(settle * 2)
        }

        assertEquals(listOf(GridSize(80, 28)), measured.settledSizes(settle).toList())
    }

    @Test
    fun aSizeThatEndsWhereItBeganIsNotSentAtAll() = runTest {
        // The IME opens and closes again: the viewport moves and comes back, so
        // the Mac has nothing to be told.
        val measured = flow {
            emit(GridSize(80, 40))
            delay(settle * 2)
            emit(GridSize(80, 18))
            emit(GridSize(80, 40))
            delay(settle * 2)
        }

        assertEquals(listOf(GridSize(80, 40)), measured.settledSizes(settle).toList())
    }

    @Test
    fun sizesSeparatedByAPauseAreEachSent() = runTest {
        // A rotation after the pane has been sitting still is a real resize.
        val measured = flow {
            emit(GridSize(80, 40))
            delay(settle * 2)
            emit(GridSize(140, 18))
            delay(settle * 2)
        }

        assertEquals(
            listOf(GridSize(80, 40), GridSize(140, 18)),
            measured.settledSizes(settle).toList(),
        )
    }

    @Test
    fun theFirstMeasurementStillArrives() = runTest {
        val measured = flow {
            emit(GridSize(80, 40))
            delay(settle * 2)
        }

        assertEquals(listOf(GridSize(80, 40)), measured.settledSizes(settle).toList())
    }
}
