package com.minim.music.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * 5.7.0 u45: the mini player pills stretched almost edge to edge with a long title. Both the Home
 * pill and the floating one now keep [MiniPill.SIDE_MARGIN_DP] from each side, and the scrolling
 * title stays clear of the heart.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measuring, so a long title really is wider than the screen
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MiniPillMarginTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }
    private val longTitle = "Real Horror Story Hyderabad Me Raat Ko Kya Hua Full Story In Hindi (Real Horror Story)"

    @Test
    fun `the widest pill keeps 40dp from each side`() {
        // 1080 px wide at 2.625x: 411 dp, less 80 dp
        assertEquals((1080 - 80 * 2.625f).roundToInt(), MiniPill.maxWidth(1080, 2.625f))
        assertEquals(720 - 160, MiniPill.maxWidth(720, 2f))
        assertEquals(0, MiniPill.maxWidth(50, 2f))
    }

    @Test
    fun `the Home pill grows with its text but stops short of the edges`() {
        val d = 2.625f
        val max = MiniPill.maxWidth(1080, d)
        assertEquals(max, MiniPill.homeWidth(5000f, 1080, d, 170f))
        assertEquals((170 * d).roundToInt(), MiniPill.homeWidth(100f, 1080, d, 170f))
        assertEquals(600, MiniPill.homeWidth(600f, 1080, d, 170f))
        // Never wider than the old 85% either
        assertTrue(MiniPill.homeWidth(5000f, 1080, d, 170f) < 1080 * 0.85f)
        // A tiny screen: the minimum gives way to the margins
        assertEquals(MiniPill.maxWidth(400, 1f), MiniPill.homeWidth(5000f, 400, 1f, 400f))
    }

    @Test
    fun `the floating pill uses the same side margins in activity_main`() {
        val main = read("res/layout/activity_main.xml")
        val include = main.substringAfter("android:id=\"@+id/mini_player\"").substringBefore("/>")
        val m = MiniPill.SIDE_MARGIN_DP.toInt()
        assertTrue(include.contains("android:layout_marginStart=\"${m}dp\""))
        assertTrue(include.contains("android:layout_marginEnd=\"${m}dp\""))
        assertTrue(include.contains("android:layout_width=\"wrap_content\""))
        assertTrue(include.contains("android:layout_height=\"54dp\""))
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertTrue(activity.contains("MiniPill.homeWidth("))
        assertTrue(!activity.contains("widthPixels * 0.85f"))
    }

    @Test
    fun `a long title can't stretch the floating pill to the edges`() {
        val density = ctx.resources.displayMetrics.density
        val screen = FrameLayout(ctx)
        val pill = LayoutInflater.from(ctx).inflate(R.layout.view_mini_player, screen, false)
        val margin = (MiniPill.SIDE_MARGIN_DP * density).roundToInt()
        pill.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, (54 * density).roundToInt(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = margin
            marginEnd = margin
        }
        screen.addView(pill)
        pill.findViewById<TextView>(R.id.mini_title).text = longTitle
        pill.findViewById<TextView>(R.id.mini_artist).text = "Kaushik STORIES"
        screen.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        screen.layout(0, 0, 1080, 2340)

        assertEquals(margin, pill.left)
        assertEquals(1080 - margin, pill.right)
        assertEquals((54 * density).roundToInt(), pill.height)

        // A short title keeps the pill short, centred
        pill.findViewById<TextView>(R.id.mini_title).text = "Tum Hi Ho"
        pill.findViewById<TextView>(R.id.mini_artist).text = "Arijit Singh"
        screen.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        screen.layout(0, 0, 1080, 2340)
        assertTrue(pill.left > margin)
        assertTrue(kotlin.math.abs(pill.left - (1080 - pill.right)) <= 1)
    }

    @Test
    fun `the scrolling title stays in its own space, clear of the heart`() {
        val density = ctx.resources.displayMetrics.density
        val pill = LayoutInflater.from(ctx).inflate(R.layout.view_mini_player, FrameLayout(ctx), false)
        val title = pill.findViewById<TextView>(R.id.mini_title)
        val like = pill.findViewById<View>(R.id.mini_like)
        title.text = longTitle
        pill.measure(View.MeasureSpec.makeMeasureSpec(MiniPill.maxWidth(1080, density), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((54 * density).roundToInt(), View.MeasureSpec.EXACTLY))
        pill.layout(0, 0, pill.measuredWidth, pill.measuredHeight)

        val textBox = title.parent as View
        val gap = like.left - textBox.right
        assertTrue("gap to the heart was $gap px", gap >= (10 * density).roundToInt() - 1)
        assertTrue(textBox.right <= like.left)
        // Scrolls inside its own width and fades out at both ends
        assertEquals(android.text.TextUtils.TruncateAt.MARQUEE, title.ellipsize)
        assertTrue(title.isHorizontalFadingEdgeEnabled)
        assertTrue(title.horizontalFadingEdgeLength >= (16 * density).roundToInt() - 1)
        assertEquals(textBox.width, title.width)
    }

    private fun read(path: String): String {
        val candidates = listOf(File("src/main/$path"), File("app/src/main/$path"))
        return candidates.first { it.exists() }.readText()
    }
}
