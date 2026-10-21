package com.russianranger.fta

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val app get() = application as FtaApplication
    private val ink = Color.rgb(238, 229, 213)
    private val muted = Color.rgb(177, 168, 151)
    private val gold = Color.rgb(214, 170, 101)
    private lateinit var sourceButton: Button
    private lateinit var databaseButton: Button
    private lateinit var sourceStatus: TextView
    private lateinit var databaseStatus: TextView
    private lateinit var progressLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logText: TextView
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(21, 20, 18))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(20) + insets.systemWindowInsetLeft,
                    dp(12) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight,
                    dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(label("FROM THE ASHES", 24, gold, true), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(label("FOUNDATION 0.1", 12, muted))
        root.addView(title)
        root.addView(label("Your realm begins here", 15, ink).apply { setPadding(0, dp(4), 0, dp(12)) })

        val wide = resources.configuration.screenWidthDp >= 760
        val body = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val checklist = panel()
        checklist.addView(label("Prepare your server", 21, ink, true))
        countText = label("", 13, gold).apply { setPadding(0, dp(6), 0, dp(10)) }
        checklist.addView(countText)
        sourceStatus = label("", 16, ink, true)
        checklist.addView(sourceStatus)
        checklist.addView(label("Select From_The_Ashes_Source.zip (193 MiB).", 13, muted))
        sourceButton = action("Select source ZIP") { selectArchive(101) }
        checklist.addView(sourceButton)
        databaseStatus = label("", 16, ink, true).apply { setPadding(0, dp(12), 0, 0) }
        checklist.addView(databaseStatus)
        checklist.addView(label("Select Database.zip (99 MiB). Kept private; fresh-install seed only.", 13, muted))
        databaseButton = action("Select database ZIP") { selectArchive(102) }
        checklist.addView(databaseButton)
        checklist.addView(label("○  Native database provider", 16, ink, true).apply { setPadding(0, dp(16), 0, 0) })
        checklist.addView(label("Pending Android MariaDB compatibility qualification.", 13, muted))
        checklist.addView(label("○  ARM64 authserver and worldserver", 16, ink, true).apply { setPadding(0, dp(14), 0, 0) })
        checklist.addView(label("Pending the C++20 build and all required modules.", 13, muted))
        checklist.addView(label("○  Matching 3.3.5 game data", 16, ink, true).apply { setPadding(0, dp(14), 0, 0) })
        checklist.addView(label("DBC, maps, vmaps and mmaps will come from your full client later.", 13, muted))

        val status = panel()
        status.addView(label("Runtime", 21, ink, true))
        status.addView(label("Awaiting qualification", 15, gold, true).apply { setPadding(0, dp(8), 0, dp(4)) })
        status.addView(label("This build prepares verified inputs. Server start becomes available after the database and ARM64 server gates pass.", 14, muted))
        status.addView(action("Start server") {}.apply { isEnabled = false })
        status.addView(label("WarcraftXL client import comes in a later milestone. Your original folders stay unchanged.", 13, muted).apply { setPadding(0, dp(8), 0, dp(16)) })
        progressLabel = label("", 14, ink)
        status.addView(progressLabel)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            contentDescription = "Archive copy progress"
        }
        status.addView(progress, LinearLayout.LayoutParams(-1, dp(14)))
        status.addView(label("Setup log", 17, ink, true).apply { setPadding(0, dp(18), 0, dp(8)) })
        logText = label("", 12, muted).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        status.addView(logText)

        if (wide) {
            body.addView(scroll(checklist), LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginEnd = dp(14) })
            body.addView(scroll(status), LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(checklist, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            body.addView(status, LinearLayout.LayoutParams(-1, -2))
            root.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        root.requestApplyInsets()
        update()
    }

    override fun onStart() {
        super.onStart()
        app.observer = { update() }
        update()
    }

    override fun onStop() {
        app.observer = null
        super.onStop()
    }

    private fun update() {
        val source = app.store?.receipt(Inputs.source) != null
        val database = app.store?.receipt(Inputs.database) != null
        sourceStatus.text = "${if (source) "✓" else "○"}  Server source ${if (source) "verified" else "ZIP"}"
        databaseStatus.text = "${if (database) "✓" else "○"}  Database seed ${if (database) "verified" else "ZIP"}"
        countText.text = "${listOf(source, database).count { it }} of 5 preparation steps complete"
        sourceButton.isEnabled = !app.busy && app.store != null
        databaseButton.isEnabled = !app.busy && app.store != null
        sourceButton.text = if (source) "Replace source ZIP" else "Select source ZIP"
        databaseButton.text = if (database) "Replace database ZIP" else "Select database ZIP"
        progressLabel.text = app.progressText
        progress.isIndeterminate = app.busy && app.store == null
        progress.progress = app.progressPercent
        progress.visibility = if (app.busy) View.VISIBLE else View.GONE
        logText.text = app.logs()
        if (app.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun selectArchive(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, requestCode)
    }

    @Deprecated("Platform activity result API keeps this foundation free of AndroidX dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val spec = when (requestCode) { 101 -> Inputs.source; 102 -> Inputs.database; else -> return }
        data?.data?.let { app.importArchive(spec, it) }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(Color.rgb(35, 32, 27))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(82, 66, 44))
        }
    }
    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
