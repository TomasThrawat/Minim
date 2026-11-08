package com.minim.music.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.model.Track
import com.minim.music.data.stories.StoryChannel
import com.minim.music.data.stories.StoryUpload
import com.minim.music.data.stories.StoryLength
import com.minim.music.data.stories.StoryPlayback
import com.minim.music.data.stories.StoryProgress
import com.minim.music.data.stories.StoryShelf
import com.minim.music.data.stories.StoryShelves
import com.minim.music.ui.Pal
import com.minim.music.ui.Painter
import com.minim.music.util.ArtworkPipeline

/**
 * 5.7.0 u44: the stories list. The added channels in a row on top (tap one to show only its
 * uploads, long-press to remove; since u45 a big + Add tile comes first in that row).
 *
 * 5.7.0 u46: under the channels, the length chips, then shelves like Search: Continue listening,
 * New this week and a sideways row per channel, of wide 16:9 cards. A channel's own list (its
 * photo or its row title tapped) still uses the usual song rows. Heard uploads go dim.
 */
class StoriesAdapter(
    private val artwork: ArtworkPipeline,
    private val onUpload: (Track, List<Track>) -> Unit,
    private val onUploadLong: (View, Track) -> Unit,
    private val onChannel: (StoryChannel) -> Unit,
    private val onChannelLong: (StoryChannel) -> Unit,
    /** 5.7.0 u45: the + Add tile, first in the channel row. */
    private val onAdd: (View) -> Unit,
    /** "Channel · 2 days ago · 24 min" for an upload's second line. */
    private val meta: (StoryUpload) -> String,
    /** 5.7.0 u46: a length chip tapped. */
    private val onChip: (View, StoryLength) -> Unit = { _, _ -> },
    /** 5.7.0 u46: a channel shelf's title tapped (opens that channel's list). */
    private val onShelfTitle: (StoryShelf) -> Unit = {},
    /** 5.7.0 u46: the line under a card's title, for the shelf it's on. */
    private val cardMeta: (StoryShelf, StoryUpload) -> String = { _, u -> meta(u) },
    /** 5.7.0 u46: a shelf's title (Continue listening, New this week, the channel name). */
    private val shelfTitle: ((StoryShelf) -> String)? = null,
    /** 5.7.0 u47: the New to You chip tapped. */
    private val onNewChip: (View) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 5.7.0 u47: New to You is showing (its chip is filled). Set before submitting. */
    var newToYouOn: Boolean = false

    /** 5.7.0 u47: channels that get the new-upload dot on their photo. */
    fun setNewChannels(ids: Set<String>) = channelAdapter.setNewChannels(ids)

    private sealed class Row {
        object Channels : Row()
        object Chips : Row()
        data class Label(val text: String) : Row()
        data class Note(val text: String) : Row()
        data class Upload(val upload: StoryUpload, val track: Track) : Row()
        data class Shelf(val shelf: StoryShelf, val tracks: List<Track>) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var tracks: List<Track> = emptyList()
    private var pal: Pal = Pal.build(0, false)

    /** 5.7.0 u47: tapes can be saved offline now, so their rows show the mark too. */
    var offline: OfflineMarks = OfflineMarks.NONE
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }
    private var activeId: String? = null
    private var length: StoryLength = StoryLength.ALL
    private var progress: Map<String, StoryPlayback> = emptyMap()
    private val channelAdapter = ChannelRowAdapter()
    /** One pile of cards shared by every shelf (all the same kind of tile). */
    private val cardPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(TYPE_CARD, 24) }
    /** Each shelf's sideways scroll, by shelf, so redraws and recycling keep your place. */
    private val shelfScroll = HashMap<String, android.os.Parcelable?>()

    /** Everything at once: the channel row, the label, an optional note, the uploads. */
    fun submit(channels: List<StoryChannel>, selectedId: String?, label: String, note: String?, uploads: List<StoryUpload>) =
        submitList(channels, selectedId, label, note, uploads, StoryLength.ALL, emptyMap(), chips = false)

    /** 5.7.0 u46: one channel's uploads as rows (with the chips), heard ones dim. */
    @Suppress("UNUSED_PARAMETER")
    fun submitList(
        channels: List<StoryChannel>, selectedId: String?, label: String, note: String?, uploads: List<StoryUpload>,
        length: StoryLength, progress: Map<String, StoryPlayback>, chips: Boolean = true, forceMeta: Boolean = false
    ) {
        channelAdapter.submit(channels, selectedId)
        this.length = length
        this.progress = progress
        val list = ArrayList<Row>(uploads.size + 4)
        if (channels.isNotEmpty()) list.add(Row.Channels)
        if (chips && channels.isNotEmpty()) list.add(Row.Chips)
        list.add(Row.Label(label))
        if (note != null) list.add(Row.Note(note))
        val ups = uploads.map { Row.Upload(it, it.toTrack()) }
        list.addAll(ups)
        tracks = ups.map { it.track }
        rows = list
        notifyDataSetChanged()
    }

    /** 5.7.0 u46: the shelves (empty ones already left out), with the chips and an optional note. */
    fun submitShelves(
        channels: List<StoryChannel>, shelves: List<StoryShelf>, note: String?, footNote: String?,
        length: StoryLength, progress: Map<String, StoryPlayback>
    ) {
        channelAdapter.submit(channels, null)
        this.length = length
        this.progress = progress
        val list = ArrayList<Row>(shelves.size + 4)
        if (channels.isNotEmpty()) {
            list.add(Row.Channels)
            list.add(Row.Chips)
        }
        if (note != null) list.add(Row.Note(note))
        for (sh in shelves) list.add(Row.Shelf(sh, sh.uploads.map { it.toTrack() }))
        if (footNote != null) list.add(Row.Note(footNote))
        tracks = emptyList()
        rows = list
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        channelAdapter.setPalette(p)
        notifyDataSetChanged()
    }

    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    /** For tests: the shelves in the list, top to bottom. */
    internal val shownShelves: List<StoryShelf> get() = rows.filterIsInstance<Row.Shelf>().map { it.shelf }
    internal val hasChips: Boolean get() = rows.any { it === Row.Chips }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        Row.Channels -> TYPE_CHANNELS
        Row.Chips -> TYPE_CHIPS
        is Row.Label -> TYPE_LABEL
        is Row.Note -> TYPE_NOTE
        is Row.Upload -> TYPE_UPLOAD
        is Row.Shelf -> TYPE_SHELF
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHANNELS -> {
                val rv = inf.inflate(R.layout.item_story_channels, parent, false) as RecyclerView
                rv.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                rv.adapter = channelAdapter
                rv.itemAnimator = null
                Plain(rv)
            }
            TYPE_CHIPS -> Plain(inf.inflate(R.layout.item_story_chips, parent, false))
            TYPE_LABEL -> Plain(inf.inflate(R.layout.item_section_label, parent, false))
            TYPE_NOTE -> Plain(inf.inflate(R.layout.item_story_note, parent, false))
            TYPE_SHELF -> ShelfHolder(inf.inflate(R.layout.item_shelf, parent, false))
            else -> TrackAdapter.Holder(inf.inflate(R.layout.item_track, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            Row.Channels -> Unit
            Row.Chips -> bindChips(holder.itemView)
            is Row.Label -> {
                (holder.itemView as TextView).text = row.text
                Painter.paint(holder.itemView, pal)
            }
            is Row.Note -> {
                (holder.itemView as TextView).text = row.text
                Painter.paint(holder.itemView, pal)
            }
            is Row.Upload -> {
                val h = holder as TrackAdapter.Holder
                h.bind(row.track, row.track.id == activeId, artwork, pal)
                val line = h.itemView.findViewById<TextView>(R.id.track_artist)
                line.text = meta(row.upload)
                // 5.7.0 u47: a small tick once it's played
                com.minim.music.ui.PlayedTick.apply(line, com.minim.music.data.stories.PlayedMark.isPlayed(progress[row.track.id]))
                h.bindOffline(row.track.id, offline)
                // 5.7.0 u46: heard ones step back (the one playing never does)
                h.itemView.alpha = if (StoryShelves.isDim(progress[row.track.id]) && row.track.id != activeId) DIM_ALPHA else 1f
                h.card.setOnClickListener { onUpload(row.track, tracks) }
                h.card.setOnLongClickListener { onUploadLong(it, row.track); true }
            }
            is Row.Shelf -> (holder as ShelfHolder).bind(row)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ShelfHolder) holder.saveScroll()
        super.onViewRecycled(holder)
    }

    private fun bindChips(v: View) {
        val short = v.findViewById<TextView>(R.id.story_chip_short)
        val long = v.findViewById<TextView>(R.id.story_chip_long)
        val fresh = v.findViewById<TextView>(R.id.story_chip_new)
        styleChip(fresh, newToYouOn)
        fresh.setOnClickListener { onNewChip(it) }
        styleChip(short, length == StoryLength.SHORT)
        styleChip(long, length == StoryLength.LONG)
        short.setOnClickListener { onChip(it, StoryLength.SHORT) }
        long.setOnClickListener { onChip(it, StoryLength.LONG) }
    }

    /** On: filled in the app colour. Off: an outline. */
    private fun styleChip(chip: TextView, on: Boolean) {
        val d = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f
            val stroke = (1.5f * chip.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            if (on) setColor(pal.fg) else {
                setColor(Color.TRANSPARENT)
                setStroke(stroke, androidx.core.graphics.ColorUtils.setAlphaComponent(pal.fg, 0x66))
            }
        }
        chip.background = d
        chip.setTextColor(if (on) pal.bg else pal.fg)
        chip.isSelected = on
    }

    /** One shelf: its title (a channel's opens its list) and a sideways row of wide cards. */
    private inner class ShelfHolder(view: View) : RecyclerView.ViewHolder(view) {
        val header: View = view.findViewById(R.id.shelf_header)
        val title: TextView = view.findViewById(R.id.shelf_title)
        val chevron: View = view.findViewById(R.id.shelf_chevron)
        val row: RecyclerView = view.findViewById(R.id.shelf_row)
        val cards = CardAdapter()
        var boundKey: String? = null

        init {
            row.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false).apply {
                recycleChildrenOnDetach = true
            }
            row.setRecycledViewPool(cardPool)
            row.adapter = cards
            row.itemAnimator = null
        }

        fun saveScroll() {
            val k = boundKey ?: return
            shelfScroll[k] = row.layoutManager?.onSaveInstanceState()
        }

        fun bind(r: Row.Shelf) {
            val sh = r.shelf
            val same = boundKey == sh.key
            if (!same) saveScroll()
            title.text = shelfTitle?.invoke(sh) ?: when (sh.kind) {
                StoryShelf.Kind.CONTINUE -> itemView.resources.getString(R.string.stories_continue)
                StoryShelf.Kind.NEW -> itemView.resources.getString(R.string.stories_new_week)
                StoryShelf.Kind.CHANNEL -> sh.title
            }
            val opens = sh.kind == StoryShelf.Kind.CHANNEL
            chevron.visibility = if (opens) View.VISIBLE else View.GONE
            // (setting a listener, even null, makes a view clickable, so that comes first)
            if (opens) header.setOnClickListener { onShelfTitle(sh) } else header.setOnClickListener(null)
            header.isClickable = opens
            header.isFocusable = opens
            cards.submit(sh, r.tracks)
            if (!same) {
                boundKey = sh.key
                val saved = shelfScroll[sh.key]
                if (saved != null) row.layoutManager?.onRestoreInstanceState(saved) else row.scrollToPosition(0)
            }
            Painter.paint(header, pal)
        }
    }

    /** The cards of one shelf. */
    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        private var shelf: StoryShelf? = null
        private var cardTracks: List<Track> = emptyList()

        fun submit(sh: StoryShelf, t: List<Track>) {
            shelf = sh
            cardTracks = t
            notifyDataSetChanged()
        }

        override fun getItemCount() = cardTracks.size
        override fun getItemViewType(position: Int) = TYPE_CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_story_card, parent, false))

        override fun onBindViewHolder(h: CardHolder, position: Int) {
            val sh = shelf ?: return
            val u = sh.uploads[position]
            val t = cardTracks[position]
            h.title.text = u.title
            h.meta.text = cardMeta(sh, u)
            h.itemView.contentDescription = u.title + ", " + h.meta.text
            val res = h.itemView.resources
            artwork.loadInto(h.art, t.thumbnailUrl, (CARD_WIDTH_DP * res.displayMetrics.density).toInt(), wide = true)
            val p = progress[u.videoId]
            val active = t.id == activeId
            h.outline.visibility = if (active) View.VISIBLE else View.GONE
            h.itemView.alpha = if (StoryShelves.isDim(p) && !active) DIM_ALPHA else 1f
            if (sh.kind == StoryShelf.Kind.CONTINUE && StoryProgress.isInProgress(p)) {
                h.progress.visibility = View.VISIBLE
                val full = ((CARD_WIDTH_DP - 24) * res.displayMetrics.density).toInt()
                val lp = h.progressFill.layoutParams
                lp.width = (full * StoryProgress.fraction(p)).toInt().coerceAtLeast(1)
                h.progressFill.layoutParams = lp
            } else {
                h.progress.visibility = View.GONE
            }
            h.itemView.setOnClickListener { onUpload(t, cardTracks) }
            h.itemView.setOnLongClickListener { onUploadLong(it, t); true }
            Painter.paint(h.itemView, pal)
            com.minim.music.ui.PlayedTick.apply(h.meta, com.minim.music.data.stories.PlayedMark.isPlayed(p))
        }
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById<ImageView>(R.id.story_card_art).apply { clipToOutline = true }
        val outline: View = view.findViewById(R.id.story_card_outline)
        val progress: View = view.findViewById(R.id.story_card_progress)
        val progressFill: View = view.findViewById(R.id.story_card_progress_fill)
        val title: TextView = view.findViewById(R.id.story_card_title)
        val meta: TextView = view.findViewById(R.id.story_card_meta)
    }

    private class Plain(view: View) : RecyclerView.ViewHolder(view)

    /** The channel row's adapter, for tests. */
    internal val channelRow: RecyclerView.Adapter<*> get() = channelAdapter

    /** The row of added channels, after the + Add tile (always first). */
    private inner class ChannelRowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var channels: List<StoryChannel> = emptyList()
        private var selected: String? = null
        private var rowPal: Pal = Pal.build(0, false)

        private var newIds: Set<String> = emptySet()

        fun setNewChannels(ids: Set<String>) {
            if (ids == newIds) return
            newIds = ids
            notifyDataSetChanged()
        }

        fun submit(list: List<StoryChannel>, selectedId: String?) {
            channels = list.toList()
            selected = selectedId
            notifyDataSetChanged()
        }

        fun setPalette(p: Pal) {
            rowPal = p
            notifyDataSetChanged()
        }

        override fun getItemCount() = channels.size + 1

        override fun getItemViewType(position: Int) = if (position == 0) TYPE_ADD_TILE else TYPE_CHANNEL_TILE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_ADD_TILE) Plain(inf.inflate(R.layout.item_story_add, parent, false).also {
                com.minim.music.ui.PressScale.apply(it)
            })
            else Holder(inf.inflate(R.layout.item_story_channel, parent, false))
        }

        override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) {
                // Never steps back while a channel is picked: it's an action, not a channel
                vh.itemView.alpha = 1f
                vh.itemView.setOnClickListener { onAdd(it) }
                Painter.paint(vh.itemView, rowPal)
                return
            }
            val holder = vh as Holder
            val c = channels[position - 1]
            holder.name.text = c.name
            holder.itemView.contentDescription = c.name
            holder.letter.text = c.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
            val px = (60 * holder.itemView.resources.displayMetrics.density).toInt()
            artwork.loadInto(holder.photo, c.avatarUrl, px)
            // While one channel is picked, the others step back
            holder.itemView.alpha = if (selected == null || selected == c.id) 1f else 0.38f
            holder.itemView.isSelected = selected == c.id
            holder.itemView.setOnClickListener { onChannel(c) }
            holder.itemView.setOnLongClickListener { onChannelLong(c); true }
            Painter.paint(holder.itemView, rowPal)
            // 5.7.0 u47: a dot when there's an upload from this week you haven't played
            val fresh = c.id in newIds
            holder.dot.visibility = if (fresh) View.VISIBLE else View.GONE
            if (fresh) {
                val dens = holder.itemView.resources.displayMetrics.density
                holder.dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(rowPal.fg)
                    setStroke((2.5f * dens).toInt().coerceAtLeast(2), rowPal.bg)
                }
                holder.itemView.contentDescription = holder.itemView.resources.getString(R.string.channel_new_upload_cd, c.name)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById<ImageView>(R.id.channel_photo).apply {
                clipToOutline = true
                // The circle only shapes the photo; the letter shows through until it loads
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }
            val letter: TextView = view.findViewById<TextView>(R.id.channel_letter).apply { clipToOutline = true }
            val name: TextView = view.findViewById(R.id.channel_name)
            val dot: View = view.findViewById(R.id.channel_dot)
        }
    }

    companion object {
        private const val TYPE_CHANNELS = 0
        private const val TYPE_LABEL = 1
        private const val TYPE_NOTE = 2
        private const val TYPE_UPLOAD = 3
        internal const val TYPE_CHIPS = 4
        internal const val TYPE_SHELF = 5
        /** Cards have their own type, so the shared pile only ever holds cards. */
        internal const val TYPE_CARD = 12
        /** 5.7.0 u46: how far a heard upload steps back. */
        const val DIM_ALPHA = 0.42f
        const val CARD_WIDTH_DP = 220
        internal const val TYPE_ADD_TILE = 10
        internal const val TYPE_CHANNEL_TILE = 11
    }
}
