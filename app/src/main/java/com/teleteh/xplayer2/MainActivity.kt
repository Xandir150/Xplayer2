package com.teleteh.xplayer2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import com.teleteh.xplayer2.databinding.ActivityMainBinding
import com.teleteh.xplayer2.ui.MainPagerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        val tabTitles = listOf("Недавние", "Файлы", "Сеть")
        TabLayoutMediator(binding.tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
            tab.contentDescription = null
        }.attach()

        setupTvFocusNavigation()
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
        val firstRow = listOf(R.id.btnOpen, R.id.etUrl, R.id.btnOpenUrl)
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
        listOf(R.id.btnOpen, R.id.etUrl, R.id.btnOpenUrl).forEach { id ->
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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.menu_stereo)?.let { item ->
            item.isCheckable = true
            item.isChecked = getStereoSbs()
            val actionView = item.actionView
            val btn = actionView?.findViewById<MaterialButton>(R.id.btnSbs)
            if (btn != null) {
                btn.isCheckable = true
                btn.isChecked = getStereoSbs()
                btn.setOnClickListener {
                    toggleStereoMode()
                    btn.isChecked = getStereoSbs()
                }
            }
        }
        return true
    }

    private fun getStereoSbs(): Boolean {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        return prefs.getBoolean("stereo_sbs", false)
    }

    private fun setStereoSbs(value: Boolean) {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("stereo_sbs", value).apply()
    }

    private fun toggleStereoMode() {
        setStereoSbs(!getStereoSbs())
        // Request redraw to apply SBS UI mirroring if visible
        // Toolbar menu button state is updated immediately in onClick above
        binding.root.invalidate()
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
    }
}