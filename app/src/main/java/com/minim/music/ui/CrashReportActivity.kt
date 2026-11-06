package com.minim.music.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.minim.music.R
import com.minim.music.util.CrashReporter

/**
 * 5.7.0 u50: the "Minim crashed last time" screen.
 *
 * Hotfix 4.9.10's card was drawn by MainActivity after the whole app had been built, so a crash
 * that happened while the app was being built (u48/u49's launch crash) killed the app before the
 * card could ever show, and the report sat unseen on the phone. Now MainActivity checks for a
 * saved report as the very first thing in onCreate and opens this screen instead, before any of
 * its own layouts or setup run.
 *
 * This screen uses none of the app's layouts, themes or data: it is built in code from plain
 * Android views, so whatever broke the app can't break it. Copy puts the report on the clipboard,
 * Share opens Android's share sheet (only when tapped), and Continue (or Back) opens Minim as
 * usual. Nothing is ever sent anywhere by itself. The report is forgotten as soon as it's shown,
 * so it shows once; if building this screen fails, Minim just opens.
 */
class CrashReportActivity : Activity() {

    private var report: String = ""
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = savedInstanceState?.getString(STATE_REPORT) ?: intent?.getStringExtra(EXTRA_REPORT)
        if (text.isNullOrBlank()) {
            continueToMinim()
            return
        }
        report = text
        try {
            setContentView(build(text))
        } catch (_: Throwable) {
            continueToMinim()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putString(STATE_REPORT, report)
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Back opens Minim, like Continue")
    override fun onBackPressed() {
        continueToMinim()
    }

    private fun build(text: String): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val semi = font(this, R.font.poppins_semibold)
        val regular = font(this, R.font.poppins_regular)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(40), dp(24), dp(24))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            id = R.id.crash_report_title
            setText(R.string.crash_card_title)
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            semi?.let { typeface = it }
        })
        val headline = CrashReporter.headline(text)
        if (headline.isNotEmpty()) {
            root.addView(TextView(this).apply {
                this.text = headline
                setTextColor(FG)
                alpha = 0.7f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                regular?.let { typeface = it }
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
        }
        root.addView(TextView(this).apply {
            setText(R.string.crash_report_body)
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            regular?.let { typeface = it }
            setPadding(0, dp(12), 0, dp(16))
        })

        // The report itself, small and scrollable both ways (stack lines are long)
        val box = ScrollView(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(BOX)
            }
            isVerticalScrollBarEnabled = true
        }
        val sideways = HorizontalScrollView(this)
        sideways.addView(TextView(this).apply {
            id = R.id.crash_report_text
            this.text = text
            setTextColor(FG)
            alpha = 0.85f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
        box.addView(sideways)
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        fun pill(viewId: Int, label: Int, filled: Boolean, onTap: () -> Unit) = TextView(this).apply {
            id = viewId
            setText(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            semi?.let { typeface = it }
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(50).toFloat()
                if (filled) setColor(FG) else setStroke(dp(1), FG)
            }
            setTextColor(if (filled) BG else FG)
            setOnClickListener {
                try {
                    onTap()
                } catch (_: Throwable) {
                }
            }
        }
        row.addView(pill(R.id.crash_report_copy, R.string.crash_card_copy, filled = false) { copy() })
        row.addView(pill(R.id.crash_report_share, R.string.crash_report_share, filled = false) { share() })
        row.addView(pill(R.id.crash_report_continue, R.string.crash_report_continue, filled = true) { continueToMinim() })
        for (i in 1 until row.childCount) {
            (row.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
        }
        root.addView(row)

        try {
            @Suppress("DEPRECATION")
            window.statusBarColor = BG
            @Suppress("DEPRECATION")
            window.navigationBarColor = BG
        } catch (_: Throwable) {
        }
        return root
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Minim crash report", report))
        Toast.makeText(this, R.string.crash_card_copied, Toast.LENGTH_SHORT).show()
    }

    /** Android's share sheet with the report as plain text; only ever opened by a tap. */
    private fun share() {
        startActivity(Intent.createChooser(shareIntent(report), getString(R.string.crash_report_share)))
    }

    /** Opens Minim as it was asked to open (a Share to Minim keeps its link), then closes. */
    private fun continueToMinim() {
        if (leaving) return
        leaving = true
        try {
            @Suppress("DEPRECATION")
            val next = intent?.getParcelableExtra<Intent>(EXTRA_NEXT)
            val open = (next ?: Intent()).setClass(this, MainActivity::class.java)
                .putExtra(EXTRA_SHOWN, true)
            startActivity(open)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_SHOWN, true))
            } catch (_: Throwable) {
            }
        }
        finish()
        overridePendingTransitionCompat()
    }

    private fun overridePendingTransitionCompat() {
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val EXTRA_REPORT = "com.minim.music.crash.REPORT"
        const val EXTRA_NEXT = "com.minim.music.crash.NEXT"
        /** On the MainActivity this screen opens: the report was just shown, build the app. */
        const val EXTRA_SHOWN = "com.minim.music.crash.SHOWN"
        private const val STATE_REPORT = "report"

        private val BG = Color.rgb(0x12, 0x12, 0x12)
        private val BOX = Color.rgb(0x22, 0x22, 0x22)
        private val FG = Color.rgb(0xF2, 0xF2, 0xF2)

        /**
         * Called first thing in MainActivity.onCreate. When the last run saved a crash report,
         * forgets it (so it shows once), opens this screen with it, finishes [activity] and
         * returns true: the caller must then build nothing. Otherwise returns false. Never throws.
         */
        fun openIfPending(activity: Activity, savedInstanceState: Bundle?): Boolean {
            return try {
                if (savedInstanceState != null) return false
                if (activity.intent?.getBooleanExtra(EXTRA_SHOWN, false) == true) return false
                val dir = activity.filesDir
                val report = CrashReporter.pending(dir) ?: return false
                // Forgotten before opening: if the screen itself failed, it won't come back every launch
                CrashReporter.clear(dir)
                val open = Intent(activity, CrashReportActivity::class.java)
                    .putExtra(EXTRA_REPORT, report)
                activity.intent?.let { original ->
                    // Keep what the launch was for (a Share to Minim link), minus any flags
                    open.putExtra(EXTRA_NEXT, Intent(original).setComponent(null).setFlags(0))
                }
                activity.startActivity(open)
                activity.finish()
                try {
                    @Suppress("DEPRECATION")
                    activity.overridePendingTransition(0, 0)
                } catch (_: Throwable) {
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** The Share intent: the report as plain text with a subject line. Nothing goes out until the user picks an app. */
        fun shareIntent(report: String): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Minim crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }

        private fun font(context: Context, id: Int): Typeface? = try {
            ResourcesCompat.getFont(context, id)
        } catch (_: Throwable) {
            null
        }
    }
}
