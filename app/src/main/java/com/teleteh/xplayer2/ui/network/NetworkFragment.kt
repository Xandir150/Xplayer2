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
import com.teleteh.xplayer2.data.network.SmbClient
import com.teleteh.xplayer2.data.network.SmbCredentials
import com.teleteh.xplayer2.data.network.SmbLoginRequired
import com.teleteh.xplayer2.data.network.SmbStorage
import com.teleteh.xplayer2.data.network.WebSourceStore
import com.teleteh.xplayer2.data.network.WebSourceType
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.MailCloudActivity
import com.teleteh.xplayer2.ui.VkClubActivity
import com.teleteh.xplayer2.ui.YaDiskActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils
import com.teleteh.xplayer2.util.WebSourceClassifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkFragment : Fragment(R.layout.fragment_network) {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: NetworkAdapter
    private lateinit var smbStorage: SmbStorage
    private lateinit var webSourceStore: WebSourceStore
    private val items = mutableListOf<NetworkItem>()
    private val discovery = DlnaDiscovery()
    private val dlnaBrowser = DlnaBrowser()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var currentDlnaControlUrl: String? = null
    private var currentDlnaDeviceLocation: String? = null
    private val dlnaBackStack = ArrayDeque<String>() // container IDs
    private val smbClient by lazy { SmbClient(requireContext()) }
    private var currentSmbUri: String? = null
    private val smbBackStack = ArrayDeque<String>() // parent folder URIs
    private val discoveredDevices = mutableListOf<NetworkItem.DlnaDevice>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        smbStorage = SmbStorage(requireContext())
        webSourceStore = WebSourceStore(requireContext())

        val etUrl: EditText = view.findViewById(R.id.etUrl)
        val btnOpen: Button = view.findViewById(R.id.btnOpenUrl)
        rv = view.findViewById(R.id.rvNetwork)
        val fab: FloatingActionButton = view.findViewById(R.id.fabAddShare)

        adapter = NetworkAdapter(
            onClick = { item -> onItemClick(item) },
            onDelete = { item ->
                when (item) {
                    is NetworkItem.SmbShare -> smbStorage.remove(item.name)
                    is NetworkItem.WebSource -> webSourceStore.remove(item.url)
                    else -> {}
                }
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
            // Classify the link. Container types (VK playlist/group, Yandex Disk folder) are opened
            // in their browser AND remembered as a Sources row; single videos / direct links play as
            // before. (YaDisk folder-vs-file is settled inside YaDiskActivity — see EXTRA_REMEMBER_URL.)
            val kind = WebSourceClassifier.classify(raw)
            val intent = WebSourceClassifier.openIntent(requireContext(), raw, kind)
            if (intent != null) {
                startActivity(intent)
                etUrl.setText("")
                // A web source may have just been saved → refresh the list so its row shows now.
                if (WebSourceClassifier.isRememberedContainer(kind)) reloadShares()
                return
            }
            // Not a recognized container → play as today (single VK/OK video, direct file, HLS, …).
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
        fun clipboardUrl(): String? {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(requireContext())?.toString()?.trim()
            return t?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("magnet:", true)
            }
        }
        etUrl.isFocusable = true
        etUrl.isFocusableInTouchMode = true
        etUrl.isLongClickable = true   // restore the paste context menu (was off → paste was blocked)
        etUrl.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.requestFocus()
                // Consuming the touch ourselves stops the IME auto-showing on some devices — pop it.
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                v.performClick(); true
            } else false
        }
        // Auto-paste a clipboard URL into the empty field on focus — covers the "can't paste / no
        // keyboard" case some devices hit; the user can still edit or clear it.
        etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && etUrl.text.isNullOrEmpty()) {
                clipboardUrl()?.let { etUrl.setText(it); etUrl.setSelection(it.length) }
            }
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
            if (currentSmbUri != null) {
                smbUp()
            } else if (currentDlnaControlUrl != null) {
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

    override fun onResume() {
        super.onResume()
        // A web source (YaDisk folder, VK playlist/group) can be saved while we're away — notably
        // YaDiskActivity persists a folder only AFTER it confirms the listing, i.e. after we've
        // already returned from tryOpen(). Rebuild on return so the new row shows up. Skip while
        // browsing a DLNA device or an SMB share so we don't drop out of that view.
        if (currentDlnaControlUrl == null && currentSmbUri == null) {
            rebuildInitialList()
        }
    }

    override fun onStop() {
        super.onStop()
        releaseMulticast()
    }

    private fun onItemClick(item: NetworkItem) {
        when (item) {
            is NetworkItem.SmbShare -> {
                smbBackStack.clear()
                browseSmb(item.uri)
            }

            is NetworkItem.SmbUp -> smbUp()

            is NetworkItem.SmbEntryItem -> {
                if (item.isDirectory) {
                    currentSmbUri?.let { smbBackStack.addLast(it) }
                    browseSmb(item.uri)
                } else {
                    playMediaUrl(item.uri, item.title)
                }
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

            is NetworkItem.WebSource -> openWebSource(item)
        }
    }

    /** Re-open a remembered container in the right browser, routed by its type. */
    private fun openWebSource(item: NetworkItem.WebSource) {
        when (item.type) {
            WebSourceType.YADISK_FOLDER ->
                startActivity(Intent(requireContext(), YaDiskActivity::class.java).apply {
                    putExtra(YaDiskActivity.EXTRA_PUBLIC_KEY, item.url)
                })

            WebSourceType.MAILRU_FOLDER ->
                startActivity(Intent(requireContext(), MailCloudActivity::class.java).apply {
                    // MailCloudActivity normalises a full share URL to its weblink.
                    putExtra(MailCloudActivity.EXTRA_PUBLIC_KEY, item.url)
                })

            WebSourceType.VK_PLAYLIST, WebSourceType.VK_GROUP -> {
                // Re-derive owner (+ playlist) from the saved URL so we don't store parsed ids.
                when (val kind = WebSourceClassifier.classify(item.url)) {
                    is WebSourceClassifier.Kind.VkPlaylist ->
                        startActivity(Intent(requireContext(), VkClubActivity::class.java).apply {
                            putExtra(VkClubActivity.EXTRA_OWNER_ID, kind.ownerId)
                            putExtra(VkClubActivity.EXTRA_PLAYLIST_ID, kind.playlistId)
                        })

                    is WebSourceClassifier.Kind.VkGroup ->
                        startActivity(Intent(requireContext(), VkClubActivity::class.java).apply {
                            putExtra(VkClubActivity.EXTRA_OWNER_ID, kind.ownerId)
                        })

                    else -> Toast.makeText(requireContext(), R.string.network_url_invalid, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun reloadShares() {
        // SMB shares and web sources both live in the "initial" (non-DLNA-browsing) list, so a
        // change to either just rebuilds it. Safe because this is never called mid-DLNA-browse.
        rebuildInitialList()
    }

    /** A vertical form of labelled text fields; returns the container and the fields in order. */
    private fun smbForm(vararg specs: Pair<Int, Int>): Pair<View, List<EditText>> {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val fields = specs.map { (hint, type) ->
            EditText(ctx).apply {
                setHint(hint)
                inputType = type
                setSingleLine()
                container.addView(this)
            }
        }
        return android.widget.ScrollView(ctx).apply { addView(container) } to fields
    }

    private fun showAddSmbDialog() {
        val ctx = requireContext()
        val text = InputType.TYPE_CLASS_TEXT
        val (form, f) = smbForm(
            R.string.network_smb_name to text,
            R.string.network_smb_uri to InputType.TYPE_TEXT_VARIATION_URI,
            R.string.network_smb_user to text,
            R.string.network_smb_password to (text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            R.string.network_smb_domain to text,
        )
        com.teleteh.xplayer2.ui.Sbs3dDialog.builder(ctx)
            .setTitle(R.string.network_add_share)
            .setView(form)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_add) { _, _ ->
                val name = f[0].text?.toString()?.trim().orEmpty()
                val uri = f[1].text?.toString()?.trim().orEmpty()
                if (name.isBlank() || !uri.startsWith("smb://", true)) {
                    Toast.makeText(ctx, R.string.error_invalid_input, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                smbStorage.addOrUpdate(name, uri)
                val host = SmbStorage.hostOf(uri)
                val user = f[2].text?.toString()?.trim().orEmpty()
                if (host != null && user.isNotEmpty()) {
                    smbStorage.saveCredentials(
                        host,
                        SmbCredentials(user, f[3].text?.toString().orEmpty(), f[4].text?.toString()?.trim().orEmpty())
                    )
                }
                reloadShares()
            }
            .show()
    }

    /** Ask for a login for [host], save it, and call [retry]. */
    private fun promptSmbLogin(host: String, retry: () -> Unit) {
        val text = InputType.TYPE_CLASS_TEXT
        val (form, f) = smbForm(
            R.string.network_smb_user to text,
            R.string.network_smb_password to (text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            R.string.network_smb_domain to text,
        )
        val saved = smbStorage.credentialsFor(host)
        f[0].setText(saved.user)
        f[2].setText(saved.domain)
        com.teleteh.xplayer2.ui.Sbs3dDialog.builder(requireContext())
            .setTitle(getString(R.string.smb_login_title, host))
            .setView(form)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                smbStorage.saveCredentials(
                    host,
                    SmbCredentials(
                        f[0].text?.toString()?.trim().orEmpty(),
                        f[1].text?.toString().orEmpty(),
                        f[2].text?.toString()?.trim().orEmpty()
                    )
                )
                retry()
            }
            .show()
    }

    private fun browseSmb(uri: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { smbClient.list(uri) }
                currentSmbUri = uri
                val rows = mutableListOf<NetworkItem>(NetworkItem.SmbUp)
                entries.filter { it.isDirectory || SmbClient.isMedia(it.name) }
                    .mapTo(rows) { NetworkItem.SmbEntryItem(it.name, it.uri, it.isDirectory, it.size) }
                items.clear()
                items.addAll(rows)
                adapter.submitList(items.toList())
            } catch (e: SmbLoginRequired) {
                // Stay where we were; the retry re-enters this folder with the new login.
                promptSmbLogin(e.host) { browseSmb(uri) }
            } catch (t: Throwable) {
                if (smbBackStack.isNotEmpty() && currentSmbUri != null) smbBackStack.removeLast()
                Toast.makeText(requireContext(), getString(R.string.smb_open_failed, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun smbUp() {
        if (smbBackStack.isNotEmpty()) {
            browseSmb(smbBackStack.removeLast())
        } else {
            currentSmbUri = null
            rebuildInitialList()
        }
    }

    private fun startDiscovery() {
        acquireMulticast()
        discovery.discover(viewLifecycleOwner.lifecycleScope) { device ->
            // Append if not already present
            val exists =
                discoveredDevices.any { it.location == device.location }
            if (!exists) {
                discoveredDevices.add(device)
                // Only update list if we are not browsing a DLNA device. Rebuild (rather than a bare
                // append) so the order stays SMB + DLNA, then web sources at the end.
                if (currentDlnaControlUrl == null && currentSmbUri == null) {
                    rebuildInitialList()
                }
            }
        }
    }

    private fun rebuildInitialList() {
        currentSmbUri = null
        smbBackStack.clear()
        // Order: SMB shares + discovered DLNA devices, remembered web sources appended at the end.
        // PC Link used to be pinned to the top here as a static row; it has its own tab now
        // (PC-Mirror), which is both the way in and the remote for a running session — so this
        // list is media sources again, and there is one entrance to PC Link rather than two.
        items.clear()
        items.addAll(smbStorage.getAll())
        items.addAll(discoveredDevices)
        items.addAll(webSourceStore.getAll())
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
