package com.minim.music.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.stories.StoryChannel
import com.minim.music.data.stories.StoryLength
import com.minim.music.data.stories.StoryPlayback
import com.minim.music.data.stories.StoryShelf
import com.minim.music.data.stories.StoryShelves
import com.minim.music.data.stories.StoryUpload
import com.minim.music.ui.adapter.StoriesAdapter
import com.minim.music.util.ArtworkPipeline
import com.minim.music.util.ArtworkUrls
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u46: the stories shelves on screen: chips, wide cards, dimming, the CC button's place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoriesShelvesUiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }
    private val artwork = ArtworkPipeline(ctx, OkHttpClient())
    private val now = System.currentTimeMillis()
    private val channels = listOf(
        StoryChannel("UC1", "Kaushik STORIES", "https://www.youtube.com/@kaushik"),
        StoryChannel("UC2", "SR PAY STORIES", "https://www.youtube.com/@srpay")
    )
    private val uploads = listOf(
        StoryUpload("aaaaaaaaaa1", "New one", "UC1", "Kaushik STORIES", 25 * 60, now - 3_600_000L),
        StoryUpload("aaaaaaaaaa2", "Old one", "UC1", "Kaushik STORIES", 40 * 60, now - 20 * 86_400_000L),
        StoryUpload("bbbbbbbbbb1", "Other", "UC2", "SR PAY STORIES", 15 * 60, now - 30 * 86_400_000L)
    )

    private fun adapter(
        onChip: (View, StoryLength) -> Unit = { _, _ -> },
        onShelfTitle: (StoryShelf) -> Unit = {}
    ) = StoriesAdapter(
        artwork = artwork,
        onUpload = { _, _ -> },
        onUploadLong = { _, _ -> },
        onChannel = {},
        onChannelLong = {},
        onAdd = {},
        meta = { "" },
        onChip = onChip,
        onShelfTitle = onShelfTitle
    )

    private fun bind(a: StoriesAdapter, pos: Int): RecyclerView.ViewHolder {
        val h = a.onCreateViewHolder(FrameLayout(ctx), a.getItemViewType(pos))
        a.onBindViewHolder(h, pos)
        return h
    }

    @Test
    fun shelvesShow_withChipsUnderTheChannelRow_andEmptyShelvesLeftOut() {
        val a = adapter()
        val progress = mapOf("aaaaaaaaaa2" to StoryPlayback(10 * 60_000L, 40 * 60_000L, false, now))
        val shelves = StoryShelves.build(uploads, channels, progress, StoryLength.ALL, now)
        a.submitShelves(channels, shelves, null, null, StoryLength.ALL, progress)
        assertTrue(a.hasChips)
        assertEquals(
            listOf(StoryShelf.Kind.CONTINUE, StoryShelf.Kind.NEW, StoryShelf.Kind.CHANNEL, StoryShelf.Kind.CHANNEL),
            a.shownShelves.map { it.kind }
        )
        // Long only: Continue (the 40 min one) and Kaushik's row; nothing new, nothing from SR PAY
        val long = StoryShelves.build(uploads, channels, progress, StoryLength.LONG, now)
        a.submitShelves(channels, long, null, null, StoryLength.LONG, progress)
        assertEquals(listOf(StoryShelf.Kind.CONTINUE, StoryShelf.Kind.CHANNEL), a.shownShelves.map { it.kind })
        assertEquals("UC1", a.shownShelves[1].channelId)
    }

    @Test
    fun chips_tap_andShowWhichIsOn() {
        var tapped: StoryLength? = null
        val a = adapter(onChip = { _, l -> tapped = l })
        a.submitShelves(channels, StoryShelves.build(uploads, channels, emptyMap(), StoryLength.SHORT, now), null, null, StoryLength.SHORT, emptyMap())
        val chipsPos = (0 until a.itemCount).first { a.getItemViewType(it) == StoriesAdapter.TYPE_CHIPS }
        val v = bind(a, chipsPos).itemView
        val short = v.findViewById<TextView>(R.id.story_chip_short)
        val long = v.findViewById<TextView>(R.id.story_chip_long)
        assertEquals("Under 20 mins", short.text.toString())
        assertEquals("30+ mins", long.text.toString())
        // u47: New to You comes first, off until tapped
        val fresh = v.findViewById<TextView>(R.id.story_chip_new)
        assertEquals("New to You", fresh.text.toString())
        assertFalse(fresh.isSelected)
        assertTrue(short.isSelected)
        assertFalse(long.isSelected)
        long.performClick()
        assertEquals(StoryLength.LONG, tapped)
    }

    @Test
    fun channelShelfTitle_opensThatChannel_otherTitlesDont() {
        var opened: StoryShelf? = null
        val a = adapter(onShelfTitle = { opened = it })
        a.submitShelves(channels, StoryShelves.build(uploads, channels, emptyMap(), StoryLength.ALL, now), null, null, StoryLength.ALL, emptyMap())
        val shelfPositions = (0 until a.itemCount).filter { a.getItemViewType(it) == StoriesAdapter.TYPE_SHELF }
        val newShelf = bind(a, shelfPositions[0]).itemView
        assertEquals("New this week", newShelf.findViewById<TextView>(R.id.shelf_title).text.toString())
        assertFalse(newShelf.findViewById<View>(R.id.shelf_header).isClickable)
        val chShelf = bind(a, shelfPositions[1]).itemView
        assertEquals("Kaushik STORIES", chShelf.findViewById<TextView>(R.id.shelf_title).text.toString())
        chShelf.findViewById<View>(R.id.shelf_header).performClick()
        assertEquals("UC1", opened?.channelId)
    }

    @Test
    fun wideCards_are16by9_withTitleAndLength_andHeardOnesDim() {
        val card = LayoutInflater.from(ctx).inflate(R.layout.item_story_card, FrameLayout(ctx), false)
        val frame = card.findViewById<View>(R.id.story_card_frame)
        val d = ctx.resources.displayMetrics.density
        val w = frame.layoutParams.width / d
        val h = frame.layoutParams.height / d
        assertEquals(16f / 9f, w / h, 0.02f)
        assertTrue(card.findViewById<ImageView>(R.id.story_card_art).background != null) // rounded card shape
        assertEquals(2, card.findViewById<TextView>(R.id.story_card_title).maxLines)
        // The thumbnail keeps 16:9: only a 4:3 file's black bars are cut, not the sides
        val band = ArtworkUrls.wideCrop(480, 360)
        assertEquals(270, band.height)
        assertEquals(45, band.top)
        assertEquals(ArtworkUrls.Band(0, 720), ArtworkUrls.wideCrop(1280, 720))

        // A heard upload's card fades
        val a = adapter()
        val heard = mapOf("aaaaaaaaaa1" to StoryPlayback(25 * 60_000L, 25 * 60_000L, true, now))
        a.submitShelves(channels, StoryShelves.build(uploads, channels, heard, StoryLength.ALL, now), null, null, StoryLength.ALL, heard)
        val pos = (0 until a.itemCount).first { a.getItemViewType(it) == StoriesAdapter.TYPE_SHELF }
        val shelf = bind(a, pos).itemView.findViewById<RecyclerView>(R.id.shelf_row)
        val cards = shelf.adapter!!
        val ch = cards.onCreateViewHolder(shelf, cards.getItemViewType(0))
        @Suppress("UNCHECKED_CAST")
        (cards as RecyclerView.Adapter<RecyclerView.ViewHolder>).onBindViewHolder(ch, 0)
        assertEquals(StoriesAdapter.DIM_ALPHA, ch.itemView.alpha, 0.001f)
        assertEquals("New one", ch.itemView.findViewById<TextView>(R.id.story_card_title).text.toString())
    }

    @Test
    fun ccButton_sitsNextToThe3Dots_hiddenUntilAnUploadPlays() {
        val np = LayoutInflater.from(ctx).inflate(R.layout.view_now_playing, FrameLayout(ctx), false)
        val cc = np.findViewById<ImageView>(R.id.np_cc)
        val more = np.findViewById<View>(R.id.np_more)
        assertEquals(View.GONE, cc.visibility)
        assertTrue(cc.parent === more.parent)
        assertEquals("tint:fg", cc.tag)
        assertEquals("Captions", cc.contentDescription.toString())
        val lp = cc.layoutParams as FrameLayout.LayoutParams
        val d = ctx.resources.displayMetrics.density
        // Right next to the 3 dots (which are 40dp wide at the end)
        assertEquals(40f, lp.marginEnd / d, 0.5f)
    }
}
