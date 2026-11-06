package com.minim.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.artists.LikedArtist
import com.minim.music.ui.Pal
import com.minim.music.ui.Painter
import com.minim.music.ui.RoundPhoto
import com.minim.music.util.ArtworkPipeline

/** 5.7.0 u48: Liked > Artists: one row per liked artist, newest first. Tap opens the Artist page. */
class LikedArtistAdapter(
    private val artwork: ArtworkPipeline,
    private val onClick: (LikedArtist) -> Unit
) : RecyclerView.Adapter<LikedArtistAdapter.Holder>() {

    private var items: List<LikedArtist> = emptyList()
    private var pal: Pal = Pal.build(0, false)

    fun submit(list: List<LikedArtist>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    fun setPalette(p: Pal) {
        pal = p
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_liked_artist, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val a = items[position]
        holder.name.text = a.name
        holder.count.text = formatArtistCount(holder.itemView.resources, a.count, a.countKind)
        holder.letter.text = a.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        holder.card.contentDescription = "${a.name}, ${holder.count.text}"
        val px = (44 * holder.itemView.resources.displayMetrics.density).toInt()
        artwork.loadInto(holder.photo, a.thumbnailUrl.ifBlank { null }, px)
        holder.card.setOnClickListener { onClick(a) }
        Painter.paint(holder.itemView, pal)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.liked_artist_card)
        val photo: ImageView = view.findViewById<ImageView>(R.id.liked_artist_photo).also { RoundPhoto.apply(it) }
        val letter: TextView = view.findViewById<TextView>(R.id.liked_artist_letter).apply { clipToOutline = true }
        val name: TextView = view.findViewById(R.id.liked_artist_name)
        val count: TextView = view.findViewById(R.id.liked_artist_count)
    }

    companion object {
        /** Its own view type, so a row is never handed a song row (or the other way round). */
        const val VIEW_TYPE = 4801
    }
}
