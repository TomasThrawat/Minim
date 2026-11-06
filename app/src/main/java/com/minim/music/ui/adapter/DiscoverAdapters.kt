package com.minim.music.ui.adapter

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.discover.Shelf
import com.minim.music.data.model.Album
import com.minim.music.data.model.Track
import com.minim.music.ui.Painter
import com.minim.music.ui.Pal
import com.minim.music.util.ArtworkPipeline

// =============================================================================================
// v4.3 Discover: a vertical list of shelves, each a title + a horizontal row of songs.
// Coloured by Painter from the layout tags, like every other list.
// =============================================================================================

class ShelfAdapter(
    private val artwork: ArtworkPipeline,
    private val onOpenShelf: (Shelf) -> Unit,
    private val onSongClick: (Track, Shelf) -> Unit,
    private val onSongLongClick: (View, Track) -> Unit,
    /** 5.7.0: an artist in an artist row was tapped. */
    private val onArtistClick: (String) -> Unit = {},
    /** 5.7.0: the photo URL saved for an artist, or null (then [onArtistPhotoNeeded] finds one). */
    private val artistPhoto: (String) -> String? = { null },
    private val onArtistPhotoNeeded: (String) -> Unit = {}
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var shelves: List<Shelf> = emptyList()
    private var activeId: String? = null
    private var pal: Pal = Pal.build(0, false)
    private val rowPool = RecyclerView.RecycledViewPool()
    /** Keeps each shelf's sideways scroll while the list is redrawn (colours, playing song). */
    private val rowState = HashMap<String, android.os.Parcelable?>()

    fun submit(list: List<Shelf>) {
        shelves = list.toList()
        notifyDataSetChanged()
    }

    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = shelves.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf, parent, false)
        return Holder(v, artwork, rowPool)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val shelf = shelves[position]
        holder.boundKey?.let { rowState[it] = holder.row.layoutManager?.onSaveInstanceState() }
        holder.boundKey = shelf.key
        holder.title.text = shelf.title
        holder.header.contentDescription = shelf.title
        if (shelf.artists.isNotEmpty()) {
            // 5.7.0: an artist row: no shelf page behind the title, each photo opens the artist
            holder.header.setOnClickListener(null)
            holder.header.isClickable = false
            holder.chevron.visibility = View.GONE
            holder.useArtists()
            holder.artists.bind(shelf.artists, pal, artistPhoto, onArtistPhotoNeeded, onArtistClick)
        } else {
            holder.header.setOnClickListener { onOpenShelf(shelf) }
            holder.chevron.visibility = View.VISIBLE
            holder.useSongs()
            holder.songs.bind(shelf, activeId, pal, onSongClick, onSongLongClick)
        }
        val saved = rowState[shelf.key]
        if (saved != null) holder.row.layoutManager?.onRestoreInstanceState(saved)
        else holder.row.scrollToPosition(0)
        Painter.paint(holder.itemView, pal)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.boundKey?.let { rowState[it] = holder.row.layoutManager?.onSaveInstanceState() }
    }

    class Holder(view: View, artwork: ArtworkPipeline, pool: RecyclerView.RecycledViewPool) :
        RecyclerView.ViewHolder(view) {
        val header: View = view.findViewById(R.id.shelf_header)
        val title: TextView = view.findViewById(R.id.shelf_title)
        val row: RecyclerView = view.findViewById(R.id.shelf_row)
        val chevron: View = view.findViewById(R.id.shelf_chevron)
        val songs = ShelfSongAdapter(artwork)
        val artists = ShelfArtistAdapter(artwork)
        var boundKey: String? = null
        private val pool = pool

        // 5.7.0 (update 43): every row shares one pool, and song tiles and artist tiles have their own
        // view types (ShelfViewTypes), so the pool only ever hands a row the kind of tile it asks for.
        // Update 42 swapped pools here, which dropped the old row's artist tiles into the song pool
        // (both used view type 0) and crashed a song row that picked one up.
        fun useSongs() {
            if (row.adapter !== songs) row.adapter = songs
        }

        fun useArtists() {
            if (row.adapter !== artists) row.adapter = artists
        }

        init {
            row.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 4
            }
            row.setRecycledViewPool(pool)
            row.adapter = songs
            row.itemAnimator = null
        }
    }
}

class ShelfSongAdapter(private val artwork: ArtworkPipeline) : RecyclerView.Adapter<ShelfSongAdapter.Holder>() {

    private var shelf: Shelf? = null
    private var activeId: String? = null
    private var pal: Pal = Pal.build(0, false)
    private var onClick: (Track, Shelf) -> Unit = { _, _ -> }
    private var onLongClick: (View, Track) -> Unit = { _, _ -> }
    /** v4.6: taps on an album tile (Artist page "Albums" shelf). */
    var onAlbumClick: (Album) -> Unit = {}

    fun bind(
        s: Shelf, active: String?, p: Pal,
        click: (Track, Shelf) -> Unit, longClick: (View, Track) -> Unit
    ) {
        shelf = s
        activeId = active
        pal = p
        onClick = click
        onLongClick = longClick
        notifyDataSetChanged()
    }

    /** v4.6 (Artist page rows): repaint / move the playing ring without a full bind. */
    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    fun setActiveTrack(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = ShelfViewTypes.SONG

    override fun getItemCount(): Int {
        val s = shelf ?: return 0
        return if (s.albums.isNotEmpty()) s.albums.size else s.tracks.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_song, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = shelf ?: return
        if (s.albums.isNotEmpty()) {
            val album = s.albums[position]
            holder.title.text = album.title
            holder.artist.text = album.subtitle
            holder.outline.visibility = View.GONE
            artwork.loadInto(holder.art, album.thumbnailUrl, ART_PX)
            holder.itemView.contentDescription = "${album.title}, ${album.subtitle}"
            holder.itemView.setOnClickListener { onAlbumClick(album) }
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
            Painter.paint(holder.itemView, pal)
            return
        }
        val track = s.tracks[position]
        holder.title.text = track.title
        holder.artist.text = track.artist
        holder.outline.visibility = if (track.id == activeId) View.VISIBLE else View.GONE
        artwork.loadInto(holder.art, track.thumbnailUrl, ART_PX)
        holder.itemView.contentDescription = "${track.title}, ${track.artist}"
        holder.itemView.setOnClickListener { onClick(track, s) }
        holder.itemView.setOnLongClickListener { onLongClick(it, track); true }
        Painter.paint(holder.itemView, pal)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById<ImageView>(R.id.song_art).apply { clipToOutline = true }
        val outline: View = view.findViewById(R.id.song_outline)
        val title: TextView = view.findViewById(R.id.song_title)
        val artist: TextView = view.findViewById(R.id.song_artist)
    }

    private companion object {
        val ART_PX = (120 * Resources.getSystem().displayMetrics.density).toInt()
    }
}

/**
 * 5.7.0 (update 43): view types for the tiles inside shelf rows. The rows share one RecycledViewPool,
 * so every adapter that can sit in a shelf row needs its own value here; the pool sorts holders by it.
 */
object ShelfViewTypes {
    const val SONG = 0x5E1F_0001
    const val ARTIST = 0x5E1F_0002
    val ALL = listOf(SONG, ARTIST)
}

/** 5.7.0: the round artist photos in a Search page artist row. */
class ShelfArtistAdapter(private val artwork: ArtworkPipeline) : RecyclerView.Adapter<ShelfArtistAdapter.Holder>() {

    private var names: List<String> = emptyList()
    private var pal: Pal = Pal.build(0, false)
    private var photo: (String) -> String? = { null }
    private var needPhoto: (String) -> Unit = {}
    private var onClick: (String) -> Unit = {}

    fun bind(
        list: List<String>,
        p: Pal,
        photoFor: (String) -> String?,
        onNeedPhoto: (String) -> Unit,
        click: (String) -> Unit
    ) {
        names = list
        pal = p
        photo = photoFor
        needPhoto = onNeedPhoto
        onClick = click
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = names.size

    override fun getItemViewType(position: Int): Int = ShelfViewTypes.ARTIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_artist, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val name = names[position]
        holder.name.text = name
        holder.itemView.contentDescription = name
        holder.letter.text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        val url = photo(name)
        val px = (96 * holder.itemView.resources.displayMetrics.density).toInt()
        artwork.loadInto(holder.photo, url, px)
        if (url == null) needPhoto(name)
        holder.itemView.setOnClickListener { onClick(name) }
        Painter.paint(holder.itemView, pal)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById<ImageView>(R.id.artist_photo).apply { clipToOutline = true }
        val letter: TextView = view.findViewById<TextView>(R.id.artist_letter).apply { clipToOutline = true }
        val name: TextView = view.findViewById(R.id.artist_name)
    }
}
