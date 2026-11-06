package com.minim.music.data.artists

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.ArtistCountKind
import com.minim.music.data.model.ArtistRef
import com.minim.music.data.stories.LibraryFilter
import com.minim.music.data.stories.LibraryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u48: liking artists, and keeping them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LikedArtistsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val arijit = ArtistRef(
        url = "https://music.youtube.com/channel/UCDxKh1gFWeYsqePvgVzmPoQ",
        name = "Arijit Singh",
        count = 647_000_000L,
        thumbnailUrl = "https://lh3.example/arijit",
        countKind = ArtistCountKind.MONTHLY_LISTENERS
    )
    private val anirudh = ArtistRef(
        url = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
        name = "Anirudh Ravichander",
        count = 625_000_000L,
        thumbnailUrl = "",
        countKind = ArtistCountKind.MONTHLY_LISTENERS
    )

    @Before
    fun clear() {
        ctx.getSharedPreferences(LikedArtistsStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sameArtistSameKeyWhicheverLink() {
        val a = LikedArtistList.keyOf("https://music.youtube.com/channel/UCDxKh1gFWeYsqePvgVzmPoQ", "Arijit")
        val b = LikedArtistList.keyOf("https://www.youtube.com/channel/UCDxKh1gFWeYsqePvgVzmPoQ?si=x", "Arijit Singh")
        assertEquals(a, b)
        assertEquals(LikedArtistList.keyOf("https://x.com/@Vijay/", "V"), LikedArtistList.keyOf("https://x.com/@vijay", "V"))
        assertEquals("name:vijay", LikedArtistList.keyOf("", " Vijay "))
    }

    @Test
    fun toggleLikesNewestFirstAndUnlikes() {
        val store = LikedArtistsStore(ctx)
        assertTrue(store.toggle(arijit, now = 1))
        assertTrue(store.toggle(anirudh, now = 2))
        assertEquals(listOf("Anirudh Ravichander", "Arijit Singh"), store.artists.value.map { it.name })
        assertTrue(store.isLiked(arijit))
        assertFalse(store.toggle(arijit, now = 3))
        assertFalse(store.isLiked(arijit))
        assertEquals(listOf("Anirudh Ravichander"), store.artists.value.map { it.name })
    }

    @Test
    fun staysSavedAcrossRestarts() {
        LikedArtistsStore(ctx).apply {
            toggle(arijit, now = 5)
            toggle(anirudh, now = 6)
        }
        val again = LikedArtistsStore(ctx)
        val list = again.artists.value
        assertEquals(2, list.size)
        val a = list.first { it.name == "Arijit Singh" }
        assertEquals(647_000_000L, a.count)
        assertEquals(ArtistCountKind.MONTHLY_LISTENERS, a.countKind)
        assertEquals("https://lh3.example/arijit", a.thumbnailUrl)
        assertEquals(5L, a.likedAt)
        // what the row opens is the same artist page
        assertEquals(arijit.url, a.toRef().url)
        assertTrue(again.isLiked(arijit))
    }

    @Test
    fun refreshKeepsFresherPhotoButNotPlace() {
        val store = LikedArtistsStore(ctx)
        store.toggle(anirudh, now = 1)
        store.toggle(arijit, now = 2)
        store.refresh(anirudh.copy(thumbnailUrl = "https://lh3.example/anirudh", count = 630_000_000L))
        val list = store.artists.value
        assertEquals(listOf("Arijit Singh", "Anirudh Ravichander"), list.map { it.name })
        assertEquals("https://lh3.example/anirudh", list[1].thumbnailUrl)
        assertEquals(630_000_000L, list[1].count)
        assertEquals(1L, list[1].likedAt)
        // an artist that isn't liked isn't added by a refresh
        store.refresh(ArtistRef(url = "https://x/channel/UCzzzzzzzzzzzzzzzzzzzzzz", name = "Vijay", count = -1L, thumbnailUrl = ""))
        assertEquals(2, store.artists.value.size)
    }

    @Test
    fun brokenSavedDataIsSkipped() {
        assertTrue(LikedArtistList.decode("not json").isEmpty())
        val good = LikedArtistList.encode(listOf(LikedArtist.from(arijit, 1)))
        val mixed = good.dropLast(1) + ",{\"u\":\"\"},{\"n\":\"x\"}]"
        assertEquals(1, LikedArtistList.decode(mixed).size)
    }

    @Test
    fun artistsKindHasNoTrackRows() {
        assertTrue(LibraryFilter.split(emptyList(), LibraryKind.ARTISTS).isEmpty())
    }
}
