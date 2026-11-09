package com.minim.music.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingLikeQueueTest {
    @Test
    fun activeAccountOwnsChangesWhenSignedIn() {
        assertEquals("account-b", PendingLikeQueue.ownerForChange("account-b", "account-a"))
    }

    @Test
    fun signedOutChangesStayWithTheLastAccount() {
        assertEquals("account-a", PendingLikeQueue.ownerForChange(null, "account-a"))
    }

    @Test
    fun changesBeforeAnyAccountAreUnassigned() {
        assertEquals(PendingLikeQueue.UNASSIGNED_ACCOUNT, PendingLikeQueue.ownerForChange(null, null))
    }

    @Test
    fun pendingPreferencesAreIsolatedByAccount() {
        assertEquals("pending_likes_account-a", PendingLikeQueue.preferenceKey("account-a"))
        assertEquals("pending_likes_account-b", PendingLikeQueue.preferenceKey("account-b"))
    }

    @Test
    fun unlikeIsNotConfirmedWhileRemoteStillContainsSong() {
        assertFalse(PendingLikeQueue.isConfirmed("song-1|0", setOf("song-1")) == true)
    }

    @Test
    fun unlikeIsConfirmedOnlyAfterRemoteRemovesSong() {
        assertTrue(PendingLikeQueue.isConfirmed("song-1|0", emptySet()) == true)
    }

    @Test
    fun likeIsConfirmedOnlyWhenRemoteContainsSong() {
        assertTrue(PendingLikeQueue.isConfirmed("song-1|1", setOf("song-1")) == true)
        assertFalse(PendingLikeQueue.isConfirmed("song-1|1", emptySet()) == true)
    }

    @Test
    fun malformedPendingChangesAreRejected() {
        assertEquals(null, PendingLikeQueue.parse("song-1|maybe"))
        assertEquals(null, PendingLikeQueue.parse("|0"))
    }
}
