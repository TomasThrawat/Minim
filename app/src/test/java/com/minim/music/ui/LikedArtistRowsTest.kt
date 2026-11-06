package com.minim.music.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.artists.LikedArtist
import com.minim.music.data.model.ArtistCountKind
import com.minim.music.ui.adapter.LikedArtistAdapter
import com.minim.music.util.ArtworkPipeline
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u48: Liked > Artists rows and the Artist page heart are on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LikedArtistRowsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }

    @Test
    fun rowShowsNameCountAndOpensTheArtist() {
        var opened: LikedArtist? = null
        val adapter = LikedArtistAdapter(ArtworkPipeline(ctx, OkHttpClient())) { opened = it }
        val a = LikedArtist("ch:UC1", "https://music.youtube.com/channel/UC1", "Anirudh Ravichander", "", 625_000_000L, ArtistCountKind.MONTHLY_LISTENERS, 1L)
        adapter.submit(listOf(a))
        assertEquals(1, adapter.itemCount)
        val parent = FrameLayout(ctx)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Anirudh Ravichander", holder.name.text.toString())
        assertTrue(holder.count.text.toString(), holder.count.text.toString().contains("monthly listeners"))
        assertEquals("A", holder.letter.text.toString())
        holder.card.performClick()
        assertEquals(a, opened)
        // its own row type, never shared with song rows
        assertEquals(LikedArtistAdapter.VIEW_TYPE, adapter.getItemViewType(0))
    }

    @Test
    fun artistPageHasTheHeartAndLikedHasTheArtistsChip() {
        val page = LayoutInflater.from(ctx).inflate(R.layout.view_artist, FrameLayout(ctx), false)
        val heart = page.findViewById<View>(R.id.artist_like)
        assertNotNull(heart)
        // hidden until the artist is known (and on Channel pages)
        assertEquals(View.GONE, heart.visibility)
        val list = LayoutInflater.from(ctx).inflate(R.layout.view_list, FrameLayout(ctx), false)
        val chip = list.findViewById<TextView>(R.id.list_filter_artists)
        assertEquals("Artists", chip.text.toString())
    }
}
