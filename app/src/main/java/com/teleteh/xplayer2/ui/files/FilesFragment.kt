package com.teleteh.xplayer2.ui.files

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils

/** The Files page: opens a video from this phone through the system picker. */
class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var openDocLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Persists read permission so the file can be reopened from Recent.
        openDocLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val ctx = requireContext()
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { /* transient read permission still works */ }
            val intent = Intent(ctx, PlayerActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            DisplayUtils.startOnBestDisplay(requireActivity(), intent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnOpenFile).setOnClickListener {
            openDocLauncher.launch(arrayOf("video/*"))
        }
    }
}
