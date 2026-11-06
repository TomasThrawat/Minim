package com.minim.music.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.7.0 u47: what the rename, the filters, the sleep timer and the speed menu left in the source. */
class TapesU47SourceTest {
    private val main: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun read(path: String) = File(main, path).readText()

    @Test
    fun `nothing you can see says stories, and Liked Songs is just Liked`() {
        val strings = read("res/values/strings.xml")
        val visible = Regex(">([^<]*)<").findAll(strings).map { it.groupValues[1] }.toList()
        assertFalse(visible.any { Regex("\\bstor(y|ies)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        assertFalse(visible.any { it.contains("Liked Songs", ignoreCase = true) })
        assertTrue(strings.contains("<string name=\"liked_songs\">Liked</string>"))
        assertTrue(strings.contains("<string name=\"tab_stories\">tapes</string>"))
    }

    @Test
    fun `sleep timer is gone from Settings and lives in Now Playing`() {
        assertFalse(read("res/layout/view_settings.xml").contains("set_sleep"))
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertFalse(activity.contains("R.id.set_sleep"))
        assertTrue(activity.contains("R.string.sleep_end_tape") && activity.contains("R.string.sleep_end_song"))
        assertTrue(activity.contains("showCustomSleepSheet()"))
        val np = read("res/layout/view_now_playing.xml")
        assertTrue(np.contains("@+id/np_sleep") && np.contains("@+id/np_sleep_text") && np.contains("@+id/np_download"))
    }

    @Test
    fun `speed is for tapes only and Liked and Offline have the filter`() {
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertTrue(activity.contains("if (isTape) {\n                sheetAction(\n                    box,\n                    R.drawable.ic_speed"))
        val list = read("res/layout/view_list.xml")
        assertTrue(list.contains("@+id/list_filter_songs") && list.contains("@+id/list_filter_tapes"))
        assertTrue(activity.contains("openListId == LIKED_LIST || openListId == OFFLINE_LIST"))
        val artist = read("res/layout/view_artist.xml")
        assertTrue(artist.contains("@+id/channel_sort_latest") && artist.contains("@+id/channel_sort_popular"))
    }
}
