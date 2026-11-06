package com.minim.music.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.model.Track
import com.minim.music.data.stories.PlayedMark
import com.minim.music.data.stories.StoryPlayback
import com.minim.music.data.stories.StoryShelves
import com.minim.music.data.stories.StoryUpload
import com.minim.music.ui.Pal
import com.minim.music.ui.PlayedTick

/** 5.7.0 u47: a Channel page's videos, as the same rows a channel's list uses on tapes. */
class ChannelVideosAdapter(
    private val artwork: com.minim.music.util.ArtworkPipeline,
    private val onUpload: (Track, List<Track>) -> Unit,
    private val onUploadLong: (android.view.View, Track) -> Unit,
    private val meta: (StoryUpload) -> String
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var items: List<Pair<StoryUpload, Track>> = emptyList()
    private var progress: Map<String, StoryPlayback> = emptyMap()
    private var activeId: String? = null
    private var pal: Pal = Pal.build(0, false)
    var offline: OfflineMarks = OfflineMarks.NONE
        set(v) { field = v; notifyDataSetChanged() }

    fun submit(uploads: List<StoryUpload>, progress: Map<String, StoryPlayback>) {
        items = uploads.map { it to it.toTrack() }
        this.progress = progress
        notifyDataSetChanged()
    }

    fun setActive(id: String?) {
        if (id == activeId) return
        activeId = id
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        TrackAdapter.Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun onBindViewHolder(h: TrackAdapter.Holder, position: Int) {
        val (u, t) = items[position]
        h.bind(t, t.id == activeId, artwork, pal)
        val line = h.itemView.findViewById<TextView>(R.id.track_artist)
        line.text = meta(u)
        PlayedTick.apply(line, PlayedMark.isPlayed(progress[t.id]))
        h.bindOffline(t.id, offline)
        h.itemView.alpha = if (StoryShelves.isDim(progress[t.id]) && t.id != activeId) 0.45f else 1f
        val all = items.map { it.second }
        h.card.setOnClickListener { onUpload(t, all) }
        h.card.setOnLongClickListener { onUploadLong(it, t); true }
    }
}
