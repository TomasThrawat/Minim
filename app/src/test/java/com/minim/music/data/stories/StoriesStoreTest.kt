package com.minim.music.data.stories

import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The channels and the "new" tag are saved on the phone and come back after a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoriesStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun ch(id: String) = StoryChannel(id, "Name $id", "https://www.youtube.com/channel/$id")

    @Test
    fun addSavesAndANewStoreSeesIt() {
        val store = StoriesStore(context)
        assertTrue(store.channels.isEmpty())
        assertTrue(store.add(ch("UCa")))
        assertTrue(store.add(ch("UCb")))
        assertFalse(store.add(ch("UCa")))
        // A fresh store (as after a restart or an update) reads the same list
        assertEquals(listOf("UCb", "UCa"), StoriesStore(context).channels.map { it.id })
    }

    @Test
    fun removeDropsTheChannelAndItsUploads() {
        val store = StoriesStore(context)
        store.add(ch("UCa"))
        store.add(ch("UCb"))
        store.saveUploads(
            listOf(
                StoryUpload("v1", "one", "UCa", "A", 300, 2),
                StoryUpload("v2", "two", "UCb", "B", 300, 1)
            ),
            at = 42L
        )
        store.remove("UCa")
        val again = StoriesStore(context)
        assertEquals(listOf("UCb"), again.channels.map { it.id })
        assertEquals(listOf("v2"), again.uploads.map { it.videoId })
        assertEquals(42L, again.fetchedAt)
    }

    @Test
    fun avatarUpdateIsKept() {
        val store = StoriesStore(context)
        store.add(ch("UCa"))
        store.updateAvatar("UCa", "https://yt3.ggpht.com/photo")
        assertEquals("https://yt3.ggpht.com/photo", StoriesStore(context).channels.single().avatarUrl)
    }

    @Test
    fun channelsEncodeAndDecode() {
        val list = listOf(StoryChannel("UCa", "A \"quoted\" name", "https://www.youtube.com/@a", "https://yt3.ggpht.com/p", "1M subscribers"))
        val back = StoriesStore.decodeChannels(StoriesStore.encodeChannels(list))
        assertEquals(listOf(list[0].copy(subtitle = "")), back)
        assertTrue(StoriesStore.decodeChannels("not json").isEmpty())
        assertTrue(StoriesStore.decodeChannels(null).isEmpty())
    }

    @Test
    fun newTagGoesAwayForGood() {
        assertFalse(AppPreferences(context).storiesTabSeen)
        AppPreferences(context).storiesTabSeen = true
        assertTrue(AppPreferences(context).storiesTabSeen)
    }
}
