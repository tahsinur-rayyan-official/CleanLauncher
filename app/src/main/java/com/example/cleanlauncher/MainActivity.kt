package com.example.cleanlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Dialog
import android.app.KeyguardManager
import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    data class App(val label: String, val pkg: String, val icon: Drawable, val intent: Intent)

    // ---------- fonts (system Roboto weights; Bangla falls back to Noto Sans Bengali) ----------
    private val THIN = "sans-serif-thin"
    private val LIGHT = "sans-serif-light"
    private val REG = "sans-serif"
    private val MED = "sans-serif-medium"

    // ---------- state ----------
    private var all = listOf<App>()
    private var lpShown = listOf<App>()
    private var badges = mapOf<String, Int>()
    private val running = LinkedHashSet<String>()
    private var insetTop = 0
    private var insetBottom = 0
    private var panel: String? = null // "lp" | "sp" | "nc"
    private var menuDlg: Dialog? = null
    private var hiddenDlg: Dialog? = null
    private var hiddenAdapter: AppAdapter? = null
    private var menuPopup: PopupWindow? = null
    private var downX = 0f
    private var downY = 0f
    private val reqUnlock = 42
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var rootFrame: FrameLayout
    private lateinit var wall: WallpaperView
    private lateinit var home: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var launchpad: FrameLayout
    private lateinit var spotlight: FrameLayout
    private lateinit var notifCenter: FrameLayout
    private lateinit var ncPanel: LinearLayout
    private lateinit var ncList: LinearLayout
    private lateinit var volBar: SeekBar
    private lateinit var lpGrid: GridView
    private lateinit var lpSearch: EditText
    private lateinit var spInput: EditText
    private lateinit var spResults: LinearLayout
    private lateinit var dock: DockLayout
    private lateinit var battView: BatteryView
    private lateinit var battText: TextView
    private lateinit var calBox: LinearLayout
    private lateinit var sysBox: LinearLayout
    private lateinit var recentBox: LinearLayout

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private val ticker = object : Runnable {
        override fun run() {
            refreshSys()
            handler.postDelayed(this, 30000)
        }
    }

    private val ncW: Int
        get() = minOf(dp(340), (resources.displayMetrics.widthPixels * 0.9f).toInt())

    // ---------- small helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    private fun glass(color: Int, radiusDp: Int, stroke: Int = 0x22FFFFFF) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        setStroke(dp(1), stroke)
    }

    private fun tv(
        text: String, size: Float, color: Int = Color.WHITE, font: String = REG,
        grav: Int = Gravity.START or Gravity.CENTER_VERTICAL
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(font, Typeface.NORMAL)
        gravity = grav
    }

    private fun selectable(v: View) {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.background = ta.getDrawable(0)
        ta.recycle()
    }

    private fun divider() = View(this).apply { setBackgroundColor(0x22FFFFFF) }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            false
        }
    }

    private fun bounce(v: View) {
        v.animate().cancel()
        v.animate().translationY(-dpf(16)).setDuration(170)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                v.animate().translationY(0f).setDuration(420)
                    .setInterpolator(BounceInterpolator()).start()
            }.start()
    }

    private fun popIn(layer: View, from: Float) {
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 0f
        layer.scaleX = from
        layer.scaleY = from
        layer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun popOut(layer: View, to: Float) {
        layer.animate().cancel()
        layer.animate().alpha(0f).scaleX(to).scaleY(to).setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { layer.visibility = View.GONE }.start()
    }

    private fun hideKb() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(rootFrame.windowToken, 0)
        rootFrame.requestFocus()
    }

    // ---------- prefs ----------
    private fun hiddenSet(): MutableSet<String> =
        HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())

    private fun saveHidden(s: Set<String>) {
        prefs.edit().putStringSet("hidden", HashSet(s)).apply()
    }

    private fun recents(): MutableList<String> =
        (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableList()

    private fun iconDp() = when (prefs.getInt("size", 1)) {
        0 -> 54
        2 -> 66
        else -> 60
    }

    private fun visibleApps(): List<App> {
        val h = hiddenSet()
        return all.filter { it.pkg !in h }
    }

    private fun hiddenApps(): List<App> {
        val h = hiddenSet()
        return all.filter { it.pkg in h }
    }

    // ================= lifecycle =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // full screen: bars transparent, content goes behind them
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }

        rootFrame = FrameLayout(this).apply {
            clipChildren = false
            isFocusableInTouchMode = true
        }
        wall = WallpaperView(this)
        rootFrame.addView(wall, FrameLayout.LayoutParams(-1, -1))

        home = buildHome()
        launchpad = buildLaunchpad()
        spotlight = buildSpotlight()
        notifCenter = buildNotifCenter()
        for (v in listOf<View>(home, launchpad, spotlight, notifCenter)) {
            rootFrame.addView(v, FrameLayout.LayoutParams(-1, -1))
        }
        launchpad.visibility = View.GONE
        spotlight.visibility = View.GONE
        notifCenter.visibility = View.GONE

        rootFrame.setOnApplyWindowInsetsListener { _, i ->
            insetTop = i.systemWindowInsetTop
            insetBottom = i.systemWindowInsetBottom
            for (v in listOf<View>(home, launchpad, spotlight, notifCenter)) {
                v.setPadding(0, insetTop, 0, insetBottom)
            }
            i
        }
        setContentView(rootFrame)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        applyPrefs()
        NotifService.listener = { runOnUiThread { onNotifsChanged() } }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        refreshCalendar()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onStop() {
        super.onStop()
        hiddenDlg?.dismiss() // privacy: bairey gele hidden window bondho
    }

    override fun onDestroy() {
        super.onDestroy()
        NotifService.listener = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeAll()
        menuDlg?.dismiss()
        menuPopup?.dismiss()
        homeScroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel != null) closeAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqUnlock && resultCode == RESULT_OK) showHiddenWindow()
    }

    // gestures: swipe up from dock = Launchpad, swipe down at top = Spotlight,
    // swipe left from right edge = Notification Center
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val th = dp(70)
                val h = rootFrame.height
                val w = rootFrame.width
                if (panel == null && menuPopup?.isShowing != true) {
                    if (abs(dy) > abs(dx) * 1.6f) {
                        if (dy < -th && downY > h - dp(190)) openLaunchpad()
                        else if (dy > th && downY < insetTop + dp(90)) openSpotlight()
                    } else if (abs(dx) > abs(dy) * 1.6f && dx < -th && downX > w - dp(36)) {
                        openNC()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ================= panels =================
    private fun closeAll() {
        hideKb()
        when (panel) {
            "lp" -> popOut(launchpad, 1.12f)
            "sp" -> popOut(spotlight, 0.95f)
            "nc" -> slideOutNC()
        }
        panel = null
    }

    private fun openLaunchpad() {
        if (panel == "lp") return
        closeAll()
        panel = "lp"
        lpSearch.setText("")
        applyLpFilter()
        lpGrid.setSelection(0)
        popIn(launchpad, 1.12f)
    }

    private fun openSpotlight() {
        if (panel == "sp") return
        closeAll()
        panel = "sp"
        spInput.setText("")
        buildResults("")
        popIn(spotlight, 0.95f)
        spInput.requestFocus()
        handler.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(spInput, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun openNC() {
        if (panel == "nc") return
        closeAll()
        panel = "nc"
        rebuildNotifs()
        syncVolume()
        refreshSys()
        notifCenter.animate().cancel()
        notifCenter.visibility = View.VISIBLE
        notifCenter.alpha = 0f
        ncPanel.animate().cancel()
        ncPanel.translationX = (ncW + dp(10)).toFloat()
        notifCenter.animate().alpha(1f).setDuration(220).start()
        ncPanel.animate().translationX(0f).setDuration(340)
            .setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    private fun slideOutNC() {
        notifCenter.animate().cancel()
        notifCenter.animate().alpha(0f).setDuration(220)
            .withEndAction { notifCenter.visibility = View.GONE }.start()
        ncPanel.animate().cancel()
        ncPanel.animate().translationX((ncW + dp(10)).toFloat()).setDuration(240)
            .setInterpolator(AccelerateInterpolator()).start()
    }

    // ================= apps =================
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
        onNotifsChanged()
        applyLpFilter()
        renderDock()
        refreshRecents()
    }

    private fun applyLpFilter() {
        val q = if (::lpSearch.isInitialized) lpSearch.text.toString().trim() else ""
        val vis = visibleApps()
        lpShown = if (q.isEmpty()) vis else vis.filter { it.label.contains(q, true) }
        (lpGrid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun open(app: App, from: View?, bounceView: View? = null) {
        bounceView?.let { bounce(it) }
        running.add(app.pkg)
        val r = recents()
        r.remove(app.pkg)
        r.add(0, app.pkg)
        prefs.edit().putString("recent", r.take(6).joinToString(",")).apply()
        closeAll()
        handler.postDelayed({
            try {
                val o = from?.let {
                    ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
                }
                startActivity(app.intent, o)
            } catch (_: Exception) {
            }
        }, if (bounceView != null) 160L else 0L)
        handler.postDelayed({ renderDock(); refreshRecents() }, 600)
    }

    // ================= notifications =================
    private fun hasNotifAccess(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return s != null && s.contains(packageName)
    }

    private fun notifs(): List<StatusBarNotification> = try {
        NotifService.instance?.activeNotifications
            ?.filter { it.isClearable && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            ?.sortedByDescending { it.postTime } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun onNotifsChanged() {
        badges = notifs().groupingBy { it.packageName }.eachCount()
        (lpGrid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        hiddenAdapter?.notifyDataSetChanged()
        renderDock()
        if (panel == "nc") rebuildNotifs()
    }

    private fun rebuildNotifs() {
        ncList.removeAllViews()
        if (!hasNotifAccess()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = glass(0x33FFFFFF, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(tv("Notifications are off", 14f, Color.WHITE, MED))
            card.addView(tv("Allow notification access to see them here.", 12f, 0xAAFFFFFF.toInt()))
            card.addView(
                tv("Allow Access…", 14f, 0xFF0A84FF.toInt(), MED).apply {
                    setPadding(0, dp(10), 0, 0)
                    setOnClickListener { safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            )
            ncList.addView(card, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val list = notifs()
        if (list.isEmpty()) {
            ncList.addView(
                tv("No Notifications", 15f, 0x99FFFFFF.toInt(), MED, Gravity.CENTER)
                    .apply { setPadding(0, dp(30), 0, dp(30)) },
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }
        for (sbn in list) {
            ncList.addView(notifCard(sbn), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun notifCard(sbn: StatusBarNotification): View {
        val n = sbn.notification
        val pkg = sbn.packageName
        val app = all.firstOrNull { it.pkg == pkg }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val time = DateUtils.getRelativeTimeSpanString(
            sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0x33FFFFFF, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val ic: Drawable? = app?.icon ?: try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
        if (ic != null) {
            val iv = IconView(this, dp(20), 0)
            iv.bind(ic, 0, false)
            head.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        }
        head.addView(tv((app?.label ?: pkg).uppercase(), 11f, 0xAAFFFFFF.toInt(), MED), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv(time, 11f, 0x88FFFFFF.toInt()))
        head.addView(
            tv("×", 20f, 0xAAFFFFFF.toInt(), MED, Gravity.CENTER).apply {
                setPadding(dp(10), 0, 0, 0)
                setOnClickListener {
                    card.animate().translationX(ncW.toFloat()).alpha(0f).setDuration(220)
                        .withEndAction {
                            try { NotifService.instance?.cancelNotification(sbn.key) } catch (e: Exception) {}
                            ncList.removeView(card)
                        }.start()
                }
            }
        )
        card.addView(head)
        if (title.isNotEmpty()) card.addView(tv(title, 14f, Color.WHITE, MED).apply { setPadding(0, dp(4), 0, 0) })
        if (text.isNotEmpty()) card.addView(
            tv(text, 13f, 0xCCFFFFFF.toInt()).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        )
        pressable(card)
        card.setOnClickListener {
            try { n.contentIntent?.send() } catch (e: Exception) {}
            closeAll()
        }
        return card
    }

    // ================= HOME =================
    private fun trafficLights(onRed: () -> Unit, onYellow: () -> Unit, onGreen: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val items = listOf(
            0xFFFF5F57.toInt() to onRed,
            0xFFFEBC2E.toInt() to onYellow,
            0xFF28C840.toInt() to onGreen
        )
        for ((col, act) in items) {
            val wrap = FrameLayout(this)
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(1, 0x33000000)
                }
            }
            wrap.addView(dot, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
            wrap.setOnClickListener { act() }
            row.addView(wrap, LinearLayout.LayoutParams(dp(26), dp(30)))
        }
        return row
    }

    private fun buildMenuBar(): View {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(8), 0, dp(12), 0)
        }
        bar.addView(trafficLights({ closeAll() }, { openSpotlight() }, { openLaunchpad() }))
        bar.addView(
            tv("Launcher", 13f, Color.WHITE, MED).apply {
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { showAppMenu() }
                setOnLongClickListener { openHidden(); true }
            },
            LinearLayout.LayoutParams(-2, -1)
        )
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(
            ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_search)
                setColorFilter(0xFFFFFFFF.toInt())
                setOnClickListener { openSpotlight() }
            },
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(12) }
        )
        battText = tv("", 12f, Color.WHITE, MED)
        bar.addView(battText, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(4) })
        battView = BatteryView(this)
        bar.addView(battView, LinearLayout.LayoutParams(dp(26), dp(12)).apply { rightMargin = dp(12) })
        bar.addView(
            TextClock(this).apply {
                format12Hour = "EEE h:mm a"
                format24Hour = "EEE HH:mm"
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.create(MED, Typeface.NORMAL)
                setOnClickListener { if (panel == "nc") closeAll() else openNC() }
            }
        )
        return bar
    }

    private fun card(bg: Int = 0x8014141C.toInt()) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(bg, 20)
        setPadding(dp(18), dp(14), dp(18), dp(14))
    }

    private fun cardLp() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    private fun buildHome(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        col.addView(buildMenuBar(), LinearLayout.LayoutParams(-1, dp(32)))

        homeScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val widgets = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // clock
        val clock = card()
        clock.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 56f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(THIN, Typeface.NORMAL)
            letterSpacing = -0.02f
        })
        clock.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 15f
            setTextColor(0xFFFF453A.toInt())
            typeface = Typeface.create(MED, Typeface.NORMAL)
        })
        widgets.addView(clock, cardLp())

        // calendar
        calBox = card()
        widgets.addView(calBox, cardLp())

        // recents
        recentBox = card()
        widgets.addView(recentBox, cardLp())

        // system
        sysBox = card()
        widgets.addView(sysBox, cardLp())

        // notes
        val notes = card(0xF2FFE27A.toInt())
        notes.addView(tv("Notes", 13f, 0xFF8A6D00.toInt(), MED))
        notes.addView(EditText(this).apply {
            hint = "Take a quick note…"
            setHintTextColor(0x883A3000.toInt())
            setTextColor(0xFF3A3000.toInt())
            textSize = 15f
            background = null
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, 0)
            setText(prefs.getString("note", ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("note", s?.toString() ?: "").apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        widgets.addView(notes, cardLp())

        homeScroll.addView(widgets, FrameLayout.LayoutParams(-1, -2))
        col.addView(homeScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        dock = DockLayout(this).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            background = glass(0x44FFFFFF, 22, 0x44FFFFFF)
            setPadding(dp(8), 0, dp(8), 0)
        }
        col.addView(dock, LinearLayout.LayoutParams(-1, dp(70)).apply { setMargins(dp(14), dp(4), dp(14), dp(10)) })
        return col
    }

    private fun refreshCalendar() {
        calBox.removeAllViews()
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val monthName = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()) ?: ""
        calBox.addView(tv(monthName.uppercase() + "  " + cal.get(Calendar.YEAR), 13f, 0xFFFF453A.toInt(), MED))
        val first = cal.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        val fdw = first.firstDayOfWeek
        val offset = (first.get(Calendar.DAY_OF_WEEK) - fdw + 7) % 7
        val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
        val head = LinearLayout(this)
        for (k in 0 until 7) {
            val dow = (fdw - 1 + k) % 7 + 1
            head.addView(
                tv(names[dow].take(1).uppercase(), 11f, 0x88FFFFFF.toInt(), MED, Gravity.CENTER),
                LinearLayout.LayoutParams(0, dp(24), 1f)
            )
        }
        calBox.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val rows = (offset + dim + 6) / 7
        for (r in 0 until rows) {
            val row = LinearLayout(this)
            for (k in 0 until 7) {
                val d = r * 7 + k - offset + 1
                val cell = FrameLayout(this)
                if (d in 1..dim) {
                    val t = tv(d.toString(), 13f, Color.WHITE, if (d == today) MED else REG, Gravity.CENTER)
                    if (d == today) {
                        t.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0xFFFF453A.toInt())
                        }
                    }
                    cell.addView(t, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(28), 1f))
            }
            calBox.addView(row)
        }
    }

    private fun refreshRecents() {
        recentBox.removeAllViews()
        recentBox.addView(tv("RECENTS", 11f, 0x99FFFFFF.toInt(), MED))
        val vis = visibleApps()
        val list = recents().mapNotNull { p -> vis.firstOrNull { it.pkg == p } }.take(5)
        if (list.isEmpty()) {
            recentBox.addView(tv("Apps you open will show up here", 13f, 0x88FFFFFF.toInt()).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val row = LinearLayout(this)
        for (a in list) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val iv = IconView(this, dp(46), 0)
            iv.bind(a.icon, badges[a.pkg] ?: 0, false)
            c.addView(iv, LinearLayout.LayoutParams(-1, dp(46)))
            c.addView(
                tv(a.label, 10f, Color.WHITE, REG, Gravity.CENTER).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            )
            pressable(c)
            c.setOnClickListener { open(a, it) }
            row.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        for (k in list.size until 5) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        recentBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1073741824.0)

    private fun barRow(label: String, value: String, pct: Int, color: Int): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val top = LinearLayout(this)
        top.addView(tv(label, 13f, 0xCCFFFFFF.toInt(), MED), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv(value, 13f, Color.WHITE))
        r.addView(top)
        val p = pct.coerceIn(0, 100)
        val track = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dpf(4)
            }
        }
        track.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dpf(4)
                }
            },
            LinearLayout.LayoutParams(0, -1, p.toFloat())
        )
        track.addView(View(this), LinearLayout.LayoutParams(0, -1, (100 - p).toFloat()))
        r.addView(track, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4) })
        return r
    }

    private fun refreshSys() {
        sysBox.removeAllViews()
        sysBox.addView(tv("SYSTEM", 11f, 0x99FFFFFF.toInt(), MED))
        val bi = batteryIntent()
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else 0
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        battView.set(pct, charging)
        battText.text = "$pct%"
        val bLabel = if (charging) "Battery • Charging" else "Battery"
        sysBox.addView(barRow(bLabel, "$pct%", pct, if (pct <= 15) 0xFFFF453A.toInt() else 0xFF30D158.toInt()))

        val sf = StatFs(Environment.getDataDirectory().path)
        val tot = sf.blockCountLong * sf.blockSizeLong
        val free = sf.availableBlocksLong * sf.blockSizeLong
        if (tot > 0) {
            sysBox.addView(barRow("Storage", gb(tot - free) + " / " + gb(tot), (((tot - free) * 100) / tot).toInt(), 0xFF0A84FF.toInt()))
        }
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        if (mi.totalMem > 0) {
            val used = mi.totalMem - mi.availMem
            sysBox.addView(barRow("Memory", gb(used) + " / " + gb(mi.totalMem), ((used * 100) / mi.totalMem).toInt(), 0xFFFF9F0A.toInt()))
        }
    }

    // ================= DOCK =================
    private fun defaultDock(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val pkgs = all.map { it.pkg }.toSet()
        return intents.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
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
            if (list.size >= 5) {
                Toast.makeText(this, "Dock full (5)", Toast.LENGTH_SHORT).show()
                return
            }
            list.add(pkg)
        }
        prefs.edit().putString("dock", list.joinToString(",")).apply()
        renderDock()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val lpCell = FrameLayout(this).apply { clipChildren = false }
        lpCell.addView(
            LaunchpadGlyph(this),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) }
        )
        lpCell.setOnClickListener { bounce(lpCell); openLaunchpad() }
        dock.addView(lpCell, LinearLayout.LayoutParams(0, -1, 1f))

        val h = hiddenSet()
        val byPkg = all.associateBy { it.pkg }
        val showDots = prefs.getBoolean("dots", true)
        for (pkg in dockPkgs()) {
            if (pkg in h) continue
            val app = byPkg[pkg] ?: continue
            val cell = IconView(this, dp(48), dp(6))
            cell.bind(app.icon, badges[pkg] ?: 0, showDots && pkg in running)
            cell.setOnClickListener { open(app, cell, cell) }
            cell.setOnLongClickListener { showMenu(app); true }
            dock.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    // ================= LAUNCHPAD =================
    private fun buildLaunchpad(): FrameLayout {
        val f = FrameLayout(this).apply { setBackgroundColor(0xE60C0C12.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = FrameLayout(this)
        top.addView(
            trafficLights({ closeAll() }, { closeAll() }, { closeAll() }),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL)
        )
        lpSearch = EditText(this).apply {
            hint = "Search"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(REG, Typeface.NORMAL)
            isSingleLine = true
            gravity = Gravity.CENTER
            background = glass(0x26FFFFFF, 10)
            setPadding(dp(14), 0, dp(14), 0)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyLpFilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                lpShown.firstOrNull()?.let { open(it, null) }
                true
            }
        }
        top.addView(lpSearch, FrameLayout.LayoutParams(dp(210), dp(34), Gravity.CENTER))
        col.addView(top, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(10), dp(14), dp(10), 0) })

        lpGrid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(20)
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(8), dp(24), dp(8), dp(16))
            clipToPadding = false
            setOnItemClickListener { _, v, p, _ -> lpShown.getOrNull(p)?.let { open(it, v) } }
            setOnItemLongClickListener { _, _, p, _ -> lpShown.getOrNull(p)?.let { showMenu(it) }; true }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {
                    if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKb()
                }
                override fun onScroll(v: AbsListView?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(lpGrid, LinearLayout.LayoutParams(-1, 0, 1f))
        f.addView(col, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    inner class AppAdapter(private val data: () -> List<App>, private val iconDp: () -> Int) : BaseAdapter() {
        override fun getCount() = data().size
        override fun getItem(p: Int) = data()[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(IconView(context, dp(iconDp()), 0), LinearLayout.LayoutParams(-1, dp(iconDp())))
                addView(
                    tv("", 11f, Color.WHITE, REG, Gravity.CENTER).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                )
                pressable(this)
            }
            val app = data()[p]
            (cell.getChildAt(0) as IconView).bind(app.icon, badges[app.pkg] ?: 0, false)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }

    // ================= SPOTLIGHT =================
    private fun buildSpotlight(): FrameLayout {
        val f = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { closeAll() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0xF2222226.toInt(), 18, 0x33FFFFFF)
            isClickable = true
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        row.addView(
            ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_search)
                setColorFilter(0xCCFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(dp(22), dp(22))
        )
        spInput = EditText(this).apply {
            hint = "Spotlight Search"
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create(LIGHT, Typeface.NORMAL)
            background = null
            isSingleLine = true
            setPadding(dp(12), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { buildResults(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                spResults.getChildAt(0)?.performClick()
                true
            }
        }
        row.addView(spInput, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        card.addView(divider(), LinearLayout.LayoutParams(-1, 1))
        spResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(spResults, LinearLayout.LayoutParams(-1, -2))
        f.addView(card, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(64), dp(14), 0) })
        return f
    }

    private fun resultRow(icon: Drawable?, glyph: String, title: String, sub: String, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        selectable(r)
        if (icon != null) {
            val iv = IconView(this, dp(36), 0)
            iv.bind(icon, 0, false)
            r.addView(iv, LinearLayout.LayoutParams(dp(36), dp(36)))
        } else {
            r.addView(
                tv(glyph, 18f, Color.WHITE, REG, Gravity.CENTER).apply { background = glass(0x33FFFFFF, 9, 0) },
                LinearLayout.LayoutParams(dp(36), dp(36))
            )
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 16f, Color.WHITE, MED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(tv(sub, 12f, 0x99FFFFFF.toInt()))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        r.setOnClickListener { onClick() }
        return r
    }

    private fun buildResults(q: String) {
        spResults.removeAllViews()
        val query = q.trim()
        val vis = visibleApps()
        val rows = ArrayList<View>()
        if (query.isEmpty()) {
            recents().mapNotNull { p -> vis.firstOrNull { it.pkg == p } }.take(5).forEach { a ->
                rows.add(resultRow(a.icon, "", a.label, "Recent") { open(a, null) })
            }
        } else {
            Calc.eval(query)?.let { res ->
                rows.add(resultRow(null, "🧮", "= $res", "Calculator • tap to copy") {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("result", res))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    closeAll()
                })
            }
            vis.filter { it.label.contains(query, true) }
                .sortedBy { !it.label.startsWith(query, true) }
                .take(5)
                .forEach { a -> rows.add(resultRow(a.icon, "", a.label, "Application") { open(a, null) }) }
            if (query.equals("hidden", true)) {
                rows.add(resultRow(null, "🔒", "Hidden Apps", "Open") { closeAll(); openHidden() })
            }
            if (query.startsWith("pref", true) || query.startsWith("sett", true)) {
                rows.add(resultRow(null, "⚙️", "System Preferences…", "Launcher") { closeAll(); showPrefs() })
            }
            rows.add(resultRow(null, "🌐", "Search the web for “$query”", "Web") {
                closeAll()
                safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
            })
        }
        for ((i, r) in rows.withIndex()) {
            if (i > 0) spResults.addView(divider(), LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(64) })
            spResults.addView(r)
        }
    }

    // ================= NOTIFICATION CENTER =================
    private fun tile(emoji: String, label: String, action: String): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        t.addView(
            tv(emoji, 20f, Color.WHITE, REG, Gravity.CENTER).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33FFFFFF)
                }
            },
            LinearLayout.LayoutParams(dp(46), dp(46))
        )
        t.addView(
            tv(label, 10f, 0xCCFFFFFF.toInt(), REG, Gravity.CENTER),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) }
        )
        pressable(t)
        t.setOnClickListener { safeStart(Intent(action)) }
        return t
    }

    private fun syncVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        volBar.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volBar.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun buildNotifCenter(): FrameLayout {
        val f = FrameLayout(this).apply {
            setBackgroundColor(0x40000000)
            setOnClickListener { closeAll() }
        }
        ncPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0xF2151519.toInt(), 22, 0x33FFFFFF)
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(4))
        }
        head.addView(trafficLights({ closeAll() }, { closeAll() }, { closeAll() }))
        head.addView(tv("Notification Center", 15f, Color.WHITE, MED, Gravity.CENTER), LinearLayout.LayoutParams(0, dp(34), 1f))
        head.addView(
            tv("Clear", 13f, 0xFF0A84FF.toInt(), MED).apply {
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnClickListener {
                    try { NotifService.instance?.cancelAllNotifications() } catch (e: Exception) {}
                    rebuildNotifs()
                }
            }
        )
        ncPanel.addView(head)

        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(14))
        }

        // Control Center
        val cc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0x22FFFFFF, 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val r1 = LinearLayout(this)
        val r2 = LinearLayout(this)
        val w = LinearLayout.LayoutParams(0, -2, 1f)
        r1.addView(tile("📶", "Wi‑Fi", Settings.ACTION_WIFI_SETTINGS), w)
        r1.addView(tile("🎧", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(tile("✈️", "Airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(tile("📍", "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(tile("☀️", "Display", Settings.ACTION_DISPLAY_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(tile("🔔", "Sound", Settings.ACTION_SOUND_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(tile("🔋", "Saver", Settings.ACTION_BATTERY_SAVER_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(tile("⚙️", "Settings", Settings.ACTION_SETTINGS), LinearLayout.LayoutParams(0, -2, 1f))
        cc.addView(r1)
        cc.addView(r2)

        val volRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        volRow.addView(tv("🔈", 14f, Color.WHITE, REG, Gravity.CENTER))
        volBar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        (getSystemService(AUDIO_SERVICE) as AudioManager)
                            .setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        volRow.addView(volBar, LinearLayout.LayoutParams(0, -2, 1f))
        volRow.addView(tv("🔊", 14f, Color.WHITE, REG, Gravity.CENTER))
        cc.addView(volRow)
        body.addView(cc, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        body.addView(tv("NOTIFICATIONS", 11f, 0x99FFFFFF.toInt(), MED).apply { setPadding(dp(4), 0, 0, dp(6)) })
        ncList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ncList, LinearLayout.LayoutParams(-1, -2))

        sc.addView(body, FrameLayout.LayoutParams(-1, -2))
        ncPanel.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        f.addView(ncPanel, FrameLayout.LayoutParams(ncW, -1, Gravity.END).apply { setMargins(0, dp(8), dp(8), dp(8)) })
        return f
    }

    // ================= mac-style windows (Dialogs) =================
    private fun macWindow(title: String, onClose: () -> Unit): Pair<LinearLayout, LinearLayout> {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0xF2202026.toInt(), 14, 0x33FFFFFF)
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(trafficLights(onClose, onClose, onClose))
        bar.addView(tv(title, 14f, 0xFFE6E6E6.toInt(), MED, Gravity.CENTER), LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(78), 1))
        outer.addView(bar, LinearLayout.LayoutParams(-1, dp(38)))
        outer.addView(divider(), LinearLayout.LayoutParams(-1, 1))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        outer.addView(content, LinearLayout.LayoutParams(-1, -2))
        return Pair(outer, content)
    }

    private fun showWindowDialog(content: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.alpha = 0f
        content.scaleX = 0.9f
        content.scaleY = 0.9f
        d.show()
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        return d
    }

    private fun sectionLabel(t: String) =
        tv(t.uppercase(), 11f, 0x99FFFFFF.toInt(), MED).apply { setPadding(0, dp(12), 0, dp(6)) }

    private fun chips(opts: List<String>, sel: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            background = glass(0x26FFFFFF, 9, 0)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val views = ArrayList<TextView>()
        fun paint(s: Int) {
            for ((i, t) in views.withIndex()) {
                t.background = if (i == s) glass(0xFF0A84FF.toInt(), 7, 0) else null
            }
        }
        for ((i, o) in opts.withIndex()) {
            val t = tv(o, 13f, Color.WHITE, MED, Gravity.CENTER).apply { setPadding(0, dp(8), 0, dp(8)) }
            t.setOnClickListener { paint(i); onPick(i) }
            views.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint(sel)
        return row
    }

    private fun switchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        r.addView(tv(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return r
    }

    private fun button(label: String, onClick: () -> Unit): View =
        tv(label, 14f, Color.WHITE, MED, Gravity.CENTER).apply {
            background = glass(0x26FFFFFF, 9, 0)
            setPadding(0, dp(11), 0, dp(11))
            setOnClickListener { onClick() }
        }

    private fun btnLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }

    private fun showPrefs() {
        var dlg: Dialog? = null
        val (win, body) = macWindow("System Preferences") { dlg?.dismiss() }
        body.addView(sectionLabel("Wallpaper"))
        body.addView(chips(listOf("System", "Big Sur Day", "Big Sur Night"), prefs.getInt("wall", 0)) { i ->
            prefs.edit().putInt("wall", i).apply(); applyPrefs()
        })
        body.addView(sectionLabel("Icon size"))
        body.addView(chips(listOf("Small", "Medium", "Large"), prefs.getInt("size", 1)) { i ->
            prefs.edit().putInt("size", i).apply(); applyPrefs()
        })
        body.addView(sectionLabel("Launchpad columns"))
        body.addView(chips(listOf("4", "5"), prefs.getInt("cols", 4) - 4) { i ->
            prefs.edit().putInt("cols", 4 + i).apply(); applyPrefs()
        })
        body.addView(switchRow("Dock magnification", prefs.getBoolean("mag", true)) { on ->
            prefs.edit().putBoolean("mag", on).apply(); applyPrefs()
        })
        body.addView(switchRow("Running indicators", prefs.getBoolean("dots", true)) { on ->
            prefs.edit().putBoolean("dots", on).apply(); applyPrefs()
        })
        body.addView(button("Notification Access…") {
            safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, btnLp())
        body.addView(button("Set as Default Launcher…") {
            safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
        }, btnLp())
        body.addView(button("Reload Apps") { loadApps() }, btnLp())
        dlg = showWindowDialog(win)
    }

    private fun showAbout() {
        var dlg: Dialog? = null
        val (win, body) = macWindow("About This Launcher") { dlg?.dismiss() }
        body.addView(tv("Clean Launcher", 20f, Color.WHITE, MED))
        body.addView(tv("macOS Big Sur inspired home screen", 13f, 0xAAFFFFFF.toInt()).apply { setPadding(0, dp(2), 0, dp(12)) })
        body.addView(
            tv(
                "• Swipe up from the dock: Launchpad\n" +
                    "• Swipe down at the top: Spotlight (apps, calculator, web)\n" +
                    "• Swipe left from the right edge, or tap the clock: Notification Center\n" +
                    "• Long-press an icon: Dock, Hide, Info, Uninstall\n" +
                    "• Long-press “Launcher” in the menu bar: Hidden Apps",
                13f, 0xDDFFFFFF.toInt()
            ).apply { setLineSpacing(0f, 1.25f) }
        )
        dlg = showWindowDialog(win)
    }

    private fun showAppMenu() {
        menuPopup?.dismiss()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0xF2262629.toInt(), 10, 0x33FFFFFF)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun item(t: String, a: () -> Unit) {
            col.addView(tv(t, 14f, Color.WHITE, REG).apply {
                setPadding(dp(16), dp(10), dp(24), dp(10))
                selectable(this)
                setOnClickListener { menuPopup?.dismiss(); a() }
            })
        }
        item("About This Launcher") { showAbout() }
        item("System Preferences…") { showPrefs() }
        item("Hidden Apps…") { openHidden() }
        item("Notification Access…") { safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        item("Reload Apps") { loadApps() }
        val pw = PopupWindow(col, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dpf(8)
        menuPopup = pw
        pw.showAtLocation(rootFrame, Gravity.TOP or Gravity.START, dp(40), insetTop + dp(34))
    }

    private fun applyPrefs() {
        val w = prefs.getInt("wall", 0)
        wall.visibility = if (w == 0) View.GONE else View.VISIBLE
        wall.night = (w == 2)
        lpGrid.numColumns = prefs.getInt("cols", 4)
        lpGrid.adapter = AppAdapter({ lpShown }, { iconDp() })
        dock.magnifyOn = prefs.getBoolean("mag", true)
        renderDock()
    }

    // ================= HIDDEN APPS =================
    private fun openHidden() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isDeviceSecure) {
            val i = km.createConfirmDeviceCredentialIntent("Hidden Apps", "Unlock to see hidden apps")
            if (i != null) {
                startActivityForResult(i, reqUnlock)
                return
            }
        }
        showHiddenWindow()
    }

    private fun showHiddenWindow() {
        hiddenDlg?.dismiss()
        var dlg: Dialog? = null
        val (win, body) = macWindow("Hidden Apps") { dlg?.dismiss() }
        val ad = AppAdapter({ hiddenApps() }, { iconDp() })
        hiddenAdapter = ad
        if (hiddenApps().isEmpty()) {
            body.addView(tv("No hidden apps yet. Long-press any app and choose Hide App.", 14f, 0xAAFFFFFF.toInt()).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            val g = GridView(this).apply {
                numColumns = 4
                verticalSpacing = dp(14)
                selector = ColorDrawable(Color.TRANSPARENT)
                adapter = ad
                setOnItemClickListener { _, v, p, _ ->
                    hiddenApps().getOrNull(p)?.let { dlg?.dismiss(); open(it, v) }
                }
                setOnItemLongClickListener { _, _, p, _ -> hiddenApps().getOrNull(p)?.let { showMenu(it) }; true }
            }
            body.addView(g, LinearLayout.LayoutParams(-1, dp(300)))
        }
        val shown = showWindowDialog(win)
        dlg = shown
        shown.setOnDismissListener { hiddenAdapter = null; hiddenDlg = null }
        hiddenDlg = shown
    }

    private fun toggleHidden(pkg: String) {
        val s = hiddenSet()
        val nowHidden = if (s.remove(pkg)) false else { s.add(pkg); true }
        saveHidden(s)
        if (nowHidden) {
            val d = dockPkgs()
            if (d.remove(pkg)) prefs.edit().putString("dock", d.joinToString(",")).apply()
        }
        applyLpFilter()
        renderDock()
        refreshRecents()
        hiddenAdapter?.notifyDataSetChanged()
        if (hiddenDlg != null && hiddenApps().isEmpty()) hiddenDlg?.dismiss()
        Toast.makeText(
            this,
            if (nowHidden) "Hidden. Long-press “Launcher” to see hidden apps" else "Unhidden",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ================= long-press menu =================
    private fun showMenu(app: App) {
        menuDlg?.dismiss()
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(0xF21C1C1E.toInt(), 16, 0x33FFFFFF)
            setPadding(0, dp(8), 0, dp(8))
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val iv = IconView(this, dp(40), 0)
        iv.bind(app.icon, 0, false)
        head.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(
            tv(app.label, 17f, Color.WHITE, MED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) }
        )
        sheet.addView(head)

        fun row(label: String, red: Boolean = false, action: () -> Unit) {
            sheet.addView(divider(), LinearLayout.LayoutParams(-1, 1))
            sheet.addView(tv(label, 16f, if (red) 0xFFFF453A.toInt() else Color.WHITE, REG).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                selectable(this)
                setOnClickListener {
                    d.dismiss()
                    try { action() } catch (_: Exception) {}
                }
            })
        }

        val isHidden = app.pkg in hiddenSet()
        row("Open") { open(app, null) }
        if (!isHidden) {
            val inDock = dockPkgs().contains(app.pkg)
            row(if (inDock) "Remove from Dock" else "Add to Dock") { toggleDock(app.pkg) }
        }
        row(if (isHidden) "Unhide App" else "Hide App") { toggleHidden(app.pkg) }
        row("App Info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        row("Uninstall", true) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        }

        d.setContentView(sheet)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheet.alpha = 0f
        sheet.scaleX = 0.88f
        sheet.scaleY = 0.88f
        d.show()
        sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
        menuDlg = d
    }
}
