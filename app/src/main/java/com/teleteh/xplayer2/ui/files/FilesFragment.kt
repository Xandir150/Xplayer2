package com.teleteh.xplayer2.ui.files

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils

class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var openDocLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val ctx = requireContext()
            // Persist read permission so we can reopen from Recent later
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Ignore if not persistable; we still have transient read permission
            }
            val intent = Intent(ctx, PlayerActivity::class.java)
            intent.data = uri
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DisplayUtils.startOnBestDisplay(requireActivity(), intent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val button = view.findViewById<Button>(R.id.btnOpen) ?: return
        button.setOnClickListener { openDocLauncher.launch(arrayOf("video/*")) }
        button.isFocusable = true
        button.isFocusableInTouchMode = true
        button.isLongClickable = false

        // Single-tap activation so touch and D-pad behave the same on this single-control screen.
        button.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.requestFocus(); v.performClick(); true
            } else false
        }

        // Files screen has just one visible control. Block downward focus escape so the
        // D-pad does not jump to hidden targets, and route UP back to the tab strip.
        button.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val activity = requireActivity()
                    val tabLayout = activity.findViewById<TabLayout>(R.id.tabLayout)
                    val viewPager = activity.findViewById<ViewPager2>(R.id.viewPager)
                    val activeTabView = tabLayout?.getTabAt(viewPager?.currentItem ?: 0)?.view
                    (activeTabView ?: tabLayout)?.requestFocus()
                    true
                }
                else -> false
            }
        }
    }
}
