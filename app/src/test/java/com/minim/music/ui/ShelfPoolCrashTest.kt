package com.minim.music.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.discover.Shelf
import com.minim.music.data.model.Track
import com.minim.music.ui.adapter.ShelfAdapter
import com.minim.music.ui.adapter.ShelfArtistAdapter
import com.minim.music.ui.adapter.ShelfSongAdapter
import com.minim.music.ui.adapter.ShelfViewTypes
import com.minim.music.util.ArtworkPipeline
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 5.7.0 (update 43): the Search page crashed with "ShelfArtistAdapter\$Holder cannot be cast to
 * ShelfSongAdapter\$Holder". Shelf rows share a RecycledViewPool, so every tile adapter that can sit in
 * a row needs its own view type, and a row switching between artists and songs must never hand one
 * kind of tile to the other adapter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShelfPoolCrashTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }
    private val artwork = ArtworkPipeline(ctx, OkHttpClient())

    private fun t(id: String) = Track(id = id, title = id, artist = "A", durationSec = 1, thumbnailUrl = "", sourceUrl = "")
    private fun songs(key: String) = Shelf(key, key, (1..8).map { t("$key$it") })
    private val artistRow = Shelf(TOP_ARTISTS_SHELF, "Your top artists", emptyList(),
        artists = (1..8).map { "Artist $it" })

    @Test
    fun `every adapter that shares the shelf pool has its own view type`() {
        assertEquals(ShelfViewTypes.ALL.size, ShelfViewTypes.ALL.toSet().size)
        val song = ShelfSongAdapter(artwork).apply { bind(songs("s"), null, Pal.build(0, false), { _, _ -> }, { _, _ -> }) }
        val artist = ShelfArtistAdapter(artwork).apply { bind(listOf("a"), Pal.build(0, false), { null }, {}, {}) }
        assertEquals(ShelfViewTypes.SONG, song.getItemViewType(0))
        assertEquals(ShelfViewTypes.ARTIST, artist.getItemViewType(0))
        assertNotEquals(song.getItemViewType(0), artist.getItemViewType(0))
    }

    private fun layOut(h: ShelfAdapter.Holder) {
        val v = h.itemView
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
        v.layout(0, 0, 1080, v.measuredHeight)
    }

    @Test
    fun `rows swapping between artists and songs never get the other kind of tile`() {
        val adapter = ShelfAdapter(artwork, {}, { _, _ -> }, { _, _ -> })
        val parent = FrameLayout(ctx)
        val a = adapter.onCreateViewHolder(parent, 0)
        val b = adapter.onCreateViewHolder(parent, 0)
        val list = listOf(artistRow, songs("heavy"), songs("mix"))
        adapter.submit(list)

        // first draw: row A shows artists, row B shows songs
        adapter.onBindViewHolder(a, 0); layOut(a)
        adapter.onBindViewHolder(b, 1); layOut(b)
        assertTrue(a.row.childCount > 0)
        assertTrue(b.row.childCount > 0)

        // after scrolling the holders get reused the other way round (update 42 crashed here)
        adapter.onBindViewHolder(a, 2); layOut(a)
        adapter.onBindViewHolder(b, 0); layOut(b)
        adapter.onBindViewHolder(a, 0); layOut(a)
        adapter.onBindViewHolder(b, 1); layOut(b)

        for (row in listOf(a.row, b.row)) {
            for (i in 0 until row.childCount) {
                val holder = row.getChildViewHolder(row.getChildAt(i))
                if (row.adapter is ShelfSongAdapter) assertTrue(holder is ShelfSongAdapter.Holder)
                else assertTrue(holder is ShelfArtistAdapter.Holder)
            }
        }
        val pool: RecyclerView.RecycledViewPool = a.row.recycledViewPool
        assertTrue("rows share one pool", pool === b.row.recycledViewPool)
        while (true) {
            val h = pool.getRecycledView(ShelfViewTypes.SONG) ?: break
            assertTrue(h is ShelfSongAdapter.Holder)
        }
        while (true) {
            val h = pool.getRecycledView(ShelfViewTypes.ARTIST) ?: break
            assertTrue(h is ShelfArtistAdapter.Holder)
        }
    }
}
