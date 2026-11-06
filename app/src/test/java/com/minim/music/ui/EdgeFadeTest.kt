package com.minim.music.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * 5.7.0 u49: lists fade out under the header and behind the floating mini player instead of being
 * sliced off, and at rest at either end nothing sits under a fade.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class EdgeFadeTest {

    // xxhdpi
    private val d = 3f
    private fun dp(v: Float) = (v * d).roundToInt()

    // ---- the maths ----

    @Test
    fun `no fade at rest, full fade once scrolled the fade's length, full when the end is far`() {
        assertEquals(0f, EdgeFadeMath.strength(0, 74, 74), 0f)
        assertEquals(0.5f, EdgeFadeMath.strength(37, 74, 74), 0.001f)
        assertEquals(1f, EdgeFadeMath.strength(500, 74, 74), 0f)
        assertEquals(1f, EdgeFadeMath.strength(-1, 74, 74), 0f)
        // A shorter edge on a view whose fading length is the longer one is scaled down
        assertEquals(74f / 144f, EdgeFadeMath.strength(-1, 74, 144), 0.001f)
        assertEquals(0f, EdgeFadeMath.strength(10, 0, 74), 0f)
    }

    @Test
    fun `the bottom melts away by the middle of the floating pill`() {
        // Pill 54dp tall, 26dp above the bottom: 26..80dp
        val b = EdgeFadeMath.bottom(2000, dp(80f), dp(26f), d)
        assertEquals((dp(80f) + dp(26f)) / 2, b.clearPx)
        assertEquals(dp(80f) - b.clearPx + dp(EdgeFadeMath.ABOVE_PILL_DP), b.lengthPx)
        // So content is solid again just under 110dp, above the lists' 110-120dp bottom padding
        assertTrue(b.clearPx + b.lengthPx <= dp(110f))
    }

    @Test
    fun `without a floating pill the bottom gets a short edge fade`() {
        val edge = EdgeFadeMath.Bottom(dp(EdgeFadeMath.EDGE_BOTTOM_DP), 0)
        assertEquals(edge, EdgeFadeMath.bottom(2000, null, null, d))
        // A pill that isn't over the bottom of this list doesn't count
        assertEquals(edge, EdgeFadeMath.bottom(2000, 1500, 1400, d))
        assertEquals(edge, EdgeFadeMath.bottom(2000, 100, -20, d))
    }

    @Test
    fun `the top fade is 24 to 32dp`() {
        assertTrue(EdgeFadeMath.TOP_DP in 24f..32f)
    }

    // ---- a real list ----

    private class Probe(ctx: android.content.Context) : FadingRecyclerView(ctx) {
        fun top() = topFadingEdgeStrength
        fun bottom() = bottomFadingEdgeStrength
    }

    private class Rows(val n: Int, val h: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = n
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
                setBackgroundColor(Color.RED)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private class Screen(val root: FrameLayout, val list: Probe, val pill: View)

    private fun screen(pillShown: Boolean): Screen {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val list = Probe(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = Rows(40, dp(64f))
            clipToPadding = false
            setPadding(0, dp(22f), 0, dp(120f))
        }
        root.addView(list, FrameLayout.LayoutParams(-1, -1))
        val pill = View(activity).apply { id = R.id.mini_player; visibility = if (pillShown) View.VISIBLE else View.GONE }
        root.addView(pill, FrameLayout.LayoutParams(dp(300f), dp(54f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(26f)
        })
        activity.setContentView(root)
        layout(root)
        return Screen(root, list, pill)
    }

    private fun layout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2200)
        root.viewTreeObserver.dispatchOnGlobalLayout()
    }

    private fun render(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun alphaAt(bmp: Bitmap, y: Int) = Color.alpha(bmp.getPixel(bmp.width / 2, y))

    @Test
    fun `at rest at the top there is no top fade, and the first row is solid`() {
        val s = screen(pillShown = false)
        assertEquals(0, s.list.topDistance())
        assertEquals(0f, s.list.top(), 0f)
        assertTrue(s.list.bottom() > 0f)
        val bmp = render(s.list)
        assertEquals(255, alphaAt(bmp, dp(22f) + 1))
    }

    @Test
    fun `scrolled, the content fades into the background under the header`() {
        val s = screen(pillShown = false)
        s.list.scrollBy(0, dp(300f))
        layout(s.root)
        assertTrue("distance ${s.list.topDistance()}", s.list.topDistance() != 0)
        assertTrue("strength ${s.list.top()}", s.list.top() > 0f)
        val bmp = render(s.list)
        // See-through right at the header's edge, partly faded in the middle, solid below the fade
        assertTrue("edge alpha ${alphaAt(bmp, 1)}", alphaAt(bmp, 1) < 30)
        val mid = alphaAt(bmp, dp(EdgeFadeMath.TOP_DP) / 2)
        assertTrue("mid fade alpha $mid", mid in 40..220)
        assertEquals(255, alphaAt(bmp, dp(EdgeFadeMath.TOP_DP) + 4))
    }

    @Test
    fun `at rest at the end there is no bottom fade and nothing is cut off`() {
        val s = screen(pillShown = true)
        s.list.scrollToPosition(39)
        layout(s.root)
        s.list.scrollBy(0, 10_000)
        layout(s.root)
        assertFalse(s.list.canScrollVertically(1))
        assertEquals(0, s.list.bottomDistance())
        assertEquals(0f, s.list.bottom(), 0f)
        val bmp = render(s.list)
        // The last row sits right above the bottom padding, fully drawn
        assertEquals(255, alphaAt(bmp, s.list.height - dp(120f) - 2))
    }

    @Test
    fun `with the floating pill showing, rows melt away behind it`() {
        val s = screen(pillShown = true)
        val b = s.list.edgeFade.bottom
        assertEquals((dp(80f) + dp(26f)) / 2, b.clearPx)
        assertTrue(s.list.bottom() > 0f)
        val bmp = render(s.list)
        val h = s.list.height
        // Gone behind the pill's lower half, partly there beside its top, solid above the fade
        assertEquals(0, alphaAt(bmp, h - dp(30f)))
        assertEquals(0, alphaAt(bmp, h - b.clearPx + 2))
        val mid = alphaAt(bmp, h - b.clearPx - b.lengthPx / 2)
        assertTrue("mid fade alpha $mid", mid in 40..220)
        assertEquals(255, alphaAt(bmp, h - b.clearPx - b.lengthPx - 4))
    }

    @Test
    fun `when the pill goes away the bottom falls back to a short edge fade`() {
        val s = screen(pillShown = true)
        assertTrue(s.list.edgeFade.bottom.clearPx > 0)
        s.pill.visibility = View.GONE
        layout(s.root)
        assertEquals(0, s.list.edgeFade.bottom.clearPx)
        assertEquals(dp(EdgeFadeMath.EDGE_BOTTOM_DP), s.list.edgeFade.bottom.lengthPx)
        val bmp = render(s.list)
        assertTrue(alphaAt(bmp, s.list.height - 1) < 30)
        assertEquals(255, alphaAt(bmp, s.list.height - dp(EdgeFadeMath.EDGE_BOTTOM_DP) - 4))
    }

    // ---- where it's used ----

    private fun read(path: String): String {
        val f = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }
        return f.readText()
    }

    @Test
    fun `every list screen with a header uses the fading list`() {
        val fading = mapOf(
            "view_stories.xml" to listOf("stories_list"),
            "view_library.xml" to listOf("library_grid"),
            "view_list.xml" to listOf("list_tracks"),
            "view_queue.xml" to listOf("queue_list"),
            "view_search.xml" to listOf("discover_shelves", "search_results"),
            "view_shelf.xml" to listOf("shelf_grid"),
            "view_import_review.xml" to listOf("import_tracks"),
        )
        for ((file, ids) in fading) {
            val xml = read("res/layout/$file")
            for (id in ids) {
                assertTrue("$file $id", Regex("<com\\.minim\\.music\\.ui\\.FadingRecyclerView\\s+android:id=\"@\\+id/$id\"").containsMatchIn(xml))
            }
        }
        assertTrue(read("res/layout/view_stats.xml").contains("<com.minim.music.ui.FadingNestedScrollView\n        android:id=\"@+id/stats_scroll\""))
    }

    @Test
    fun `Up next sits above the pill so it only gets the edge fade, and Artist pages are untouched`() {
        val queue = read("res/layout/view_queue.xml")
        assertTrue(queue.contains("app:fadeAroundPill=\"false\""))
        assertFalse(read("res/layout/view_artist.xml").contains("Fading"))
    }
}
