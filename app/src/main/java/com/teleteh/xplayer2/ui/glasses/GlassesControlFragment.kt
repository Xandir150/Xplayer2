package com.teleteh.xplayer2.ui.glasses

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.glasses.XrealOneBrightness
import com.teleteh.xplayer2.data.glasses.XrealOneController
import com.teleteh.xplayer2.data.glasses.XrealOneDimmer
import com.teleteh.xplayer2.data.glasses.XrealOneDisplayConfiguration
import kotlinx.coroutines.launch

/**
 * The Glasses tab: screen mode, brightness and dimming of XREAL One-series glasses.
 *
 * [com.teleteh.xplayer2.ui.MainPagerAdapter] hands this page out only while
 * [XrealOneController.State.available] is true. The page renders the controller's state and sends
 * every change back to it; it keeps no state of its own besides "the slider is being dragged".
 */
class GlassesControlFragment : Fragment(R.layout.fragment_glasses_control) {

    private val controller get() = XrealOneController.get(requireContext())

    private var draggingBrightness = false

    private class Tile(val card: MaterialCardView, val mode: XrealOneDisplayConfiguration)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tiles = listOf(
            Tile(view.findViewById(R.id.tileHd60), XrealOneDisplayConfiguration.HD_60),
            Tile(view.findViewById(R.id.tileHd90), XrealOneDisplayConfiguration.HD_90),
            Tile(view.findViewById(R.id.tileHd120), XrealOneDisplayConfiguration.HD_120),
            Tile(view.findViewById(R.id.tileSbs60), XrealOneDisplayConfiguration.SIDE_BY_SIDE_60),
        )
        for (tile in tiles) {
            tile.card.findViewById<TextView>(R.id.tileTitle).text = if (tile.mode.isStereo) "3D" else "2D"
            tile.card.findViewById<TextView>(R.id.tileDetail).text = detail(tile.mode)
            tile.card.setOnClickListener { controller.setDisplayConfiguration(tile.mode) }
        }

        val slider = view.findViewById<Slider>(R.id.sliderBrightness)
        val brightnessValue = view.findViewById<TextView>(R.id.tvBrightnessValue)
        slider.addOnChangeListener { _, value, _ -> brightnessValue.text = brightnessText(value.toInt()) }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingBrightness = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingBrightness = false
                // The command goes out when the finger lifts, not on every step of the drag.
                controller.setBrightness(slider.value.toInt())
            }
        })

        val dimmerButtons = mapOf(
            R.id.btnDimmerLightest to XrealOneDimmer.LIGHTEST,
            R.id.btnDimmerMiddle to XrealOneDimmer.MIDDLE,
            R.id.btnDimmerDimmest to XrealOneDimmer.DIMMEST,
        )
        val dimmerGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleDimmer)
        dimmerGroup.addOnButtonCheckedListener { _, id, checked ->
            // render() checks buttons too, but only a level that differs from the state is a command.
            val level = dimmerButtons[id]
            if (checked && level != null && controller.state.value.dimmer != level) controller.setDimmer(level)
        }

        view.findViewById<View>(R.id.cardFailure).setOnClickListener { controller.dismissFailure() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { render(view, it, tiles, dimmerGroup, dimmerButtons) }
            }
        }
    }

    private fun render(
        view: View,
        state: XrealOneController.State,
        tiles: List<Tile>,
        dimmerGroup: MaterialButtonToggleGroup,
        dimmerButtons: Map<Int, XrealOneDimmer>,
    ) {
        view.findViewById<TextView>(R.id.tvGlassesName).text = state.name

        view.findViewById<CircularProgressIndicator>(R.id.progressApplying).visibility =
            if (state.applying) View.VISIBLE else View.GONE
        val badge = view.findViewById<TextView>(R.id.tvModeBadge)
        val mode = state.displayConfiguration
        badge.visibility = if (!state.applying && mode != null) View.VISIBLE else View.GONE
        if (mode != null) badge.text = if (mode.isStereo) "3D" else "2D"

        for (tile in tiles) tile.card.isChecked = state.displayConfiguration == tile.mode
        for (tile in tiles) tile.card.isEnabled = !state.applying

        val slider = view.findViewById<Slider>(R.id.sliderBrightness)
        if (!draggingBrightness) {
            val level = state.brightness ?: DEFAULT_BRIGHTNESS
            slider.value = level.toFloat()
            view.findViewById<TextView>(R.id.tvBrightnessValue).text = brightnessText(level)
        }

        val checkedId = dimmerButtons.entries.firstOrNull { it.value == state.dimmer }?.key
        if (checkedId != null) dimmerGroup.check(checkedId) else dimmerGroup.clearChecked()
        dimmerButtons.keys.forEach { view.findViewById<View>(it).isEnabled = !state.applying }

        view.findViewById<View>(R.id.cardFailure).visibility =
            if (state.failure != null) View.VISIBLE else View.GONE

        val id = view.findViewById<TextView>(R.id.tvGlassesId)
        id.visibility = if (state.glassesId.isNullOrEmpty()) View.GONE else View.VISIBLE
        id.text = getString(R.string.xreal_id_label, state.glassesId.orEmpty())
    }

    private fun brightnessText(level: Int) =
        getString(R.string.xreal_brightness_value, level, XrealOneBrightness.RANGE.last)

    private fun detail(mode: XrealOneDisplayConfiguration): String = when (mode) {
        XrealOneDisplayConfiguration.HD_60 -> getString(R.string.xreal_detail_hd, 60)
        XrealOneDisplayConfiguration.HD_90 -> getString(R.string.xreal_detail_hd, 90)
        XrealOneDisplayConfiguration.HD_120 -> getString(R.string.xreal_detail_hd, 120)
        XrealOneDisplayConfiguration.SIDE_BY_SIDE_60 -> getString(R.string.xreal_detail_sbs, 60)
    }

    private companion object {
        const val DEFAULT_BRIGHTNESS = 5
    }
}
