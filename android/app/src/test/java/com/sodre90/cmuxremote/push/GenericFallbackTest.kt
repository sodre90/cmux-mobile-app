package com.sodre90.cmuxremote.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a relay/direct duplicate push from downgrading a
 * notification the user can act on into a content-free one (cmux-app-17r).
 */
class GenericFallbackTest {

    @Test
    fun nothingShowingMeansTheFallbackIsFreeToPost() {
        assertFalse(genericFallbackWouldHideContent(null))
    }

    @Test
    fun aDecryptedTitleAlreadyShowingMustNotBeReplaced() {
        assertTrue(genericFallbackWouldHideContent("Cmux android app 401s through tailscale"))
    }

    @Test
    fun replacingOneFallbackWithAnotherIsHarmless() {
        assertFalse(genericFallbackWouldHideContent(GENERIC_TITLE))
    }

    /** Notification extras come back as CharSequence, not String -- comparing
     *  the two without converting is the way this silently stops working. */
    @Test
    fun aNonStringCharSequenceStillComparesEqual() {
        assertFalse(genericFallbackWouldHideContent(StringBuilder(GENERIC_TITLE)))
    }

    @Test
    fun anEmptyTitleCountsAsContentWorthKeeping() {
        assertTrue(genericFallbackWouldHideContent(""))
    }
}
