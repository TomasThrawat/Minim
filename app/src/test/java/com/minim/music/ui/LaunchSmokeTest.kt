package com.minim.music.ui

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.playback.PlaybackService
import com.minim.music.util.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * The real Minim application, with the playback service connection answered by a stub (Robolectric
 * can't run the media session). Registered before MinimApp.onCreate, which is where it connects.
 */
class LaunchTestApp : MinimApp() {
    override fun onCreate() {
        val svc = ComponentName(this, PlaybackService::class.java)
        val bind = Intent("androidx.media3.session.MediaSessionService").setClassName(packageName, svc.className)
        Shadows.shadowOf(this).setComponentNameAndServiceForBindServiceForIntent(bind, svc, android.os.Binder())
        super.onCreate()
    }
}

/**
 * 5.7.0 u50: launch smoke test. u48 and u49 crashed on every launch (the Artist page heart was
 * wired before its view was looked up) and no test ever started MainActivity, so nothing caught
 * it. This starts the real MainActivity with the real application, opens every tab and every
 * screen that got edge fades in u49, scrolls and draws them, and checks the crash report screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = LaunchTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LaunchSmokeTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()

    @Before
    fun noOldReport() {
        CrashReporter.clear(app.filesDir)
    }

    private fun idle(seconds: Long = 1) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    /** Measures, lays out and draws the whole window, like a frame on a 1080x2400-ish phone. */
    private fun frame(a: android.app.Activity) {
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1233, 2673)
        root.draw(Canvas(Bitmap.createBitmap(1233, 2673, Bitmap.Config.ARGB_8888)))
        idle()
    }

    private fun call(a: MainActivity, name: String, vararg args: Any?) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(a, *args)
        idle()
    }

    private fun tab(name: String): Any {
        val cls = Class.forName("com.minim.music.ui.MainActivity\$Tab")
        return cls.enumConstants!!.first { (it as Enum<*>).name == name }
    }

    private fun visibleLists(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        if (!v.isShown) return out
        if (v is RecyclerView || v is androidx.core.widget.NestedScrollView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) visibleLists(v.getChildAt(i), out)
        return out
    }

    /** Draws the screen at rest, scrolled down, and back at the top. */
    private fun scrollAndDraw(a: MainActivity) {
        frame(a)
        for (list in visibleLists(a.window.decorView)) {
            list.scrollBy(0, 600)
            frame(a)
            list.scrollBy(0, -600)
        }
        frame(a)
    }

    private fun launch(intent: Intent? = null): MainActivity {
        val c = if (intent != null) Robolectric.buildActivity(MainActivity::class.java, intent) else Robolectric.buildActivity(MainActivity::class.java)
        c.setup()
        idle(3)
        return c.get()
    }

    @Test
    fun launchesAndEveryTabOpens() {
        val a = launch()
        assertFalse(a.isFinishing)
        assertNotNull(a.findViewById<View>(R.id.artist_like))
        frame(a)
        for (t in listOf("STORIES", "STATS", "LIBRARY", "HOME")) {
            call(a, "showTab", tab(t), false)
            scrollAndDraw(a)
        }
        assertNull(Shadows.shadowOf(a).nextStartedActivity)
    }

    @Test
    fun everyFadingListScreenOpens() {
        val a = launch()
        call(a, "showTab", tab("LIBRARY"), false)
        // Liked, Recents and Offline (the ids MainActivity uses for them)
        for ((id, name) in listOf(-1L to "Liked", -2L to "Recents", -3L to "Offline")) {
            call(a, "openList", id, name, null, null)
            scrollAndDraw(a)
            a.onBackPressedDispatcher.onBackPressed()
            idle()
        }
        // A Discover grid
        call(a, "openShelfPage", com.minim.music.data.discover.Shelf("smoke", "Smoke test", emptyList()), false)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        // Search, Up next and Settings
        call(a, "openSearch", null)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        call(a, "openQueue", null)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        // The Artist page (its heart is what crashed u48/u49) opens and draws, even empty
        call(a, "showArtistScreen", false)
        frame(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        call(a, "openSettings", null)
        scrollAndDraw(a)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        frame(a)
    }

    @Test
    fun savedCrashOpensTheReportScreenFirstThenMinim() {
        CrashReporter.save(
            app.filesDir,
            "Minim crash report\nVersion: 5.7.0 (49)\nThread: main\n\n" +
                "kotlin.UninitializedPropertyAccessException: lateinit property artistLike has not been initialized\n" +
                "\tat com.minim.music.ui.MainActivity.bindViews(MainActivity.kt:533)\n"
        )
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val main = c.get()
        // Nothing of the app was built: it went straight to the report screen
        assertTrue(main.isFinishing)
        val open = Shadows.shadowOf(main).nextStartedActivity
        assertEquals(CrashReportActivity::class.java.name, open.component?.className)
        val report = open.getStringExtra(CrashReportActivity.EXTRA_REPORT)!!
        assertTrue(report.contains("artistLike"))
        // Shown once: the saved report is gone
        assertNull(CrashReporter.pending(app.filesDir))

        val screen = Robolectric.buildActivity(CrashReportActivity::class.java, open).setup().get()
        idle()
        frame(screen)
        val title = screen.findViewById<TextView>(R.id.crash_report_title)
        assertEquals("Minim crashed last time", title.text.toString())
        assertTrue(screen.findViewById<TextView>(R.id.crash_report_text).text.contains("bindViews"))

        // Copy puts the whole report on the clipboard; nothing is started or sent
        screen.findViewById<View>(R.id.crash_report_copy).performClick()
        val cm = screen.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(report, cm.primaryClip!!.getItemAt(0).text.toString())
        assertNull(Shadows.shadowOf(screen).nextStartedActivity)

        // Share opens Android's share sheet with the report as text
        screen.findViewById<View>(R.id.crash_report_share).performClick()
        val chooser = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(report, send.getStringExtra(Intent.EXTRA_TEXT))

        // Continue opens Minim, which now builds normally
        screen.findViewById<View>(R.id.crash_report_continue).performClick()
        assertTrue(screen.isFinishing)
        val next = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        val again = launch(next)
        assertFalse(again.isFinishing)
        frame(again)
        assertNotNull(again.findViewById<View>(R.id.artist_like))
    }

    @Test
    fun backOnTheReportScreenAlsoOpensMinim() {
        val open = Intent(app, CrashReportActivity::class.java).putExtra(CrashReportActivity.EXTRA_REPORT, "Minim crash report\nThread: main\n\njava.lang.IllegalStateException: boom\n")
        val screen = Robolectric.buildActivity(CrashReportActivity::class.java, open).setup().get()
        idle()
        @Suppress("DEPRECATION")
        screen.onBackPressed()
        assertTrue(screen.isFinishing)
        assertEquals(MainActivity::class.java.name, Shadows.shadowOf(screen).nextStartedActivity.component?.className)
    }

    /**
     * Upgrade paths into u50. u47 had no liked-artists list at all (it's new in u48), u48/u49 have
     * one, and a half-written one must not matter either. The library database is version 5 in
     * u47, u48, u49 and u50 alike, so it needs no migration. Each case launches and opens Liked.
     */
    @Test
    fun launchesOverUpdate47_48And49Data() {
        val prefs = app.getSharedPreferences("minim_liked_artists", Context.MODE_PRIVATE)
        val cases = listOf(
            null, // straight from u47: nothing saved yet
            "[{\"k\":\"url:https://music.youtube.com/channel/UCsmoke\",\"u\":\"https://music.youtube.com/channel/UCsmoke\"," +
                "\"n\":\"Smoke Artist\",\"t\":\"\",\"c\":625000000,\"ck\":\"MONTHLY_LISTENERS\",\"at\":1730900000000}]", // u48/u49
            "[{\"broken" // a cut-off save
        )
        for (saved in cases) {
            prefs.edit().apply { if (saved == null) remove("artists_v1") else putString("artists_v1", saved) }.commit()
            val a = launch()
            assertFalse(a.isFinishing)
            call(a, "showTab", tab("LIBRARY"), false)
            call(a, "openList", -1L, "Liked", null, com.minim.music.data.stories.LibraryKind.ARTISTS)
            scrollAndDraw(a)
            a.finish()
            idle()
        }
    }

    @Test
    fun noSavedCrashMeansNoReportScreen() {
        val a = launch()
        assertFalse(a.isFinishing)
        assertNull(Shadows.shadowOf(a).nextStartedActivity)
    }

    @Test
    fun aShareToMinimLinkSurvivesTheReportScreen() {
        CrashReporter.save(app.filesDir, "Minim crash report\nThread: main\n\njava.lang.IllegalStateException: boom\n")
        val shared = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/playlist?list=PLsmoke"))
        val c = Robolectric.buildActivity(MainActivity::class.java, shared).setup()
        idle()
        val open = Shadows.shadowOf(c.get()).nextStartedActivity
        val screen = Robolectric.buildActivity(CrashReportActivity::class.java, open).setup().get()
        idle()
        screen.findViewById<View>(R.id.crash_report_continue).performClick()
        val next = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertEquals(Intent.ACTION_VIEW, next.action)
        assertEquals("https://music.youtube.com/playlist?list=PLsmoke", next.dataString)
        assertTrue(next.getBooleanExtra(CrashReportActivity.EXTRA_SHOWN, false))
        val built = launch(next)
        assertFalse(built.isFinishing)
        assertEquals("https://music.youtube.com/playlist?list=PLsmoke", built.intent.dataString)
    }
}
