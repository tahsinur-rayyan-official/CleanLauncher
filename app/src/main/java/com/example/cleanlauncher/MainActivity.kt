package com.example.cleanlauncher

import android.app.Activity
import android.app.ActivityOptions
import android.app.Dialog
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    data class App(val label: String, val pkg: String, val icon: Drawable, val intent: Intent)

    private var all = listOf<App>()
    private var shown = listOf<App>()
    private var hiddenMode = false
    private var menu: Dialog? = null

    private lateinit var root: LinearLayout
    private lateinit var clockBox: LinearLayout
    private lateinit var hiddenTitle: TextView
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var dock: LinearLayout

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val reqUnlock = 42

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun glass(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun roundIcon(iv: ImageView) {
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.width * 0.225f)
            }
        }
        iv.clipToOutline = true
        iv.scaleType = ImageView.ScaleType.FIT_XY
    }

    // iOS-style press: icon halka choto hoy
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    // ---------- hidden apps storage ----------
    private fun hiddenSet(): MutableSet<String> =
        HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())

    private fun saveHidden(s: Set<String>) {
        prefs.edit().putStringSet("hidden", HashSet(s)).apply()
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // full screen: status bar ar nav bar er pichhone porjonto
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.setOnApplyWindowInsetsListener { v, i ->
            @Suppress("DEPRECATION")
            v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom)
            i
        }

        // Clock widget (long-press = hidden apps)
        clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnLongClickListener { openHidden(); true }
        }
        clockBox.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 58f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(10f, 0f, 2f, 0x66000000)
            gravity = Gravity.CENTER
        })
        clockBox.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 15f
            setShadowLayer(6f, 0f, 1f, 0x66000000)
            gravity = Gravity.CENTER
        })
        root.addView(clockBox, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })

        hiddenTitle = TextView(this).apply {
            text = "Hidden Apps"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { setHiddenMode(false) }
        }
        root.addView(hiddenTitle, LinearLayout.LayoutParams(-1, dp(80)))

        search = EditText(this).apply {
            hint = "Search"
            setHintTextColor(0xB3FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            background = glass(0x33FFFFFF, 20)
            setPadding(dp(18), 0, dp(18), 0)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter(); updateDim() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnFocusChangeListener { _, _ -> updateDim() }
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { launch(it, null) }
                true
            }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(40)).apply {
            setMargins(dp(20), dp(16), dp(20), dp(4))
        })

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            clipToPadding = false
            adapter = Adapter()
            setOnItemClickListener { v, _, p, _ -> launch(shown[p], v) }
            setOnItemLongClickListener { _, _, p, _ -> showMenu(shown[p]); true }
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: android.widget.AbsListView?, s: Int) {
                    if (s == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) dismissSearch()
                }
                override fun onScroll(v: android.widget.AbsListView?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glass(0x40FFFFFF, 30)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(86)).apply {
            setMargins(dp(14), dp(6), dp(14), dp(12))
        })

        setContentView(root)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onStop() {
        super.onStop()
        if (hiddenMode) setHiddenMode(false) // bairey gele hidden mode bondho
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        menu?.dismiss()
        if (hiddenMode) setHiddenMode(false)
        search.setText("")
        dismissSearch()
        grid.setSelection(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            hiddenMode -> setHiddenMode(false)
            search.text.isNotEmpty() || search.hasFocus() -> { search.setText(""); dismissSearch() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqUnlock && resultCode == RESULT_OK) setHiddenMode(true)
    }

    // ---------- helpers ----------
    private fun dismissSearch() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        root.requestFocus()
    }

    private fun updateDim() {
        root.setBackgroundColor(
            when {
                hiddenMode -> 0x99000000.toInt()
                search.hasFocus() || search.text.isNotEmpty() -> 0x66000000
                else -> Color.TRANSPARENT
            }
        )
    }

    private fun openHidden() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isDeviceSecure) {
            val i = km.createConfirmDeviceCredentialIntent("Hidden Apps", "Unlock to see hidden apps")
            if (i != null) { startActivityForResult(i, reqUnlock); return }
        }
        setHiddenMode(true)
    }

    private fun setHiddenMode(on: Boolean) {
        hiddenMode = on
        clockBox.visibility = if (on) View.GONE else View.VISIBLE
        hiddenTitle.visibility = if (on) View.VISIBLE else View.GONE
        dock.visibility = if (on) View.GONE else View.VISIBLE
        search.setText("")
        dismissSearch()
        refresh()
        updateDim()
    }

    private fun loadApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        all = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val pkg = it.activityInfo.packageName
                App(
                    it.loadLabel(pm).toString(), pkg, it.loadIcon(pm),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(ComponentName(pkg, it.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .sortedBy { it.label.lowercase() }
        refresh()
    }

    private fun applyFilter() {
        val h = hiddenSet()
        val pool = if (hiddenMode) all.filter { it.pkg in h } else all.filter { it.pkg !in h }
        val q = search.text.toString().trim()
        shown = if (q.isEmpty()) pool else pool.filter { it.label.contains(q, true) }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun refresh() {
        applyFilter()
        renderDock()
    }

    private fun launch(app: App, from: View?) {
        try {
            val opts = from?.let {
                ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
            }
            startActivity(app.intent, opts)
        } catch (_: Exception) {
        }
    }

    // ---------- long-press menu ----------
    private fun showMenu(app: App) {
        menu?.dismiss()
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0xF21C1C1E.toInt(), 22)
            setPadding(0, dp(8), 0, dp(8))
        }

        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        head.addView(ImageView(this).apply {
            setImageDrawable(app.icon); roundIcon(this)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        panel.addView(head)

        fun row(label: String, red: Boolean = false, action: () -> Unit) {
            panel.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) },
                LinearLayout.LayoutParams(-1, 1))
            panel.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(if (red) 0xFFFF453A.toInt() else Color.WHITE)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener {
                    d.dismiss()
                    try { action() } catch (_: Exception) {}
                }
            })
        }

        if (!hiddenMode) {
            val inDock = dockPkgs().contains(app.pkg)
            row(if (inDock) "Remove from Dock" else "Add to Dock") { toggleDock(app.pkg) }
        }
        row(if (hiddenMode) "Unhide App" else "Hide App") { toggleHidden(app.pkg) }
        row("App Info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        row("Uninstall", true) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        }

        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
        menu = d
    }

    private fun toggleHidden(pkg: String) {
        val s = hiddenSet()
        val nowHidden = if (s.remove(pkg)) false else { s.add(pkg); true }
        saveHidden(s)
        if (nowHidden) {
            val d = dockPkgs()
            if (d.remove(pkg)) prefs.edit().putString("dock", d.joinToString(",")).apply()
        }
        refresh()
        Toast.makeText(
            this,
            if (nowHidden) "Hidden. Clock e long-press korle dekhabe" else "Unhidden",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------- Dock ----------
    private fun defaultDock(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val pkgs = all.map { it.pkg }.toSet()
        return intents.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.filter { it in pkgs }.distinct()
    }

    private fun dockPkgs(): MutableList<String> {
        val saved = prefs.getString("dock", null)
        return if (saved == null) defaultDock().toMutableList()
        else saved.split(",").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun toggleDock(pkg: String) {
        val list = dockPkgs()
        if (!list.remove(pkg)) {
            if (list.size >= 4) {
                Toast.makeText(this, "Dock full (4)", Toast.LENGTH_SHORT).show(); return
            }
            list.add(pkg)
        }
        prefs.edit().putString("dock", list.joinToString(",")).apply()
        renderDock()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val h = hiddenSet()
        val byPkg = all.associateBy { it.pkg }
        for (pkg in dockPkgs()) {
            if (pkg in h) continue
            val app = byPkg[pkg] ?: continue
            val cell = FrameLayout(this)
            val iv = ImageView(this).apply { setImageDrawable(app.icon); roundIcon(this) }
            cell.addView(iv, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
            pressable(cell)
            cell.setOnClickListener { launch(app, cell) }
            cell.setOnLongClickListener { showMenu(app); true }
            dock.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    // ---------- Grid ----------
    inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context).also { roundIcon(it) }, LinearLayout.LayoutParams(dp(60), dp(60)))
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                pressable(this)
            }
            val app = shown[p]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }
}
