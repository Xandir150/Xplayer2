package com.teleteh.xplayer2.ui.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.network.DlnaBrowser
import com.teleteh.xplayer2.data.network.DlnaDiscovery
import com.teleteh.xplayer2.data.network.NetworkItem
import com.teleteh.xplayer2.data.network.SmbStorage
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.VkClubActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils
import kotlinx.coroutines.launch

class NetworkFragment : Fragment(R.layout.fragment_network) {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: NetworkAdapter
    private lateinit var smbStorage: SmbStorage
    private val items = mutableListOf<NetworkItem>()
    private val discovery = DlnaDiscovery()
    private val dlnaBrowser = DlnaBrowser()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var currentDlnaControlUrl: String? = null
    private var currentDlnaDeviceLocation: String? = null
    private val dlnaBackStack = ArrayDeque<String>() // container IDs
    private val discoveredDevices = mutableListOf<NetworkItem.DlnaDevice>()
    private lateinit var openDocLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Local-file picker (moved here from the old Files tab — Files and Network are now one
        // "Sources" tab). Persists read permission so the file can be reopened from Recent.
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
        super.onViewCreated(view, savedInstanceState)

        smbStorage = SmbStorage(requireContext())
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenFile)
            ?.setOnClickListener { openDocLauncher.launch(arrayOf("video/*")) }

        val etUrl: EditText = view.findViewById(R.id.etUrl)
        val btnOpen: Button = view.findViewById(R.id.btnOpenUrl)
        rv = view.findViewById(R.id.rvNetwork)
        val fab: FloatingActionButton = view.findViewById(R.id.fabAddShare)

        adapter = NetworkAdapter(
            onClick = { item -> onItemClick(item) },
            onDelete = { share ->
                smbStorage.remove(share.name)
                reloadShares()
            }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS

        // "Hughey" — a VK group's 3D films. Shown by default only on Russian; on other locales it
        // stays hidden until the user "adds" it by typing `hughey` into the URL field below — the
        // choice is then persisted, like an added source.
        val btnHughey = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHughey)
        val lang = resources.configuration.locales.takeIf { it.size() > 0 }?.get(0)?.language
        val hugheyPrefs = requireContext().getSharedPreferences("hughey", Context.MODE_PRIVATE)
        fun refreshHughey() {
            btnHughey.visibility =
                if (lang == "ru" || hugheyPrefs.getBoolean("unlocked", false)) View.VISIBLE else View.GONE
        }
        refreshHughey()
        btnHughey.setOnClickListener {
            startActivity(Intent(requireContext(), VkClubActivity::class.java).apply {
                putExtra(VkClubActivity.EXTRA_OWNER_ID, "-225720479")
                putExtra(VkClubActivity.EXTRA_TITLE_FILTER, "3D")
                putExtra(VkClubActivity.EXTRA_TITLE, "Hughey")
                putExtra(
                    VkClubActivity.EXTRA_BOOSTY_URL,
                    "https://boosty.to/hugheyvr/bundle/f36c08bd-634e-49e9-bb7a-e2948c30d668?isFromShowcasePreview=true"
                )
            })
        }

        fun tryOpen(text: String?) {
            val raw = text?.trim()
            if (raw.isNullOrBlank()) {
                Toast.makeText(requireContext(), R.string.network_enter_url_error, Toast.LENGTH_SHORT).show()
                return
            }
            if (raw.equals("hughey", ignoreCase = true)) {
                // Magic word: reveal + remember the Hughey shortcut instead of treating it as a URL.
                hugheyPrefs.edit().putBoolean("unlocked", true).apply()
                refreshHughey()
                etUrl.setText("")
                Toast.makeText(requireContext(), R.string.network_hughey_added, Toast.LENGTH_SHORT).show()
                return
            }
            val uri = normalizeToUri(raw)
            if (uri == null) {
                Toast.makeText(requireContext(), R.string.network_url_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            val i = Intent(requireContext(), PlayerActivity::class.java)
            i.data = uri
            DisplayUtils.startOnBestDisplay(requireActivity(), i)
        }

        btnOpen.setOnClickListener { tryOpen(etUrl.text?.toString()) }
        btnOpen.isFocusable = true
        btnOpen.isFocusableInTouchMode = true
        btnOpen.isLongClickable = false
        btnOpen.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.requestFocus(); v.performClick(); true
            } else false
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryOpen(etUrl.text?.toString())
                true
            } else false
        }
        etUrl.isFocusable = true
        etUrl.isFocusableInTouchMode = true
        etUrl.isLongClickable = false
        etUrl.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.requestFocus(); v.performClick(); true
            } else false
        }

        fab.setOnClickListener { showAddSmbDialog() }
        fab.isFocusable = true
        fab.isFocusableInTouchMode = true
        fab.isLongClickable = false
        fab.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.requestFocus(); v.performClick(); true
            } else false
        }

        // Initial content: saved SMB shares
        reloadShares()

        // Start discovery with multicast lock
        startDiscovery()

        // Back navigation within DLNA
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (currentDlnaControlUrl != null) {
                val control = currentDlnaControlUrl
                if (control != null && dlnaBackStack.isNotEmpty()) {
                    val parentId = dlnaBackStack.removeLast()
                    viewLifecycleOwner.lifecycleScope.launch {
                        browseAndShow(control, parentId)
                    }
                } else {
                    // Exit DLNA browsing: restore initial list
                    currentDlnaControlUrl = null
                    currentDlnaDeviceLocation = null
                    rebuildInitialList()
                }
            } else {
                // Not handling, let system handle default back
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }


    }

    override fun onStop() {
        super.onStop()
        releaseMulticast()
    }

    private fun onItemClick(item: NetworkItem) {
        when (item) {
            is NetworkItem.SmbShare -> {
                // For now, just copy URI to URL field so user can try open (later: SMB browser)
                view?.findViewById<EditText>(R.id.etUrl)?.setText(item.uri)
            }

            is NetworkItem.DlnaUp -> {
                val control = currentDlnaControlUrl
                if (control != null && dlnaBackStack.isNotEmpty()) {
                    val parentId = dlnaBackStack.removeLast()
                    viewLifecycleOwner.lifecycleScope.launch {
                        browseAndShow(control, parentId)
                    }
                } else {
                    // No parent – exit DLNA to device list
                    currentDlnaControlUrl = null
                    currentDlnaDeviceLocation = null
                    rebuildInitialList()
                }
            }

            is NetworkItem.DlnaDevice -> {
                // Resolve control URL and browse root
                browseDlnaDevice(item)
            }

            is NetworkItem.DlnaContainer -> {
                browseDlnaContainer(item)
            }

            is NetworkItem.DlnaMedia -> {
                // Play media url with better title from DIDL metadata
                playMediaUrl(item.url, item.title)
            }
        }
    }

    private fun reloadShares() {
        val shares = smbStorage.getAll()
        items.removeAll { it is NetworkItem.SmbShare }
        items.addAll(0, shares)
        adapter.submitList(items.toList())
    }

    private fun showAddSmbDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val etName = EditText(ctx).apply {
            hint = getString(R.string.network_smb_name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etUri = EditText(ctx).apply {
            hint = getString(R.string.network_smb_uri)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(etName)
        container.addView(etUri)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.network_add_share)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_add) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val uri = etUri.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || !uri.startsWith("smb://", true)) {
                    Toast.makeText(ctx, R.string.error_invalid_input, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                smbStorage.addOrUpdate(name, uri)
                reloadShares()
            }
            .show()
    }

    private fun startDiscovery() {
        acquireMulticast()
        discovery.discover(viewLifecycleOwner.lifecycleScope) { device ->
            // Append if not already present
            val exists =
                items.any { it is NetworkItem.DlnaDevice && it.location == device.location }
            if (!exists) {
                discoveredDevices.add(device)
                // Only update list if we are not browsing a DLNA device
                if (currentDlnaControlUrl == null) {
                    items.add(device)
                    adapter.submitList(items.toList())
                }
            }
        }
    }

    private fun rebuildInitialList() {
        val shares = smbStorage.getAll()
        items.clear()
        items.addAll(shares)
        items.addAll(discoveredDevices)
        adapter.submitList(items.toList())
    }

    private fun browseDlnaDevice(device: NetworkItem.DlnaDevice) {
        // Clear DLNA nav state
        currentDlnaControlUrl = null
        currentDlnaDeviceLocation = device.location
        dlnaBackStack.clear()
        // Resolve control URL then browse root ("0")
        viewLifecycleOwner.lifecycleScope.launch {
            var control = dlnaBrowser.resolveContentDirectoryControlUrl(device.location)
            if (control == null) {
                // Fallbacks for common servers (e.g., MiniDLNA) when parsing fails
                try {
                    val base =
                        device.location.toUri().buildUpon().path("").build().toString().trimEnd('/')
                    val candidates = listOf("/ctl/ContentDir", "/upnp/control/content_directory")
                    for (c in candidates) {
                        val url = base + c
                        // Try a lightweight GET; many servers return 405 for GET on control, still proves endpoint exists
                        val ok = com.teleteh.xplayer2.util.Net.pingHttp(url)
                        if (ok) {
                            control = url; break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (control == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.dlna_not_found,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            currentDlnaControlUrl = control
            try {
                browseAndShow(control, "0")
            } catch (t: Throwable) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.dlna_browse_failed, t.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun browseDlnaContainer(container: NetworkItem.DlnaContainer) {
        val control = currentDlnaControlUrl ?: container.controlUrl
        // Push current id to backstack if present
        dlnaBackStack.addLast(container.parentId ?: "0")
        viewLifecycleOwner.lifecycleScope.launch {
            browseAndShow(control, container.id)
        }
    }

    private fun browseAndShow(controlUrl: String, objectId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val res = dlnaBrowser.browse(controlUrl, objectId)
            // Replace current DLNA list: show containers then items
            // Keep SMB shares at top
            val shares = smbStorage.getAll()
            val newList = mutableListOf<NetworkItem>()
            // If we are inside DLNA (and have a parent), add Up row first
            if (currentDlnaControlUrl != null && dlnaBackStack.isNotEmpty()) {
                newList.add(NetworkItem.DlnaUp)
            }
            newList.addAll(shares)
            val devLoc = currentDlnaDeviceLocation ?: ""
            res.containers.forEach {
                newList.add(
                    NetworkItem.DlnaContainer(
                        title = it.title,
                        id = it.id,
                        parentId = it.parentId,
                        deviceLocation = devLoc,
                        controlUrl = controlUrl
                    )
                )
            }
            res.items.forEach {
                newList.add(
                    NetworkItem.DlnaMedia(
                        title = it.title,
                        url = it.resUrl,
                        mime = it.mime,
                        deviceLocation = devLoc,
                        controlUrl = controlUrl
                    )
                )
            }
            items.clear()
            items.addAll(newList)
            adapter.submitList(items.toList())
        }
    }

    private fun playMediaUrl(url: String, title: String? = null) {
        try {
            val uri = Uri.parse(url)
            val i = Intent(requireContext(), PlayerActivity::class.java)
            i.data = uri
            if (!title.isNullOrBlank()) {
                i.putExtra(PlayerActivity.EXTRA_TITLE, title)
            }
            DisplayUtils.startOnBestDisplay(requireActivity(), i)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.network_open_failed, e.message), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun acquireMulticast() {
        if (multicastLock == null) {
            val wifi =
                requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("xplayer2-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticast() {
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    private fun normalizeToUri(raw: String): Uri? {
        // Accept http/https, content:// and file paths, and try to add scheme when missing
        val s = raw.trim()
        return try {
            when {
                s.startsWith("http://", true) || s.startsWith("https://", true) -> s.toUri()
                s.startsWith("content://", true) -> s.toUri()
                s.startsWith("file://", true) -> s.toUri()
                s.startsWith("/") -> Uri.fromFile(java.io.File(s))
                else -> {
                    // Try https by default
                    ("https://" + s).toUri()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
