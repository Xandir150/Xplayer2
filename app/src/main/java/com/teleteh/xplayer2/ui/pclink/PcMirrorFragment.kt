package com.teleteh.xplayer2.ui.pclink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PcLinkSession
import com.teleteh.xplayer2.ui.network.PcConnectActivity

/**
 * **The PC-Mirror tab — PC Link's own screen**, next to Recent and Sources.
 *
 * The desktop is not a film, and it does not belong inside the film player's remote: it has its own
 * way in (find a PC, compare six digits) and its own things to say while it runs (how many frames
 * are arriving, how many megabits, where the sound is going). So it gets a tab.
 *
 * Two states, and the tab picks between them on its own clock:
 *
 * * **nothing streaming** — the connect card, whose button opens [PcConnectActivity]. The pairing
 *   ceremony stays exactly where it was: it is security-sensitive, already tested, and reproducing
 *   it here would have bought nothing but a second copy to keep right.
 * * **a session running** — the remote. Two live numbers out front, each over a minute of itself;
 *   everything that answers "why isn't it working" behind a shut, remembered door; and a switch
 *   that names where the PC's sound is going rather than claiming to be a mute.
 *
 * The session itself lives in `PlayerActivity` (the picture is on the glasses, and that is the
 * activity holding the panel) and is reached through [PcLinkSession] — see there for why this is a
 * pull on a one-second clock rather than a subscription.
 */
class PcMirrorFragment : Fragment() {

    private val history = PcLinkStatsHistory()
    private val ticker = Handler(Looper.getMainLooper())

    private lateinit var cardConnect: MaterialCardView
    private lateinit var cardSession: MaterialCardView
    private lateinit var tvServerName: TextView
    private lateinit var tvLinkState: TextView
    private lateinit var chevron: ImageView
    private lateinit var detailsBox: LinearLayout
    private lateinit var audioSwitch: SwitchMaterial
    private lateinit var tvAudioHint: TextView
    private lateinit var fpsChip: Chip
    private lateinit var bitrateChip: Chip

    /** The detail rows, built once and then only re-texted — a row per line of [detailLines]. */
    private val detailRows = ArrayList<Pair<TextView, TextView>>()

    private var detailsOpen = false

    /** Set while [applyAudioState] is writing the switch, so its listener doesn't echo back. */
    private var bindingAudioSwitch = false

    private val tick = object : Runnable {
        override fun run() {
            sample()
            ticker.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pc_mirror, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cardConnect = view.findViewById(R.id.cardConnect)
        cardSession = view.findViewById(R.id.cardSession)
        tvServerName = view.findViewById(R.id.tvServerName)
        tvLinkState = view.findViewById(R.id.tvLinkState)
        chevron = view.findViewById(R.id.ivDetailsChevron)
        detailsBox = view.findViewById(R.id.boxDetails)
        // The rows belong to the view that has just been thrown away (a rotation, a tab that was
        // scrolled far enough off the pager to be destroyed): rebuild them into the new box rather
        // than writing text into views nobody is showing.
        detailRows.clear()
        audioSwitch = view.findViewById(R.id.swAudioToGlasses)
        tvAudioHint = view.findViewById(R.id.tvAudioHint)

        fpsChip = Chip(view.findViewById(R.id.chipFps), getString(R.string.pclink_stat_fps), history.fps)
        bitrateChip =
            Chip(view.findViewById(R.id.chipBitrate), getString(R.string.pclink_stat_bitrate), history.mbps)

        view.findViewById<MaterialButton>(R.id.btnFindPc).setOnClickListener {
            startActivity(Intent(requireContext(), PcConnectActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btnDisconnect).setOnClickListener {
            PcLinkSession.end()
            // Don't wait for the next tick to admit it's gone.
            sample()
        }

        detailsOpen = prefs().getBoolean(PREF_DETAILS_OPEN, false)
        applyDetailsOpen(animate = false)
        view.findViewById<View>(R.id.rowDetails).setOnClickListener {
            detailsOpen = !detailsOpen
            prefs().edit().putBoolean(PREF_DETAILS_OPEN, detailsOpen).apply()
            applyDetailsOpen(animate = true)
        }

        audioSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingAudioSwitch) return@setOnCheckedChangeListener
            PcLinkSession.setAudioToGlasses(checked)
            sample()
        }
    }

    override fun onResume() {
        super.onResume()
        // Sampling only while the tab is actually in front: nobody is watching a sparkline they
        // can't see, and the numbers themselves live in the session either way.
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        // What was collected describes seconds that were being watched; splicing across the gap
        // would draw a minute that never happened.
        history.reset()
    }

    /**
     * One reading, on our own clock. Deliberately not driven by the session telling us something
     * changed: a desktop nobody is touching stops changing, and "0 fps" is the answer then.
     */
    private fun sample() {
        if (view == null) return
        val stats = PcLinkSession.stats()
        history.sample(stats, SystemClock.elapsedRealtime())
        if (stats == null) {
            cardConnect.visibility = View.VISIBLE
            cardSession.visibility = View.GONE
            return
        }
        cardConnect.visibility = View.GONE
        cardSession.visibility = View.VISIBLE
        tvServerName.text = stats.serverName
        tvLinkState.setText(
            when (stats.link) {
                PcLinkSession.Link.CONNECTING -> R.string.pclink_state_connecting
                PcLinkSession.Link.STREAMING -> R.string.pclink_state_streaming
                PcLinkSession.Link.RECONNECTING -> R.string.pclink_state_reconnecting
                PcLinkSession.Link.FAILED -> R.string.pclink_state_failed
            }
        )
        // A dash until the second reading: one counter is not a rate, and a made-up zero in the
        // first second of a session would be the one lie this screen exists to avoid.
        val fps = history.fps.latest()
        val mbps = history.mbps.latest()
        fpsChip.render(
            if (fps == null) getString(R.string.pclink_value_none)
            else getString(R.string.pclink_value_fps, "%.0f".format(fps))
        )
        bitrateChip.render(
            if (mbps == null) getString(R.string.pclink_value_none)
            else getString(R.string.pclink_value_mbps, "%.1f".format(mbps))
        )
        applyAudioState(stats)
        if (detailsOpen) applyDetails(stats)
    }

    private fun applyAudioState(stats: PcLinkSession.Stats) {
        bindingAudioSwitch = true
        audioSwitch.isChecked = stats.audioToGlasses
        bindingAudioSwitch = false
        // The switch names the destination; the line under it names the other one, so neither
        // state has to be guessed at.
        tvAudioHint.setText(
            when {
                !stats.audioAvailable -> R.string.pclink_audio_none
                stats.audioToGlasses -> R.string.pclink_audio_hint_glasses
                else -> R.string.pclink_audio_hint_computer
            }
        )
        audioSwitch.isEnabled = stats.audioAvailable
    }

    // --- the door ------------------------------------------------------------------------------

    private fun applyDetailsOpen(animate: Boolean) {
        detailsBox.visibility = if (detailsOpen) View.VISIBLE else View.GONE
        val rotation = if (detailsOpen) 180f else 0f
        if (animate) chevron.animate().rotation(rotation).setDuration(150).start()
        else chevron.rotation = rotation
        // The visible label stays "Details"; a screen reader is told which way the door goes,
        // since the chevron it would otherwise read is decorative.
        view?.findViewById<View>(R.id.rowDetails)?.contentDescription = getString(
            if (detailsOpen) R.string.pclink_details_hide else R.string.pclink_details_show
        )
        if (detailsOpen) PcLinkSession.stats()?.let { applyDetails(it) }
    }

    /** Everything that answers "why isn't it working" — the seven rows behind the door. */
    private fun applyDetails(stats: PcLinkSession.Stats) {
        val lines = detailLines(stats)
        while (detailRows.size < lines.size) detailRows.add(addDetailRow())
        detailRows.forEachIndexed { index, (label, value) ->
            val line = lines.getOrNull(index)
            val row = label.parent as View
            if (line == null) {
                row.visibility = View.GONE
            } else {
                row.visibility = View.VISIBLE
                label.text = line.first
                value.text = line.second
            }
        }
    }

    private fun detailLines(stats: PcLinkSession.Stats): List<Pair<String, String>> {
        val none = getString(R.string.pclink_value_none)
        val format = if (stats.codec != null && stats.width > 0) {
            getString(
                R.string.pclink_value_format,
                stats.codec, stats.width, stats.height, stats.stereo.orEmpty()
            )
        } else {
            none
        }
        val audioOut = if (stats.audioRateHz > 0) {
            getString(
                R.string.pclink_value_audio_format,
                stats.audioRateHz / 1000, stats.audioChannels
            )
        } else {
            none
        }
        return listOf(
            getString(R.string.pclink_stat_latency) to
                getString(R.string.pclink_value_ms, format0(stats.rttMs)),
            getString(R.string.pclink_stat_format) to format,
            getString(R.string.pclink_stat_dropped) to stats.droppedFrames.toString(),
            getString(R.string.pclink_stat_audio_output) to audioOut,
            getString(R.string.pclink_stat_audio_buffer) to
                getString(R.string.pclink_value_ms, stats.audioBufferedMs.toString()),
            getString(R.string.pclink_stat_skew) to
                (stats.audioSkewMs?.let { getString(R.string.pclink_value_ms, it.toString()) } ?: none),
            getString(R.string.pclink_stat_audio_dropouts) to stats.audioDropouts.toString()
        )
    }

    private fun addDetailRow(): Pair<TextView, TextView> {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setTextColor(secondaryTextColor())
        }
        val value = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 13f
            maxLines = 1
        }
        row.addView(label)
        row.addView(value)
        detailsBox.addView(row)
        return label to value
    }

    private fun secondaryTextColor(): Int {
        val out = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, out, true)
        return if (out.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(requireContext(), out.resourceId)
        } else {
            out.data
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One chip: the title, the number, and the minute of itself under it. */
    private class Chip(private val root: View, private val title: String, window: SparklineWindow) {
        private val value: TextView = root.findViewById(R.id.tvChipValue)
        private val sparkline: SparklineView = root.findViewById(R.id.sparkline)

        init {
            root.findViewById<TextView>(R.id.tvChipTitle).text = title
            sparkline.setWindow(window)
        }

        fun render(text: String) {
            value.text = text
            // The label and the number are one fact to a screen reader, and the chart is that same
            // fact drawn again (which is why the chart itself is hidden from it).
            root.contentDescription = "$title: $text"
            sparkline.invalidate()
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val PREFS = "pc_mirror"
        const val PREF_DETAILS_OPEN = "details_open"

        fun format0(value: Float): String = "%.0f".format(value)
    }
}
