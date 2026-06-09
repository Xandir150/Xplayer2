package com.teleteh.xplayer2.player

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.teleteh.xplayer2.R

/**
 * Glasses-only menu mirror shown on the external (glasses) display while the user is in the main
 * section. It RENDERS the phone's current list and highlights the focused row — the phone stays the
 * single source of truth, so navigating there (touch / D-pad / head-nod) keeps the glasses selection
 * in sync. MainActivity pushes state via [render] whenever the list or focus changes.
 *
 * A slim status bar (top) flashes the head-gesture that just fired (▲▼ / ◀▶ / OK / BACK) and shows
 * the glasses temperature + phone battery — fed by [imuProvider].
 *
 * The layout wraps content in [com.teleteh.xplayer2.ui.SbsMirrorLayout], which duplicates it per-eye
 * on the 3D (ultra-wide) panel and passes through unchanged on a normal external display.
 */
class MenuMirrorPresentation(
    context: Context,
    display: Display,
    /** Raw IMU debug sample [gx,gy,gz, ax,ay,az, tempRaw] for the on-glasses HUD, or null. */
    private val imuProvider: (() -> FloatArray?)? = null,
) : Presentation(context, display) {

    /** One menu row: primary [title], optional [subtitle], optional source [iconRes] and [chip]. */
    data class Row(
        val title: String,
        val subtitle: String? = null,
        val iconRes: Int? = null,
        val chip: String? = null,
    )

    private var titleView: TextView? = null
    private var listView: LinearLayout? = null
    private var scrollView: ScrollView? = null

    // Last state pushed before the views existed; applied once in onCreate.
    private var pendingTitle: String? = null
    private var pendingRows: List<Row> = emptyList()
    private var pendingSelected: Int = -1
    private var hasPending = false

    // --- Status bar (gesture flash + temp/battery) ---
    private var hudImu: TextView? = null
    private var hudStatus: TextView? = null
    private var battPct = -1
    private var battFrame = 0
    @Volatile private var gestureLabel = ""   // last fired head-gesture, flashed briefly in the bar
    private var gestureLabelAt = 0L
    private val hudHandler = Handler(Looper.getMainLooper())
    private val hudTick = object : Runnable {
        override fun run() { updateHud(); hudHandler.postDelayed(this, 50) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_menu)
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes = window?.attributes?.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        titleView = findViewById(R.id.menuTitle)
        listView = findViewById(R.id.menuList)
        scrollView = findViewById(R.id.menuScroll)
        hudImu = findViewById(R.id.hudImu)
        hudStatus = findViewById(R.id.hudStatus)
        hideSystemBars()
        if (hasPending) { render(pendingTitle, pendingRows, pendingSelected); hasPending = false }
    }

    override fun onStart() {
        super.onStart()
        hudHandler.removeCallbacks(hudTick)
        hudHandler.post(hudTick)
    }

    override fun onStop() {
        super.onStop()
        hudHandler.removeCallbacks(hudTick)
    }

    /** Push the current list + highlighted index. Safe to call before the views are created. */
    fun render(title: String?, rows: List<Row>, selectedIndex: Int) {
        val list = listView
        if (list == null) {
            pendingTitle = title; pendingRows = rows; pendingSelected = selectedIndex; hasPending = true
            return
        }
        titleView?.text = title ?: ""
        list.removeAllViews()
        val ctx = list.context
        var selectedView: View? = null
        rows.forEachIndexed { i, row ->
            val selected = i == selectedIndex
            val rowView = buildRow(ctx, row, selected)
            list.addView(rowView)
            if (selected) selectedView = rowView
        }
        val sv = scrollView
        val sel = selectedView
        if (sv != null && sel != null) {
            sv.post { sv.scrollTo(0, (sel.top - sv.height / 3).coerceAtLeast(0)) }
        }
    }

    /** Flash the head-gesture that just fired (OK / BACK / ▲▼ / ◀▶) in the HUD for ~1.2 s, so the
     *  user can see which way a tilt/nod/turn was interpreted and spot false triggers. */
    fun showGesture(label: String) {
        gestureLabel = label
        gestureLabelAt = System.nanoTime()
    }

    // --- HUD ---

    private fun updateHud() {
        val hud = hudImu ?: return
        val now = System.nanoTime()
        val showing = gestureLabel.isNotEmpty() && now - gestureLabelAt < 1_200_000_000L
        hud.text = if (showing) gestureLabel else ""
        hudStatus?.text = statusText(imuProvider?.invoke()?.getOrNull(6))
    }

    private fun statusText(tempRaw: Float?): String {
        if (battFrame++ % 20 == 0) battPct = readBattery()
        val t = tempRaw?.let { "🌡 ${"%.0f".format(it / 132.48f + 25f)}°" } ?: "🌡 —"
        val b = if (battPct >= 0) "🔋 $battPct%" else "🔋 —"
        return "$t  $b"
    }

    private fun readBattery(): Int = try {
        (getContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) { -1 }

    private fun buildRow(ctx: Context, row: Row, selected: Boolean): View {
        val rootRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
            if (selected) background = selectedBackground(ctx)
        }

        row.iconRes?.let { res ->
            val s = dp(ctx, 32)
            // VK / OK / Yandex Disk are square app-store icons — round their corners so they read as
            // proper app icons. The other source glyphs (local/network/unknown) stay square.
            val isAppIcon = res == R.drawable.ic_source_vk ||
                res == R.drawable.ic_source_ok ||
                res == R.drawable.ic_source_yadisk ||
                res == R.drawable.ic_source_mailru
            val iconView = if (isAppIcon) {
                ShapeableImageView(ctx).apply {
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                        .setAllCorners(CornerFamily.ROUNDED, s * 0.22f)
                        .build()
                }
            } else {
                ImageView(ctx)
            }
            rootRow.addView(iconView.apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(ctx, 16) }
            })
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = row.title
            setTextColor(if (selected) Color.WHITE else 0xFFCCCCCC.toInt())
            textSize = 22f
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!row.subtitle.isNullOrBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = row.subtitle
                setTextColor(0xFF8A8A8A.toInt())
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        rootRow.addView(textCol)

        if (!row.chip.isNullOrBlank()) {
            rootRow.addView(TextView(ctx).apply {
                text = row.chip
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 3))
                background = chipBackground(ctx)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(ctx, 12) }
            })
        }
        return rootRow
    }

    private fun selectedBackground(ctx: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 10).toFloat()
        setColor(0x33FFEB3B)
        setStroke(dp(ctx, 2), 0xFFFFEB3B.toInt())
    }

    private fun chipBackground(ctx: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 12).toFloat()
        setColor(0xFF2196F3.toInt()) // Material blue, matching the phone's stereo chip
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        val w = window ?: return
        val decor = w.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(w, false)
            val controller = WindowInsetsControllerCompat(w, decor)
            controller.hide(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
