package com.minim.music.ui

import com.minim.music.data.discover.DiscoverEngine
import com.minim.music.data.discover.Shelf

/** 5.7.0: the key of the top artists row on the Search page. */
const val TOP_ARTISTS_SHELF = "top-artists"

/**
 * 5.7.0: the Search page's shelves, in order: your top artists (round photos), the Gemini shelf,
 * then Discover's shelves. Pure, so it's unit tested.
 *
 * The two extras only show next to Discover's shelves: with none of those, the page shows its
 * "play a few songs" or error message instead and nothing sits under it. The artist row needs
 * at least [DiscoverEngine.MIN_ARTIST_ROW] artists and the Gemini shelf at least
 * [MIN_GEMINI_SONGS] songs.
 */
fun buildSearchShelves(
    discover: List<Shelf>,
    topArtists: List<String>,
    gemini: Shelf?,
    topArtistsTitle: String,
): List<Shelf> {
    if (discover.isEmpty()) return emptyList()
    val out = ArrayList<Shelf>(discover.size + 2)
    if (topArtists.size >= DiscoverEngine.MIN_ARTIST_ROW) {
        out += Shelf(TOP_ARTISTS_SHELF, topArtistsTitle, emptyList(), artists = topArtists.take(DiscoverEngine.ARTIST_ROW))
    }
    if (gemini != null && gemini.tracks.size >= MIN_GEMINI_SONGS) out += gemini
    out += discover.filter { it.key != TOP_ARTISTS_SHELF && it.key != gemini?.key }
    return out
}

/** Fewer songs than this left on the Gemini shelf (after "Not interested") and it's hidden. */
const val MIN_GEMINI_SONGS = 3
