package com.teleteh.xplayer2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayoutMediator
import com.teleteh.xplayer2.data.depth.DepthModelManager
import com.teleteh.xplayer2.data.glasses.GlassesController
import com.teleteh.xplayer2.data.glasses.GlassesProtocol
import com.teleteh.xplayer2.databinding.ActivityMainBinding
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.data.RecentEntry
import com.teleteh.xplayer2.data.RecentStore
import com.teleteh.xplayer2.data.SourceType
import com.teleteh.xplayer2.player.MenuMirrorPresentation
import com.teleteh.xplayer2.ui.MainPagerAdapter
import com.teleteh.xplayer2.ui.util.DisplayUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val glasses: GlassesController by lazy {
        GlassesController(applicationContext).also { glassesControllerForPlayback = it }
    }
    private var glassesMenuItem: MenuItem? = null

    // Glasses menu on the external (glasses) display: while we're in the main section the goggles
    // would otherwise just mirror the phone UI (wrong per-eye on the ultra-wide 3D panel), so on an
    // ultra-wide external display we put up our own menu that reflects the phone's focused item.
    // Shown while MainActivity is foreground; PlayerActivity takes the display over when a film starts.
    private val displayManager: DisplayManager? by lazy { getSystemService(DisplayManager::class.java) }
    private var menuPresentation: MenuMirrorPresentation? = null
    private var glassesRows: List<MenuMirrorPresentation.Row> = emptyList()
    private var glassesTab = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcileGlassesMenu()
        override fun onDisplayRemoved(displayId: Int) = reconcileGlassesMenu()
        override fun onDisplayChanged(displayId: Int) = reconcileGlassesMenu()
    }
    private val glassesFocusListener =
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> pushGlassesMenu() }
    private val glassesPageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = refreshGlassesMenu()
    }
    // Head-as-D-pad: a nod past the threshold fires one DPAD_UP/DOWN into the phone's focus engine,
    // which moves the focused row (and the glasses highlight follows). Ratchets: re-arms near neutral.
    // Head-as-D-pad from RAW gyro rates (deg/s) — drift-free, high thresholds ignore small moves.
    // A real nod is "tilt + return" (you can't hold the head tilted), so the RETURN spike (opposite
    // direction, right after a step) is suppressed for gestureReturnNs — only the intended direction
    // steps. Same-direction repeats are allowed after gestureMinGapNs. Pitch (gx): down(+)->FOCUS_DOWN,
    // up(-)->FOCUS_UP. Yaw (gz): left(+)->previous tab, right(-)->next tab.
    private val nodRateDps = 70f
    private val turnRateDps = 80f
    private val rollRateDps = 80f
    private val gestureMinGapNs = 250_000_000L   // debounce: ignore the rest of one spike
    private val gestureReturnNs = 850_000_000L   // ignore the opposite "return to neutral" motion
    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pitchDir = 0
    private var pitchFireNs = 0L
    private var yawDir = 0
    private var yawFireNs = 0L
    private var rollDir = 0
    private var rollFireNs = 0L
    private val gestureTick = object : Runnable {
        override fun run() {
            if (menuPresentation == null) return
            val imu = glasses.latestImuDebug()
            if (imu != null && imu.size >= 3) {
                val now = System.nanoTime()
                val gx = imu[0] // pitch rate
                val gz = imu[2] // yaw rate
                val pdir = if (gx > nodRateDps) 1 else if (gx < -nodRateDps) -1 else 0
                if (pdir != 0 && now - pitchFireNs > gestureMinGapNs &&
                    !(pdir == -pitchDir && now - pitchFireNs < gestureReturnNs)
                ) {
                    stepFocus(if (pdir > 0) android.view.View.FOCUS_DOWN else android.view.View.FOCUS_UP)
                    menuPresentation?.showGesture(if (pdir > 0) "▼" else "▲")
                    pitchDir = pdir; pitchFireNs = now
                }
                val gy = imu[1] // roll rate
                // Yaw (turn) -> paging, only when yaw dominates roll (a head tilt often adds yaw).
                val ydir = if (gz > turnRateDps && kotlin.math.abs(gz) > kotlin.math.abs(gy)) 1
                    else if (gz < -turnRateDps && kotlin.math.abs(gz) > kotlin.math.abs(gy)) -1 else 0
                if (ydir != 0 && now - yawFireNs > gestureMinGapNs &&
                    !(ydir == -yawDir && now - yawFireNs < gestureReturnNs)
                ) {
                    switchTab(if (ydir > 0) -1 else 1) // turn left -> previous tab, right -> next
                    menuPresentation?.showGesture(if (ydir > 0) "◀ tab" else "tab ▶")
                    yawDir = ydir; yawFireNs = now
                }
                // Roll (head tilt) -> OK / Back, only when roll dominates yaw (filters involuntary yaw).
                val rdir = if (gy > rollRateDps && kotlin.math.abs(gy) > kotlin.math.abs(gz)) 1
                    else if (gy < -rollRateDps && kotlin.math.abs(gy) > kotlin.math.abs(gz)) -1 else 0
                if (rdir != 0 && now - rollFireNs > gestureMinGapNs &&
                    !(rdir == -rollDir && now - rollFireNs < gestureReturnNs)
                ) {
                    if (rdir > 0) { selectFocused(); menuPresentation?.showGesture("OK ✔") } else goBack()
                    rollDir = rdir; rollFireNs = now
                }
            }
            gestureHandler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge before setting content view
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()
        hideSystemBars()

        // Use MaterialToolbar as ActionBar to fix menu overlap under title on older Android
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.app_name)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        // Decode and scale toolbar logo on background to avoid blocking onCreate
        // (large PNG decode + scale can stall main thread for hundreds of ms on slow devices,
        //  contributing to ANR via MessageQueue.nativePollOnce).
        loadToolbarLogoAsync(toolbar)

        val viewPager: ViewPager2 = binding.viewPager
        viewPager.adapter = MainPagerAdapter(this)

        val tabTitles = listOf(getString(R.string.tab_recent), getString(R.string.tab_sources))
        TabLayoutMediator(binding.tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
            tab.contentDescription = null
        }.attach()

        setupTvFocusNavigation()
        prefetchDepthModelIfNeeded()
        registerDoubleBackToExit()
    }

    /**
     * Quietly pre-download the Lazy-3D depth model in the background so the feature is ready
     * to use instantly later. Strictly opt-in to Wi-Fi/unmetered to avoid spending the
     * user's cellular data on a ~65 MB file they may never need. No UI, no progress — if it
     * fails or the network is metered we simply try again next launch, and the on-demand
     * download in PlayerActivity remains the fallback.
     */
    private fun prefetchDepthModelIfNeeded() {
        val mgr = DepthModelManager(applicationContext)
        if (mgr.isAvailable()) return
        if (!isUnmeteredNetwork()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!mgr.isAvailable()) {
                    android.util.Log.i("MainActivity", "Prefetching Lazy 3D depth model in background…")
                    val ok = mgr.ensureAvailable(null)
                    android.util.Log.i("MainActivity", "Lazy 3D depth model prefetch ${if (ok) "done" else "failed"}")
                }
            } catch (_: Throwable) { }
        }
    }

    private fun isUnmeteredNetwork(): Boolean = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Throwable) {
        false
    }

    // --- Android TV / D-pad keyboard navigation ---
    private fun setupTvFocusNavigation() {
        binding.tabLayout.isFocusable = true
        binding.tabLayout.isFocusableInTouchMode = true
        binding.tabLayout.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.tabLayout.post { focusActiveTab() }
        }
        for (i in 0 until binding.tabLayout.tabCount) {
            val tabView = binding.tabLayout.getTabAt(i)?.view ?: continue
            tabView.isFocusable = true
            tabView.isFocusableInTouchMode = true
            tabView.isLongClickable = false
            tabView.tooltipText = null
            tabView.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) v.requestFocus()
                false
            }
            tabView.setOnClickListener { v ->
                v.requestFocus()
                binding.tabLayout.getTabAt(i)?.select()
            }
            tabView.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val fragmentView = currentFragmentView()
                    if (fragmentView != null && focusFirstVisibleControl(fragmentView)) {
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val fragmentView = currentFragmentView()
            if (fragmentView != null && shouldReturnToTabsOnUp(fragmentView)) {
                focusActiveTab()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun currentFragmentView(): View? =
        supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")?.view

    private fun focusActiveTab() {
        val index = binding.viewPager.currentItem.coerceAtLeast(0)
        binding.tabLayout.getTabAt(index)?.view?.takeIf { it !== currentFocus }?.requestFocus()
    }

    private fun shouldReturnToTabsOnUp(fragmentView: View): Boolean {
        val focused = currentFocus ?: return false
        // First-row controls on each fragment should return to tabs on UP
        val firstRow = listOf(R.id.etUrl, R.id.btnOpenUrl, R.id.btnOpenFile, R.id.btnHughey)
        if (firstRow.any { fragmentView.findViewById<View?>(it) === focused }) return true
        val recycler: RecyclerView? = fragmentView.findViewById<RecyclerView?>(R.id.rvRecent)
            ?: fragmentView.findViewById<RecyclerView?>(R.id.rvNetwork)
        if (recycler != null && isDescendantOf(focused, recycler)) {
            val itemView = findRecyclerItemView(focused, recycler) ?: return true
            return recycler.getChildAdapterPosition(itemView) == 0
        }
        return false
    }

    private fun isDescendantOf(child: View?, ancestor: View): Boolean {
        var cur: View? = child
        while (cur != null) {
            if (cur === ancestor) return true
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return false
    }

    private fun findRecyclerItemView(focused: View, recycler: RecyclerView): View? {
        var cur: View? = focused
        while (cur != null) {
            if (cur.parent === recycler) return cur
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return null
    }

    private fun isActuallyFocusable(view: View): Boolean =
        view.isFocusable && view.isEnabled && view.visibility == View.VISIBLE && view.isShown &&
            view.width > 0 && view.height > 0 && view.alpha > 0f

    private fun findFirstFocusable(view: View): View? {
        if (isActuallyFocusable(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = findFirstFocusable(view.getChildAt(i))
                if (c != null) return c
            }
        }
        return null
    }

    private fun focusFirstVisibleControl(fragmentView: View): Boolean {
        listOf(R.id.etUrl, R.id.btnOpenUrl, R.id.btnOpenFile, R.id.btnHughey).forEach { id ->
            fragmentView.findViewById<View?>(id)?.takeIf { isActuallyFocusable(it) }?.let {
                it.requestFocus(); return true
            }
        }
        val recycler: RecyclerView? = fragmentView.findViewById<RecyclerView?>(R.id.rvRecent)
            ?: fragmentView.findViewById<RecyclerView?>(R.id.rvNetwork)
        if (recycler != null && isActuallyFocusable(recycler) && recycler.childCount > 0) {
            val first = recycler.getChildAt(0)
            if (first != null && isActuallyFocusable(first)) {
                first.requestFocus(); return true
            }
            recycler.requestFocus(); return true
        }
        return findFirstFocusable(fragmentView)?.also { it.requestFocus() } != null
    }

    private fun loadToolbarLogoAsync(toolbar: MaterialToolbar) {
        val density = resources.displayMetrics.density
        val targetHeightPx = (24f * density).toInt().coerceAtLeast(1)
        lifecycleScope.launch {
            val drawable: Drawable? = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bmp = BitmapFactory.decodeResource(resources, R.drawable.hero_image_for_block_g, opts)
                        ?: return@runCatching null
                    val aspect = if (bmp.height != 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
                    val targetWidthPx = (targetHeightPx * aspect).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bmp, targetWidthPx, targetHeightPx, true)
                    if (scaled !== bmp) bmp.recycle()
                    BitmapDrawable(resources, scaled)
                }.getOrNull()
            }
            if (drawable != null) {
                supportActionBar?.apply {
                    setDisplayUseLogoEnabled(true)
                    setLogo(drawable)
                }
                toolbar.logo = drawable
            }
        }
    }

    private fun setupEdgeToEdge() {
        // Handle window insets for edge-to-edge display
        // AppBarLayout handles status bar insets automatically when fitsSystemWindows=true
        // For navigation bar, we apply padding to the ViewPager
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewPager) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply bottom padding for navigation bar
            view.updatePadding(bottom = insets.bottom)
            // Return insets so other views can also consume them
            windowInsets
        }
    }

    private fun hideSystemBars() {
        // Hide status bar but keep navigation bar visible for edge-to-edge
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // For legacy devices (API < 30), also apply classic flags
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        glassesMenuItem = menu.findItem(R.id.menu_glasses)
        // The item uses a custom actionView (icon + mode chip), so taps arrive through the view,
        // not onOptionsItemSelected.
        glassesMenuItem?.actionView?.setOnClickListener { showGlassesModePicker() }
        updateGlassesMenu()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_glasses -> {
            showGlassesModePicker()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        glasses.setListener { _, _ -> updateGlassesMenu() }
        glasses.register()
        // Show our menu on the glasses while we're the foreground (browsing) screen, and react to
        // the goggles being plugged/unplugged, tab switches, and focus moves (to keep it in sync).
        displayManager?.registerDisplayListener(displayListener, null)
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(glassesFocusListener)
        binding.viewPager.registerOnPageChangeCallback(glassesPageCallback)
        reconcileGlassesMenu()
    }

    override fun onStop() {
        super.onStop()
        glasses.setListener(null)
        glasses.unregister()
        // Give the external display back (PlayerActivity will put video on it, or it returns to
        // mirroring once nothing owns it).
        displayManager?.unregisterDisplayListener(displayListener)
        binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(glassesFocusListener)
        binding.viewPager.unregisterOnPageChangeCallback(glassesPageCallback)
        dismissGlassesMenu()
    }

    override fun onResume() {
        super.onResume()
        // PlayerActivity might have come and gone in the background; re-evaluate enabled state.
        updateGlassesMenu()
        refreshGlassesMenu()
    }

    /**
     * Put our menu up on the external (glasses) display if an ultra-wide one is present, or take it
     * down if the display went away. Idempotent — safe to call on every display event.
     */
    private fun reconcileGlassesMenu() {
        val ext = DisplayUtils.findUltraWideExternalDisplay(this)
        if (ext == null) { dismissGlassesMenu(); return }
        if (menuPresentation?.display?.displayId == ext.displayId) { refreshGlassesMenu(); return }
        dismissGlassesMenu()
        try {
            MenuMirrorPresentation(this, ext) { glasses.latestImuDebug() }
                .also { it.show(); menuPresentation = it }
            refreshGlassesMenu()
            startGestureLoop()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "Menu presentation failed: ${t.message}")
            dismissGlassesMenu()
        }
    }

    private fun dismissGlassesMenu() {
        stopGestureLoop()
        try { menuPresentation?.dismiss() } catch (_: Throwable) {}
        menuPresentation = null
    }

    /** Force a rebuild of the current tab's rows on the next push, then push + ensure a focused row. */
    private fun refreshGlassesMenu() {
        glassesTab = -1
        pushGlassesMenu()
        ensureGlassesRowFocused()
    }

    /** Reflect the phone's current tab + focused row onto the glasses menu (no-op if not shown). */
    private fun pushGlassesMenu() {
        val pres = menuPresentation ?: return
        val tab = binding.viewPager.currentItem
        val title = if (tab == 0) getString(R.string.tab_recent) else getString(R.string.tab_sources)
        if (tab == 0) {
            // Recent: rich data rows (icon + title + position + stereo chip), cached per tab.
            if (tab != glassesTab) {
                glassesTab = tab
                glassesRows = RecentStore(this).getAll().map { e ->
                    val st = e.sourceType ?: RecentEntry.detectSourceType(android.net.Uri.parse(e.uri))
                    val icon = when (st) {
                        SourceType.VK -> R.drawable.ic_source_vk
                        SourceType.OK -> R.drawable.ic_source_ok
                        SourceType.LOCAL -> R.drawable.ic_source_local
                        SourceType.NETWORK -> R.drawable.ic_source_network
                        SourceType.YANDEX -> R.drawable.ic_source_yadisk
                        else -> R.drawable.ic_source_unknown
                    }
                    val chip = when {
                        e.stereoMode == 2 || e.framePacking == 3 -> getString(R.string.menu_stereo_short)
                        e.stereoMode == 1 || e.framePacking == 4 -> getString(R.string.remote_ou_to_sbs)
                        else -> null
                    }
                    val sub = getString(R.string.recent_position_prefix) + fmtMs(e.lastPositionMs) +
                        if (e.durationMs > 0) " / " + fmtMs(e.durationMs) else ""
                    MenuMirrorPresentation.Row(e.title, sub, icon, chip)
                }
            }
            val rv = currentFragmentView()?.findViewById<RecyclerView?>(R.id.rvRecent)
            var idx = rv?.focusedChild?.let { rv.getChildAdapterPosition(it) } ?: -1
            if (idx < 0 && glassesRows.isNotEmpty()) idx = 0
            pres.render(title, glassesRows, idx)
        } else {
            // Sources: reflect the tab's focusable items (Open File / URL / Hughey + DLNA/SMB list)
            // by walking the live view tree, so it covers buttons + dynamically-discovered rows.
            glassesTab = tab
            val items = currentFragmentView()?.let { collectFocusableItems(it) } ?: emptyList()
            glassesRows = items.map { MenuMirrorPresentation.Row(labelOf(it)) }
            var idx = items.indexOfFirst { it.isFocused }
            if (idx < 0 && glassesRows.isNotEmpty()) idx = 0
            pres.render(title, glassesRows, idx)
        }
    }

    /** Leaf focusable controls (buttons, list rows, text fields) under [root], in view-tree order. */
    private fun collectFocusableItems(root: android.view.View): List<android.view.View> {
        val out = ArrayList<android.view.View>()
        fun walk(v: android.view.View) {
            if (!v.isShown) return
            if (v.isFocusable && (v.isClickable || v is android.widget.EditText)) { out.add(v); return }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** A human label for a focusable control: its own text, else a child's text, else its description. */
    private fun labelOf(v: android.view.View): String {
        if (v is android.widget.TextView) v.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (v is android.view.ViewGroup) firstTextIn(v)?.let { return it }
        return v.contentDescription?.toString() ?: ""
    }

    private fun firstTextIn(group: android.view.ViewGroup): String? {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c is android.widget.TextView) c.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            if (c is android.view.ViewGroup) firstTextIn(c)?.let { return it }
        }
        return null
    }

    /** Focus the first row on the phone when entering a section so head-D-pad has a starting point. */
    private fun ensureGlassesRowFocused() {
        if (menuPresentation == null) return
        val rv = currentFragmentView()?.let {
            it.findViewById<RecyclerView?>(R.id.rvRecent) ?: it.findViewById<RecyclerView?>(R.id.rvNetwork)
        } ?: return
        rv.post {
            if (rv.focusedChild == null) {
                (rv.findViewHolderForAdapterPosition(0)?.itemView ?: rv.getChildAt(0))?.requestFocus()
            }
        }
    }

    /**
     * Move focus like a D-pad: focusSearch from the current focus, then requestFocus. This is what
     * the hardware D-pad / keyboard does via ViewRootImpl — a hand-built Activity.dispatchKeyEvent
     * reaches the focused view but does NOT run focus navigation, so it left focus unmoved.
     */
    private fun stepFocus(direction: Int) {
        val cur = currentFocus ?: return
        val next = cur.focusSearch(direction)
        next?.requestFocus(direction)
    }

    /** Flip the ViewPager tab (head-turn "листание"). */
    private fun switchTab(delta: Int) {
        val vp = binding.viewPager
        val n = vp.currentItem + delta
        if (n in 0 until (vp.adapter?.itemCount ?: 0)) vp.currentItem = n
    }

    /** "OK" — activate the focused item, like a D-pad center click. */
    private fun selectFocused() {
        currentFocus?.performClick()
    }

    /** "Back / cancel" via the standard back dispatcher (routed through [registerDoubleBackToExit]). */
    private fun goBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    private var lastBackAt = 0L

    /** On the main screen a single Back — hardware button or head-tilt-left — doesn't exit: it warns,
     *  and only a second Back within 2 s actually leaves the app. */
    private fun registerDoubleBackToExit() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBackAt in 1..2000) {
                    finish()
                } else {
                    lastBackAt = now
                    menuPresentation?.showGesture("↩×2 exit")
                    android.widget.Toast.makeText(
                        this@MainActivity, R.string.press_back_again_to_exit, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private fun startGestureLoop() {
        gestureHandler.removeCallbacks(gestureTick)
        pitchDir = 0; yawDir = 0; rollDir = 0; pitchFireNs = 0L; yawFireNs = 0L; rollFireNs = 0L
        glasses.setImuStreaming(true)   // start the IMU only while the head-gesture menu is shown
        gestureHandler.post(gestureTick)
    }

    private fun stopGestureLoop() {
        gestureHandler.removeCallbacks(gestureTick)
        glasses.setImuStreaming(false)  // stop the IMU when the menu goes away — no idle drain
    }

    private fun fmtMs(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateGlassesMenu() {
        val item = glassesMenuItem ?: return
        // Always visible, always enabled. The previous "disable during playback" rule was
        // over-cautious — switching the panel between 2D and 3D SBS mid-clip works fine on
        // XREAL once the player re-negotiates the output, and it's specifically useful while
        // playback is running.
        item.isVisible = true
        item.isEnabled = true
        // Show the current mode as a chip ("3D 90Hz" / "2D 60Hz") next to the icon, but only for
        // connected XREAL glasses (the brand we actually switch); otherwise just the icon.
        val tv = item.actionView?.findViewById<android.widget.TextView>(R.id.tvGlassesMode) ?: return
        val connected = glasses.currentState() == GlassesController.ConnectionState.Connected &&
            glasses.supportsRemoteSwitch()
        if (connected) {
            // VITURE is a plain 2D/3D toggle (no Hz), so drop the frequency for it.
            tv.text = if (glasses.currentBrand() == GlassesController.Brand.VITURE)
                (if (GlassesProtocol.is3DMode(glasses.lastMode())) "3D" else "2D")
            else GlassesProtocol.shortModeName(glasses.lastMode())
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }

    private fun showGlassesModePicker() {
        when (glasses.currentState()) {
            GlassesController.ConnectionState.NeedsPermission -> {
                Toast.makeText(this, R.string.glasses_needs_permission, Toast.LENGTH_SHORT).show()
                return
            }
            GlassesController.ConnectionState.Disconnected -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.glasses_mode_title)
                    .setMessage(R.string.glasses_not_connected)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
            GlassesController.ConnectionState.Connected -> Unit
        }

        // Detected glasses, but their brand needs a closed-source SDK we don't bundle.
        if (!glasses.supportsRemoteSwitch()) {
            val brand = glasses.currentBrand()?.name ?: "Glasses"
            val model = glasses.currentModel().orEmpty()
            AlertDialog.Builder(this)
                .setTitle("$brand $model".trim())
                .setMessage(getString(R.string.glasses_brand_unsupported, brand))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val items = if (glasses.currentBrand() == GlassesController.Brand.VITURE) {
            // VITURE switches 2D/3D as a binary toggle (no Hz variants).
            listOf(
                GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60 to getString(R.string.glasses_mode_2d_plain),
                GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_90_SBS to getString(R.string.glasses_mode_3d_plain),
            )
        } else {
            listOf(
                GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60 to getString(R.string.glasses_mode_2d, 60),
                GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_72 to getString(R.string.glasses_mode_2d, 72),
                GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_90 to getString(R.string.glasses_mode_2d, 90),
                GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_60_SBS to getString(R.string.glasses_mode_3d_sbs, 60),
                GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_72_SBS to getString(R.string.glasses_mode_3d_sbs, 72),
                GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_90_SBS to getString(R.string.glasses_mode_3d_sbs, 90),
            )
        }
        val labels = items.map { it.second }.toTypedArray()
        // Highlight the mode the user last chose. The controller persists it and re-asserts it
        // on the glasses on every (re)connect, so the picker and the hardware stay in sync
        // instead of snapping back to the 2D power-on default after unplug/replug.
        val current = glasses.lastMode()
        val checked = items.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.glasses_mode_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val (mode, label) = items[which]
                if (glasses.setDisplayMode(mode)) {
                    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.glasses_send_failed, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /**
     * A native method that is implemented by the 'xplayer2' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'xplayer2' library on application startup.
        init {
            System.loadLibrary("xplayer2")
        }

        /**
         * Exposed for [com.teleteh.xplayer2.player.PlayerActivity] / RemoteControlActivity so
         * the Lazy-3D feature can reuse the live USB connection MainActivity already owns
         * (it's where glasses permission and interface claims happen).
         */
        @Volatile var glassesControllerForPlayback: GlassesController? = null
            private set
    }
}