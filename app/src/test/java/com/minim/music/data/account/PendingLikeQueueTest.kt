package com.minim.music.data.account

import org.junit.Assert.assertEquals
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
}
