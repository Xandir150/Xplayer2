package com.teleteh.xplayer2

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.teleteh.xplayer2.databinding.ActivityMainBinding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import com.teleteh.xplayer2.ui.MainPagerAdapter
import com.google.android.material.button.MaterialButton
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        // Use MaterialToolbar as ActionBar to fix menu overlap under title on older Android
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        // Ensure title is shown alongside a compact logo
        toolbar.title = getString(R.string.app_name)
        // Create a scaled bitmap for the logo (~18dp height) so the title remains visible
        val bmp = BitmapFactory.decodeResource(resources, R.drawable.hero_image_for_block_g)
        val targetDp = 24f
        val density = resources.displayMetrics.density
        val targetHeightPx = (targetDp * density).toInt().coerceAtLeast(1)
        val aspect = if (bmp.height != 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
        val targetWidthPx = (targetHeightPx * aspect).toInt().coerceAtLeast(1)
        val scaledBmp = Bitmap.createScaledBitmap(bmp, targetWidthPx, targetHeightPx, true)
        val logoDrawable = BitmapDrawable(resources, scaledBmp)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(true)
            setDisplayShowHomeEnabled(true)
            setDisplayUseLogoEnabled(true)
            setLogo(logoDrawable)
        }
        // Also set on the toolbar to guarantee layout alongside title on all OEMs
        toolbar.logo = logoDrawable

        val viewPager: ViewPager2 = binding.viewPager
        viewPager.adapter = MainPagerAdapter(this)

        val tabTitles = listOf("Недавние", "Файлы", "Сеть")
        TabLayoutMediator(binding.tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun hideSystemBars() {
        // Edge-to-edge and hide system bars on modern Android
        WindowCompat.setDecorFitsSystemWindows(window, false)
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