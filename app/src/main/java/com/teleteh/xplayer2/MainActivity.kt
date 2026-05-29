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
import com.teleteh.xplayer2.player.ScreensaverPresentation
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

    // Idle screensaver on the external (glasses) display: while we're in the main section the
    // goggles otherwise just mirror the phone UI, so we put up a retro bouncing-DVD logo instead.
    // Shown while MainActivity is foreground with an external display; PlayerActivity takes the
    // display over when a film starts.
    private val displayManager: DisplayManager? by lazy { getSystemService(DisplayManager::class.java) }
    private var screensaverPresentation: ScreensaverPresentation? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcileScreensaver()
        override fun onDisplayRemoved(displayId: Int) = reconcileScreensaver()
        override fun onDisplayChanged(displayId: Int) = reconcileScreensaver()
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
        // Show the idle screensaver on the glasses while we're the foreground (browsing) screen,
        // and react to the goggles being plugged/unplugged.
        displayManager?.registerDisplayListener(displayListener, null)
        reconcileScreensaver()
    }

    override fun onStop() {
        super.onStop()
        glasses.setListener(null)
        glasses.unregister()
        // Give the external display back (PlayerActivity will put video on it, or it returns to
        // mirroring once nothing owns it).
        displayManager?.unregisterDisplayListener(displayListener)
        dismissScreensaver()
    }

    override fun onResume() {
        super.onResume()
        // PlayerActivity might have come and gone in the background; re-evaluate enabled state.
        updateGlassesMenu()
    }

    /**
     * Put the bouncing-DVD screensaver up on the external (glasses) display if one is present,
     * or take it down if the display went away. Idempotent — safe to call on every display event.
     */
    private fun reconcileScreensaver() {
        val ext = DisplayUtils.findUltraWideExternalDisplay(this)
        if (ext == null) { dismissScreensaver(); return }
        if (screensaverPresentation?.display?.displayId == ext.displayId) return // already up there
        dismissScreensaver()
        try {
            ScreensaverPresentation(this, ext) { glasses.headOrientationDegrees() }
                .also { it.show(); screensaverPresentation = it }
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "Screensaver presentation failed: ${t.message}")
            dismissScreensaver()
        }
    }

    private fun dismissScreensaver() {
        try { screensaverPresentation?.dismiss() } catch (_: Throwable) {}
        screensaverPresentation = null
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
            tv.text = GlassesProtocol.shortModeName(glasses.lastMode())
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

        val items = listOf(
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60 to getString(R.string.glasses_mode_2d, 60),
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_72 to getString(R.string.glasses_mode_2d, 72),
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_90 to getString(R.string.glasses_mode_2d, 90),
            GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_60_SBS to getString(R.string.glasses_mode_3d_sbs, 60),
            GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_72_SBS to getString(R.string.glasses_mode_3d_sbs, 72),
            GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_90_SBS to getString(R.string.glasses_mode_3d_sbs, 90),
        )
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