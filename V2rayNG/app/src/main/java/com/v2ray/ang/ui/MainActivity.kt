package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.MobileTinaSessionLimiter
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MobileTinaExpiryManager
import com.v2ray.ang.handler.MobileTinaResetManager
import com.v2ray.ang.handler.MobileTinaSubscriptionInfo
import com.v2ray.ang.handler.MobileTinaSubscriptionMarkerManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.MobileTinaImportNormalizer
import com.v2ray.ang.util.MobileTinaIntegrityGuard
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

class MainActivity : HelperBaseActivity(), com.google.android.material.navigation.NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val expiryDataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MobileTinaExpiryManager.ACTION_DATA_CHANGED) return
            normalizeSubscriptionNames()
            setupGroupTab()
            ensureSelectedServerForCurrentSubscription()
            mainViewModel.reloadServerListAsync()
            refreshSelectedServerUi()
            showExpiredSubscriptionToastIfNeeded()
        }
    }
    private var expiryReceiverRegistered = false
    val mainViewModel: MainViewModel by viewModels()

    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private val firstRunPrefs by lazy { getSharedPreferences("mobiletina_first_run", MODE_PRIVATE) }

    private var currentMode = MODE_AUTO
    private var modeInitialized = false
    private var lastSubscriptionRefreshAt = 0L
    private var subscriptionRefreshing = false
    private var pendingSmartVpnPermission = false
    private var pendingSmartVpnAttemptId = 0L
    private var smartConnecting = false
    private var smartConnectionFailed = false
    private var smartCountdownSeconds = 0
    private var smartAttemptId = 0L
    private var lastConnectedPing: String? = null
    private var smartConnectJob: kotlinx.coroutines.Job? = null
    private var smartStartWatchdogJob: kotlinx.coroutines.Job? = null
    private var smartStopWatchdogJob: kotlinx.coroutines.Job? = null
    private var manualConnecting = false
    private var manualPrewarmGuid: String? = null
    private var manualPrewarmJob: kotlinx.coroutines.Job? = null
    private var markerRecoveryJob: kotlinx.coroutines.Job? = null
    private var internetDialog: AlertDialog? = null
    private var firstLaunchDialog: Dialog? = null
    private var manualSubscriptionDialog: AlertDialog? = null
    private val internetDialogHandler = Handler(Looper.getMainLooper())
    private val drawerSecretHandler = Handler(Looper.getMainLooper())
    private var drawerRevealRunnable: Runnable? = null
    private var drawerHideRunnable: Runnable? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val smart = pendingSmartVpnPermission
            val attemptId = pendingSmartVpnAttemptId
            pendingSmartVpnPermission = false
            pendingSmartVpnAttemptId = 0L
            if (result.resultCode == RESULT_OK) {
                if (smart) {
                    if (isSmartAttemptActive(attemptId)) startV2Ray(true, attemptId)
                } else {
                    startV2Ray(false)
                }
            } else if (smart) {
                markSmartConnectFailed(attemptId)
            } else {
                manualConnecting = false
                refreshSelectedServerUi()
            }
        }

    private val requestFirstRunCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestFirstRunVpnPermissionOnly()
        }

    private val requestFirstRunVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            markFirstRunPermissionCompleted()
        }

    private val requestActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) restartV2Ray()
            if (SettingsChangeManager.consumeSetupGroupTab()) setupGroupTab()
            setupGroupTab()
            refreshSelectedServerUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Defense in depth: keep an activity-level integrity gate in addition to the
        // process-level Application check so a patched startup path is not sufficient.
        MobileTinaIntegrityGuard.verify(this)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.mobiletina_app_name))

        setupModeTabs()
        setupGroupPager()
        setupDrawer()
        setupActions()
        setupViewModel()
        normalizeSubscriptionNames()
        setupGroupTab()
        ensureSelectedServerForCurrentSubscription()
        mainViewModel.reloadServerListAsync()
        refreshSelectedServerUi()

        ContextCompat.registerReceiver(
            this,
            expiryDataChangedReceiver,
            IntentFilter(MobileTinaExpiryManager.ACTION_DATA_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        expiryReceiverRegistered = true
        MobileTinaExpiryManager.recoverPending(this)

        continueInitialLaunch()
    }

    private fun setupModeTabs() {
        binding.btnModeManual.setOnClickListener { setMode(MODE_MANUAL) }
        binding.btnModeAuto.setOnClickListener { setMode(MODE_AUTO) }
        binding.modeContainer.setOnModeSwipeListener { direction ->
            if (direction > 0) setMode(MODE_MANUAL) else setMode(MODE_AUTO)
        }
        setMode(MODE_AUTO)
    }

    private fun setMode(mode: Int, updateTab: Boolean = true) {
        val requestedMode = mode.coerceIn(MODE_AUTO, MODE_MANUAL)
        if (modeInitialized && currentMode == requestedMode) return
        currentMode = requestedMode
        modeInitialized = true
        binding.autoPanel.visibility = if (currentMode == MODE_AUTO) View.VISIBLE else View.GONE
        binding.manualPanel.visibility = if (currentMode == MODE_MANUAL) View.VISIBLE else View.GONE
        updateModeSelector()
        refreshSelectedServerUi()
    }

    private fun updateModeSelector() {
        val manualSelected = currentMode == MODE_MANUAL
        val autoSelected = currentMode == MODE_AUTO
        binding.btnModeManual.isSelected = manualSelected
        binding.btnModeAuto.isSelected = autoSelected

        fun style(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
            button.animate().cancel()
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.mobiletina_mode_selected else R.color.mobiletina_mode_unselected
                )
            )
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.mobiletina_mode_text_selected else R.color.mobiletina_mode_text_unselected
                )
            )
            button.animate()
                .scaleX(if (selected) 1f else 0.98f)
                .scaleY(if (selected) 1f else 0.98f)
                .alpha(if (selected) 1f else 0.9f)
                .setDuration(MODE_SWITCH_ANIMATION_MS)
                .start()
        }
        style(binding.btnModeManual, manualSelected)
        style(binding.btnModeAuto, autoSelected)
    }

    private fun setupGroupPager() {
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = false
    }

    private fun setupDrawer() {
        val toggle = object : ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        ) {
            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                // NavigationMenuItemView can re-bind its CheckedTextView after the first
                // layout pass. Re-apply the absolute left alignment whenever the drawer
                // opens so RTL locales cannot move Persian labels to the far right.
                binding.navView.post { forceLtrTree(binding.navView) }
            }
        }
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        binding.navView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        binding.navView.textDirection = View.TEXT_DIRECTION_LTR
        binding.navView.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        binding.navView.menu.findItem(R.id.mobiletina_subscriptions)?.apply {
            title = r.a(4)
            isVisible = false
        }
        binding.navView.post { forceLtrTree(binding.navView) }
        installHiddenSubscriptionsEntry()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    moveTaskToBack(false)
                }
            }
        })
    }

    private fun forceLtrTree(view: View) {
        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
        view.textDirection = View.TEXT_DIRECTION_LTR
        view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (view is TextView) {
            // LEFT is intentional here: START resolves back to the right on some
            // OEM builds when the TextView content is Persian.
            view.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                forceLtrTree(view.getChildAt(index))
            }
        }
    }

    private fun installHiddenSubscriptionsEntry() {
        val logo = binding.navView.getHeaderView(0)
            .findViewById<ImageView>(R.id.mobiletina_nav_logo)
        logo.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelDrawerReveal(view)
                    val reveal = Runnable {
                        drawerRevealRunnable = null
                        if (!isFinishing && !isDestroyed && view.isAttachedToWindow) {
                            revealSubscriptionsEntry()
                        }
                    }
                    drawerRevealRunnable = reveal
                    view.postDelayed(reveal, DRAWER_SUBSCRIPTION_HOLD_MS)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelDrawerReveal(view)
            }
            true
        }
    }

    private fun cancelDrawerReveal(view: View? = null) {
        drawerRevealRunnable?.let { runnable ->
            (view ?: binding.navView.getHeaderView(0)
                .findViewById<ImageView>(R.id.mobiletina_nav_logo)).removeCallbacks(runnable)
        }
        drawerRevealRunnable = null
    }

    private fun revealSubscriptionsEntry() {
        val item = binding.navView.menu.findItem(R.id.mobiletina_subscriptions) ?: return
        item.isVisible = true
        binding.navView.post { forceLtrTree(binding.navView) }
        toast(r.a(1))

        drawerHideRunnable?.let(drawerSecretHandler::removeCallbacks)
        val hide = Runnable {
            drawerHideRunnable = null
            item.isVisible = false
        }
        drawerHideRunnable = hide
        drawerSecretHandler.postDelayed(hide, DRAWER_SUBSCRIPTION_VISIBLE_MS)
    }

    private fun setupActions() {
        binding.fabAuto.setOnClickListener {
            binding.fabAuto.animate().cancel()
            binding.fabAuto.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70L).withEndAction {
                binding.fabAuto.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }.start()
            smartConnectAndStart()
        }
        binding.btnSmartConnect.setOnClickListener { smartConnectAndStart() }
        binding.fab.setOnClickListener { handleManualFabAction() }
        binding.tvAutoPing.setOnClickListener { handlePingClick() }
        binding.manualSelectedRow.setOnClickListener { handlePingClick() }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { content ->
            if (mainViewModel.isRunning.value == true && !smartConnecting) {
                lastConnectedPing = Regex("(\\d+)").find(content.orEmpty())?.groupValues?.getOrNull(1)
            }
            refreshSelectedServerUi()
        }
        mainViewModel.updateListAction.observe(this) {
            ensureSelectedServerForCurrentSubscription()
            refreshSelectedServerUi()
        }
        mainViewModel.realPingFinishedAction.observe(this) {
            refreshSelectedServerUi()
            refreshSubscriptionCard()
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            if (isRunning == true) {
                manualConnecting = false
                smartConnectJob?.cancel()
                smartConnectJob = null
                smartStartWatchdogJob?.cancel()
                smartStartWatchdogJob = null
                pendingSmartVpnPermission = false
                pendingSmartVpnAttemptId = 0L
                smartConnecting = false
                smartConnectionFailed = false
                smartCountdownSeconds = 0
                MobileTinaSessionLimiter.schedule(this)
            } else {
                MobileTinaSessionLimiter.cancel(this)
                lastConnectedPing = null
            }
            refreshSelectedServerUi()
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        val counts = groups.associate { group ->
            group.id to MmkvManager.decodeServerList(group.id).size
        }
        val structureChanged = groupPagerAdapter.update(groups)

        if (groups.isEmpty()) {
            binding.tabGroup.visibility = View.GONE
            refreshSelectedServerUi()
            return
        }
        binding.tabGroup.visibility = View.VISIBLE

        if (structureChanged || tabMediator == null) {
            tabMediator?.detach()
            tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
                val group = groupPagerAdapter.groups[position]
                val textView = TextView(this).apply {
                    setPadding(22, 14, 22, 14)
                    maxLines = 1
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorTextPrimary))
                }
                bindGroupTab(textView, tab, group, counts[group.id] ?: 0, installHold = true)
                tab.customView = textView
                tab.tag = group.id
            }.also { it.attach() }
        } else {
            groups.forEachIndexed { index, group ->
                val tab = binding.tabGroup.getTabAt(index) ?: return@forEachIndexed
                val textView = tab.customView as? TextView ?: return@forEachIndexed
                bindGroupTab(textView, tab, group, counts[group.id] ?: 0, installHold = false)
                tab.tag = group.id
            }
        }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.let { if (it >= 0) it else 0 }
        val targetGroup = groups[targetIndex]
        if (mainViewModel.subscriptionId != targetGroup.id) {
            mainViewModel.subscriptionIdChanged(targetGroup.id)
        }
        binding.viewPager.setCurrentItem(targetIndex, false)
        ensureSelectedServerForCurrentSubscription()
        refreshSubscriptionCard()
    }

    private fun bindGroupTab(
        textView: TextView,
        tab: com.google.android.material.tabs.TabLayout.Tab,
        group: com.v2ray.ang.dto.GroupMapItem,
        count: Int,
        installHold: Boolean
    ) {
        textView.text = "${group.remarks} ($count)"
        if (installHold) installManualSubscriptionHold(textView, tab, group.id)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installManualSubscriptionHold(
        header: TextView,
        tab: com.google.android.material.tabs.TabLayout.Tab,
        subscriptionId: String
    ) {
        var pendingReveal: Runnable? = null
        header.isClickable = true
        header.isFocusable = true
        header.setOnClickListener { tab.select() }
        header.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingReveal?.let(view::removeCallbacks)
                    val reveal = Runnable {
                        pendingReveal = null
                        if (
                            currentMode == MODE_MANUAL &&
                            !isFinishing &&
                            !isDestroyed &&
                            view.isAttachedToWindow
                        ) {
                            showManualSubscriptionQr(subscriptionId)
                        }
                    }
                    pendingReveal = reveal
                    view.postDelayed(reveal, MANUAL_SUBSCRIPTION_HOLD_MS)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pendingReveal?.let(view::removeCallbacks)
                    pendingReveal = null
                }
            }
            false
        }
    }

    private fun showManualSubscriptionQr(subscriptionId: String) {
        if (currentMode != MODE_MANUAL || manualSubscriptionDialog?.isShowing == true) return
        val subscription = MmkvManager.decodeSubscription(subscriptionId) ?: return
        val link = subscription.url.trim()
        if (!r.b(link)) return
        val bitmap = QRCodeDecoder.createQRCode(link) ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 12)
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(-1, 520)
            })
            addView(Button(this@MainActivity).apply {
                text = r.a(5)
                setOnClickListener { Utils.setClipboard(this@MainActivity, link) }
            })
        }

        manualSubscriptionDialog = AlertDialog.Builder(this)
            .setTitle(subscription.remarks.ifBlank { r.a(4) })
            .setView(container)
            .setNegativeButton(r.a(6), null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (manualSubscriptionDialog === dialog) manualSubscriptionDialog = null
                }
                dialog.show()
            }
    }

    private fun ensureSelectedServerForCurrentSubscription() {
        val subId = mainViewModel.subscriptionId
        if (subId.isBlank()) return
        val guids = MmkvManager.decodeServerList(subId)
        if (guids.isEmpty()) return
        val selected = MmkvManager.getSelectServer()
        if (selected.isNullOrBlank() || selected !in guids) {
            MmkvManager.setSelectServer(guids.first())
        }
    }

    private fun handleManualFabAction() {
        if (isServiceRunningConfirmed() || manualConnecting) {
            manualConnecting = false
            stopServiceReliably()
            refreshSelectedServerUi()
            return
        }
        if (smartConnecting || manualConnecting) return

        ensureSelectedServerForCurrentSubscription()
        if (MmkvManager.getSelectServer().isNullOrBlank()) {
            mainViewModel.currentServerGuids().singleOrNull()?.let(MmkvManager::setSelectServer)
        }
        manualConnecting = true
        lastConnectedPing = null
        refreshSelectedServerUi()
        requestVpnPermissionAndStart(false)
    }

    private fun smartConnectAndStart() {
        // Use both sources. On a few OEM builds the cross-process MMKV flag or the UI broadcast can
        // arrive late; either positive state must make the automatic FAB behave as a stop button.
        val serviceRunning = runCatching { V2RayServiceManager.isRunning() }.getOrDefault(false) ||
                mainViewModel.isRunning.value == true
        if (serviceRunning || smartConnecting) {
            cancelSmartConnect()
            return
        }

        val attemptId = beginSmartConnectAttempt()
        ensureSelectedServerForCurrentSubscription()
        val immediateServer = mainViewModel.currentServerGuids().singleOrNull()
        if (immediateServer != null) {
            MmkvManager.setSelectServer(immediateServer)
            refreshSelectedServerUi()
            requestVpnPermissionAndStart(true, attemptId)
            return
        }

        smartConnectJob = lifecycleScope.launch {
            val refreshGrace = SMART_SUBSCRIPTION_REFRESH_GRACE_MS
            if (refreshGrace > 0L) {
                withTimeoutOrNull(refreshGrace) {
                    while (subscriptionRefreshing && isActive && isSmartAttemptActive(attemptId)) delay(50L)
                }
            }
            if (!isSmartAttemptActive(attemptId)) return@launch

            val groups = mainViewModel.getSubscriptions(this@MainActivity)
            if (mainViewModel.subscriptionId.isBlank()) {
                val first = groups.firstOrNull()
                if (first != null) mainViewModel.subscriptionIdChanged(first.id)
            } else {
                mainViewModel.reloadServerList()
            }
            ensureSelectedServerForCurrentSubscription()
            if (!isSmartAttemptActive(attemptId)) return@launch

            val serverGuids = mainViewModel.currentServerGuids()
            if (serverGuids.isEmpty()) {
                markSmartConnectFailed(attemptId)
                toast(R.string.title_file_chooser)
                return@launch
            }

            if (serverGuids.size == 1) {
                MmkvManager.setSelectServer(serverGuids.first())
                refreshSelectedServerUi()
                requestVpnPermissionAndStart(true, attemptId)
                return@launch
            }

            val firstBatchId = attemptId * SMART_BATCH_ID_MULTIPLIER
            awaitSmartPingBatch(attemptId, firstBatchId, serverGuids, SMART_FIRST_PING_TIMEOUT_MS)
            if (!isSmartAttemptActive(attemptId)) return@launch

            var best = findBestServer(serverGuids)
            if (best == null) {
                // Some devices need one service warm-up cycle. Retry inside the same click instead
                // of showing a false red failure and requiring the user to press FAB again.
                mainViewModel.cancelRealPing()
                delay(SMART_RETRY_DELAY_MS)
                if (!isSmartAttemptActive(attemptId)) return@launch
                awaitSmartPingBatch(
                    attemptId,
                    firstBatchId + 1L,
                    serverGuids,
                    SMART_RETRY_PING_TIMEOUT_MS
                )
                if (!isSmartAttemptActive(attemptId)) return@launch
                best = findBestServer(serverGuids)
            }

            if (!isSmartAttemptActive(attemptId)) return@launch
            if (best == null) {
                markSmartConnectFailed(attemptId)
                toast(R.string.mobiletina_no_working_server)
                return@launch
            }

            MmkvManager.setSelectServer(best.first)
            refreshSelectedServerUi()
            requestVpnPermissionAndStart(true, attemptId)

            // Sorting the manual list is useful, but it must not delay the actual VPN start.
            withContext(Dispatchers.IO) { mainViewModel.sortByTestResults() }
            if (attemptId == smartAttemptId) mainViewModel.reloadServerList()
        }
    }

    private suspend fun awaitSmartPingBatch(
        attemptId: Long,
        batchId: Long,
        serverGuids: List<String>,
        timeoutMs: Long
    ): Boolean {
        val unresolvedGuids = serverGuids.toMutableSet()
        mainViewModel.testAllRealPingForSmart(batchId)
        return withTimeoutOrNull(timeoutMs) {
            while (isActive && isSmartAttemptActive(attemptId) &&
                mainViewModel.realPingStartedBatchId != batchId
            ) {
                delay(SMART_BATCH_START_POLL_MS)
            }
            if (!isSmartAttemptActive(attemptId) || mainViewModel.realPingStartedBatchId != batchId) {
                return@withTimeoutOrNull false
            }

            var firstPositiveAt = 0L
            while (isActive && isSmartAttemptActive(attemptId)) {
                val resultState = withContext(Dispatchers.IO) {
                    unresolvedGuids.removeAll { guid ->
                        (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) != 0L
                    }
                    val hasPositive = serverGuids.any { guid ->
                        (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) > 0L
                    }
                    unresolvedGuids.isEmpty() to hasPositive
                }
                if (resultState.second && firstPositiveAt == 0L) {
                    firstPositiveAt = SystemClock.elapsedRealtime()
                }
                if (resultState.first ||
                    (firstPositiveAt > 0L &&
                        SystemClock.elapsedRealtime() - firstPositiveAt >= SMART_FAST_SETTLE_MS)
                ) return@withTimeoutOrNull true
                delay(SMART_PING_RESULT_POLL_MS)
            }
            false
        } ?: false
    }

    private suspend fun findBestServer(serverGuids: List<String>): Pair<String, Long>? =
        withContext(Dispatchers.IO) {
            serverGuids.mapNotNull { guid ->
                val ping = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                if (ping > 0L) guid to ping else null
            }.minByOrNull { it.second }
        }

    private fun beginSmartConnectAttempt(): Long {
        smartAttemptId += 1L
        smartStopWatchdogJob?.cancel()
        smartStopWatchdogJob = null
        smartConnectJob?.cancel()
        smartConnectJob = null
        smartStartWatchdogJob?.cancel()
        smartStartWatchdogJob = null
        mainViewModel.cancelRealPing()
        pendingSmartVpnPermission = false
        pendingSmartVpnAttemptId = 0L
        smartConnecting = true
        lastConnectedPing = null
        smartConnectionFailed = false
        smartCountdownSeconds = 0
        refreshSelectedServerUi()
        return smartAttemptId
    }

    private fun isSmartAttemptActive(attemptId: Long): Boolean {
        return attemptId > 0L && attemptId == smartAttemptId && smartConnecting
    }

    private fun invalidateSmartConnectAttempt() {
        smartAttemptId += 1L
        smartConnectJob?.cancel()
        smartConnectJob = null
        smartStartWatchdogJob?.cancel()
        smartStartWatchdogJob = null
        mainViewModel.cancelRealPing()
        pendingSmartVpnPermission = false
        pendingSmartVpnAttemptId = 0L
    }

    private fun cancelSmartConnect() {
        invalidateSmartConnectAttempt()
        smartConnecting = false
        smartConnectionFailed = false
        smartCountdownSeconds = 0
        stopServiceReliably()
        refreshSelectedServerUi()
    }

    private fun isServiceRunningConfirmed(): Boolean =
        runCatching { V2RayServiceManager.isRunning() }.getOrDefault(false) ||
                mainViewModel.isRunning.value == true

    private fun stopServiceReliably() {
        V2RayServiceManager.stopVService(this)
        smartStopWatchdogJob?.cancel()
        smartStopWatchdogJob = lifecycleScope.launch {
            for (retryDelay in SMART_STOP_RETRY_DELAYS_MS) {
                delay(retryDelay)
                val stillRunning = isServiceRunningConfirmed()
                if (!stillRunning) break
                V2RayServiceManager.stopVService(this@MainActivity)
            }
        }
    }

    private fun requestVpnPermissionAndStart(isSmartConnect: Boolean, attemptId: Long = 0L) {
        if (isSmartConnect && !isSmartAttemptActive(attemptId)) return
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray(isSmartConnect, attemptId)
            } else {
                pendingSmartVpnPermission = isSmartConnect
                pendingSmartVpnAttemptId = if (isSmartConnect) attemptId else 0L
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray(isSmartConnect, attemptId)
        }
    }

    private fun startV2Ray(isSmartConnect: Boolean = false, attemptId: Long = 0L) {
        if (isSmartConnect && !isSmartAttemptActive(attemptId)) return
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            if (isSmartConnect) {
                markSmartConnectFailed(attemptId)
            } else {
                manualConnecting = false
                refreshSelectedServerUi()
            }
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) { }
        }
        V2RayServiceManager.startVService(this)
        if (isSmartConnect) {
            smartStartWatchdogJob?.cancel()
            smartStartWatchdogJob = lifecycleScope.launch {
                delay(SMART_START_CONFIRM_TIMEOUT_MS)
                if (!isSmartAttemptActive(attemptId) || mainViewModel.isRunning.value == true) return@launch
                val coreRunning = withContext(Dispatchers.IO) {
                    runCatching { V2RayServiceManager.isRunning() }.getOrDefault(false)
                }
                if (!coreRunning && isSmartAttemptActive(attemptId)) {
                    markSmartConnectFailed(attemptId)
                }
            }
        } else {
            lifecycleScope.launch {
                delay(6_000L)
                if (manualConnecting && mainViewModel.isRunning.value != true) {
                    manualConnecting = false
                    refreshSelectedServerUi()
                }
            }
        }
    }

    fun restartV2Ray() {
        lastConnectedPing = null
        if (mainViewModel.isRunning.value == true) V2RayServiceManager.stopVService(this)
        lifecycleScope.launch {
            delay(500L)
            startV2Ray(false)
        }
    }

    private fun handlePingClick() {
        val guid = MmkvManager.getSelectServer().orEmpty()
        if (guid.isBlank() || smartConnecting) return
        val running = mainViewModel.isRunning.value == true
        if (currentMode == MODE_MANUAL) {
            binding.tvManualPing.setText(R.string.mobiletina_testing)
        } else {
            binding.tvAutoPing.setText(R.string.mobiletina_testing)
        }
        if (running) {
            lastConnectedPing = null
            mainViewModel.testCurrentServerRealPing()
        } else {
            mainViewModel.testServerRealPing(guid)
        }
    }

    fun refreshSelectedServerUi() {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        val profile = selectedGuid.takeIf { it.isNotBlank() }?.let(MmkvManager::decodeServerConfig)
        val ping = selectedGuid.takeIf { it.isNotBlank() }
            ?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis } ?: 0L
        val running = mainViewModel.isRunning.value == true

        if (!running && currentMode == MODE_MANUAL && selectedGuid.isNotBlank()) {
            prewarmManualConnection(selectedGuid)
        }

        binding.tvAutoServer.text = profile?.remarks.orEmpty()
        binding.tvManualSelected.text = profile?.remarks.orEmpty()
        binding.tvManualPing.text = when {
            smartConnecting && smartCountdownSeconds > 0 ->
                getString(R.string.mobiletina_smart_countdown_short, smartCountdownSeconds)
            smartConnecting -> getString(R.string.mobiletina_testing)
            running && !lastConnectedPing.isNullOrBlank() -> lastConnectedPing
            else -> pingLabel(ping)
        }
        binding.manualSelectedRow.visibility = if (profile != null) View.VISIBLE else View.INVISIBLE

        val autoArtwork: Int
        val status: String
        when {
            MobileTinaSubscriptionMarkerManager.isSubscriptionExpiredCached() -> {
                autoArtwork = R.drawable.red
                status = "اشتراک شما به پایان رسید"
            }
            running -> {
                autoArtwork = R.drawable.blue
                status = getString(R.string.mobiletina_status_connected)
            }
            smartConnecting -> {
                autoArtwork = R.drawable.yellow
                status = getString(R.string.mobiletina_status_connecting)
            }
            smartConnectionFailed -> {
                autoArtwork = R.drawable.red
                status = getString(R.string.mobiletina_status_failed)
            }
            else -> {
                autoArtwork = R.drawable.white
                status = getString(R.string.mobiletina_status_disconnected)
            }
        }

        binding.fabAuto.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        binding.fabAuto.imageTintList = null
        binding.fabAuto.setImageResource(autoArtwork)
        binding.tvAutoStatus.text = status
        val showAutoDetails = running || smartConnecting
        binding.tvAutoPing.visibility = if (showAutoDetails) View.VISIBLE else View.GONE
        binding.tvAutoServer.visibility = if (showAutoDetails) View.VISIBLE else View.GONE
        binding.tvAutoServer.text = if (showAutoDetails) profile?.remarks.orEmpty() else ""
        binding.tvAutoPing.text = when {
            smartConnecting -> getString(R.string.mobiletina_testing)
            running && !lastConnectedPing.isNullOrBlank() ->
                getString(R.string.mobiletina_ping_format, lastConnectedPing)
            running && ping > 0L ->
                getString(R.string.mobiletina_ping_format, ping.toString())
            running && ping < 0L -> getString(R.string.mobiletina_ping_inactive)
            running -> getString(R.string.mobiletina_tap_for_ping)
            else -> ""
        }

        binding.fab.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        binding.fab.imageTintList = null
        binding.fab.setImageResource(
            if (running) R.drawable.fab else R.drawable.stop
        )
        binding.subscriptionCard.translationY = if (running) -12f * resources.displayMetrics.density else 0f
        refreshSubscriptionCard()
    }

    private fun prewarmManualConnection(guid: String) {
        if (guid.isBlank() || guid == manualPrewarmGuid) return
        manualPrewarmGuid = guid
        manualPrewarmJob?.cancel()
        manualPrewarmJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching { V2RayServiceManager.isRunning() }
            runCatching { com.v2ray.ang.service.TProxyService.preloadNative() }
            runCatching {
                com.v2ray.ang.handler.V2rayConfigManager.getV2rayConfig(
                    applicationContext,
                    guid
                )
            }
        }
    }

    private fun pingLabel(ping: Long): String = when {
        ping > 0L -> ping.toString()
        ping < 0L -> getString(R.string.mobiletina_ping_inactive)
        else -> getString(R.string.mobiletina_ping_unknown)
    }

    private fun refreshSubscriptionCard() {
        val item = MmkvManager.decodeSubscription(mainViewModel.subscriptionId)
        if (item == null) {
            binding.subscriptionCard.visibility = View.GONE
            return
        }

        val total = (item.trafficTotalBytes ?: 0L).coerceAtLeast(0L)
        val used = ((item.trafficUploadBytes ?: 0L) + (item.trafficDownloadBytes ?: 0L)).coerceAtLeast(0L)
        val expire = (item.commentExpireEpochSeconds ?: item.expireEpochSeconds ?: 0L)
            .coerceAtLeast(0L)
        if (total <= 0L && expire <= 0L) {
            binding.subscriptionCard.visibility = View.GONE
            return
        }

        binding.subscriptionCard.visibility = View.VISIBLE
        binding.tvSubscriptionName.text = item.remarks
        if (total > 0L) {
            val progress = ((used.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            binding.subscriptionProgress.progress = progress
            binding.subscriptionProgress.visibility = View.VISIBLE
            binding.tvSubscriptionUsage.text = getString(
                R.string.mobiletina_subscription_usage_compact,
                formatBytes(used), formatBytes(total)
            )
            binding.tvSubscriptionUsage.visibility = View.VISIBLE
        } else {
            binding.subscriptionProgress.visibility = View.GONE
            binding.tvSubscriptionUsage.visibility = View.GONE
        }

        if (expire > 0L) {
            val days = ceil(((expire * 1000L - System.currentTimeMillis()).coerceAtLeast(0L)) / 86_400_000.0).toLong()
            binding.tvSubscriptionDays.text = String.format(java.util.Locale.ROOT, r.a(7), days)
            binding.tvSubscriptionDays.visibility = View.VISIBLE
        } else {
            binding.tvSubscriptionDays.visibility = View.GONE
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        do {
            value /= 1024.0
            i++
        } while (value >= 1024.0 && i < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", value, units[i])
    }

    private fun clearSmartConnectState() {
        invalidateSmartConnectAttempt()
        smartConnecting = false
        smartConnectionFailed = false
        smartCountdownSeconds = 0
        refreshSelectedServerUi()
    }

    private fun markSmartConnectFailed(attemptId: Long = smartAttemptId) {
        if (!isSmartAttemptActive(attemptId)) return
        smartConnectJob = null
        smartStartWatchdogJob?.cancel()
        smartStartWatchdogJob = null
        pendingSmartVpnPermission = false
        pendingSmartVpnAttemptId = 0L
        mainViewModel.cancelRealPing()
        smartConnecting = false
        smartConnectionFailed = true
        smartCountdownSeconds = 0
        refreshSelectedServerUi()
    }

    override fun onResume() {
        super.onResume()
        MobileTinaExpiryManager.recoverPending(this)
        setupGroupTab()
        ensureSelectedServerForCurrentSubscription()
        mainViewModel.reloadServerListAsync()
        refreshSelectedServerUi()
        showExpiredSubscriptionToastIfNeeded()
        reconcileExistingMarkersAsync()
        updateSubscriptionOnResume()
    }

    private fun reconcileExistingMarkersAsync() {
        markerRecoveryJob?.cancel()
        markerRecoveryJob = lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                MobileTinaSubscriptionMarkerManager.processExistingMarkers()
            }
            if (!changed) return@launch
            normalizeSubscriptionNames()
            setupGroupTab()
            ensureSelectedServerForCurrentSubscription()
            mainViewModel.reloadServerListAsync()
            refreshSelectedServerUi()
            showExpiredSubscriptionToastIfNeeded()
        }
    }

    private fun updateSubscriptionOnResume() {
        if (!hasInternetConnection()) {
            showInternetRequiredDialog()
            return
        }
        internetDialogHandler.removeCallbacksAndMessages(null)
        internetDialog?.dismiss()
        internetDialog = null
        if (!firstRunPrefs.getBoolean(FIRST_RUN_COMPLETED, false)) return

        val now = SystemClock.elapsedRealtime()
        if (!subscriptionRefreshing && now - lastSubscriptionRefreshAt >= SUBSCRIPTION_REFRESH_GUARD_MS) {
            lastSubscriptionRefreshAt = now
            refreshSubscriptionsSilently()
        }
    }

    private fun showInternetRequiredDialog() {
        if (isFinishing || isDestroyed || internetDialog?.isShowing == true) return
        internetDialog = AlertDialog.Builder(this)
            .setMessage(R.string.mobiletina_enable_internet)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (internetDialog === dialog) internetDialog = null
                }
                dialog.show()
                internetDialogHandler.postDelayed({
                    if (dialog.isShowing) dialog.dismiss()
                }, INTERNET_DIALOG_DURATION_MS)
            }
    }

    private fun refreshSubscriptionsSilently() {
        if (subscriptionRefreshing) return
        subscriptionRefreshing = true
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val updateResult = runCatching {
                mainViewModel.updateEverySubscription().also {
                    MobileTinaSubscriptionInfo.refreshAll()
                }
            }.getOrDefault(com.v2ray.ang.dto.SubscriptionUpdateResult())
            val serverListChanged = updateResult.configCount > 0
            normalizeSubscriptionNames()
            withContext(Dispatchers.Main) {
                showExpiredSubscriptionToastIfNeeded()
                if (serverListChanged) {
                    setupGroupTab()
                    ensureSelectedServerForCurrentSubscription()
                    mainViewModel.reloadServerListAsync()
                    refreshSelectedServerUi()
                } else {
                    // Metadata such as traffic/expiry may still change, but the server list and
                    // tabs are intentionally left untouched when the optimizer found no profile
                    // additions, removals or rewrites.
                    refreshSubscriptionCard()
                }
                subscriptionRefreshing = false
                binding.progressBar.visibility = View.INVISIBLE
            }
        }
    }

    fun importConfigViaSub(): Boolean {
        if (subscriptionRefreshing) return false
        subscriptionRefreshing = true
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                mainViewModel.updateEverySubscription()
                MobileTinaSubscriptionInfo.refreshAll()
            }
            normalizeSubscriptionNames()
            withContext(Dispatchers.Main) {
                showExpiredSubscriptionToastIfNeeded()
                setupGroupTab()
                ensureSelectedServerForCurrentSubscription()
                mainViewModel.reloadServerListAsync()
                subscriptionRefreshing = false
                binding.progressBar.visibility = View.INVISIBLE
                refreshSelectedServerUi()
            }
        }
        return true
    }

    private fun showExpiredSubscriptionToastIfNeeded() {
        if (MobileTinaSubscriptionMarkerManager.consumeExpiredToastPending()) {
            toastError("اشتراک شما به پایان رسید")
        }
    }

    private fun normalizeSubscriptionNames() {
        MmkvManager.decodeSubscriptions().forEach { cache ->
            if (cache.guid == AppConfig.DEFAULT_SUBSCRIPTION_ID) return@forEach
            if (cache.subscription.remarks != DEFAULT_SUBSCRIPTION_NAME) {
                cache.subscription.remarks = DEFAULT_SUBSCRIPTION_NAME
                MmkvManager.encodeSubscription(cache.guid, cache.subscription)
            }
        }
    }

    private fun hasInternetConnection(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun handleFirstRunPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            requestFirstRunVpnPermissionOnly()
        } else {
            requestFirstRunCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestFirstRunVpnPermissionOnly() {
        if (!SettingsManager.isVpnMode()) {
            markFirstRunPermissionCompleted()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) markFirstRunPermissionCompleted() else requestFirstRunVpnPermission.launch(intent)
    }

    private fun markFirstRunPermissionCompleted() {
        firstRunPrefs.edit().putBoolean(FIRST_RUN_COMPLETED, true).apply()
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) { }
    }

    private fun continueInitialLaunch() {
        firstLaunchDialog = MobileTinaFirstLaunchDialog.showOnce(this, firstRunPrefs) {
            firstLaunchDialog = null
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                continuePermissionFlow()
            }
        }
        if (firstLaunchDialog == null) {
            continuePermissionFlow()
        }
    }

    private fun continuePermissionFlow() {
        if (firstRunPrefs.getBoolean(FIRST_RUN_COMPLETED, false)) {
            checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) { }
        } else {
            handleFirstRunPermissions()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_local -> {
            importConfigLocal()
            true
        }
        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }
        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }
        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }
        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }
        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }
        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }
        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }
        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }
        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }
        R.id.service_restart -> {
            restartV2Ray()
            true
        }
        R.id.mobiletina_locate_selected -> {
            locateSelectedServer()
            true
        }
        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }
        R.id.real_ping_all -> {
            mainViewModel.testAllRealPing()
            true
        }
        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }
        R.id.sub_update -> {
            importConfigViaSub()
            true
        }
        R.id.del_all_config -> {
            delAllConfig()
            true
        }
        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }
        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        val intent = if (createConfigType == EConfigType.POLICYGROUP.value) {
            Intent()
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerGroupActivity::class.java)
        } else {
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        }
        requestActivityLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (!scanResult.isNullOrBlank()) importBatchConfig(scanResult)
        }
    }

    private fun importClipboard(): Boolean {
        return try {
            importBatchConfig(Utils.getClipboard(this))
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            toastError("خطا")
            false
        }
    }

    private fun importConfigLocal(): Boolean {
        return try {
            showFileChooser()
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            toastError("خطا")
            false
        }
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri != null) readContentFromUri(uri)
        }
    }

    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read config file", e)
            toastError("خطا")
        }
    }

    private fun importBatchConfig(raw: String?) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val normalized = MobileTinaImportNormalizer.normalize(raw)
                val (count, countSub) = AngConfigManager.importBatchConfig(normalized, mainViewModel.subscriptionId, true)
                if (countSub > 0) MobileTinaSubscriptionInfo.refreshAll()
                withContext(Dispatchers.Main) {
                    normalizeSubscriptionNames()
                    showExpiredSubscriptionToastIfNeeded()
                    when {
                        count > 0 -> {
                            val targetSubscriptionId = ensureImportedConfigsHaveVisibleGroup()
                            targetSubscriptionId?.let { subscriptionId ->
                                MobileTinaExpiryManager.scheduleFromImportedText(
                                    this@MainActivity,
                                    normalized,
                                    subscriptionId
                                )
                            }
                            toast(getString(R.string.title_import_config_count, count))
                            setupGroupTab()
                            mainViewModel.reloadServerList()
                            ensureSelectedServerForCurrentSubscription()
                        }
                        countSub > 0 -> {
                            setupGroupTab()
                            if (hasInternetConnection()) refreshSubscriptionsSilently()
                        }
                        else -> toastError("خطا")
                    }
                    binding.progressBar.visibility = View.INVISIBLE
                    ensureSelectedServerForCurrentSubscription()
                    refreshSelectedServerUi()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
                withContext(Dispatchers.Main) {
                    toastError("خطا")
                    binding.progressBar.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun ensureImportedConfigsHaveVisibleGroup(): String? {
        val defaultServers = MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        if (defaultServers.isEmpty()) {
            return mainViewModel.subscriptionId.takeIf { subscriptionId ->
                subscriptionId.isNotBlank() &&
                        subscriptionId != AppConfig.DEFAULT_SUBSCRIPTION_ID &&
                        MmkvManager.decodeSubscription(subscriptionId) != null
            }
        }

        val currentId = mainViewModel.subscriptionId
            .takeIf { it.isNotBlank() && it != AppConfig.DEFAULT_SUBSCRIPTION_ID }
        val existingId = MmkvManager.decodeSubscriptions()
            .firstOrNull { it.guid != AppConfig.DEFAULT_SUBSCRIPTION_ID }
            ?.guid
        val targetId = currentId ?: existingId ?: Utils.getUuid().also { newId ->
            MmkvManager.encodeSubscription(
                newId,
                SubscriptionItem(remarks = DEFAULT_SUBSCRIPTION_NAME, enabled = true, autoUpdate = false)
            )
        }

        val merged = (MmkvManager.decodeServerList(targetId) + defaultServers).distinct().toMutableList()
        defaultServers.forEach { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { profile ->
                profile.subscriptionId = targetId
                MmkvManager.encodeProfileDirect(guid, JsonUtil.toJson(profile))
            }
        }
        MmkvManager.encodeServerList(merged, targetId)
        MmkvManager.encodeServerList(mutableListOf(), AppConfig.DEFAULT_SUBSCRIPTION_ID)
        mainViewModel.subscriptionIdChanged(targetId)
        if (MmkvManager.getSelectServer().isNullOrBlank()) {
            merged.firstOrNull()?.let(MmkvManager::setSelectServer)
        }
        return targetId
    }

    private fun locateSelectedServer() {
        if (currentMode != MODE_MANUAL) setMode(MODE_MANUAL)
        val selected = MmkvManager.getSelectServer().orEmpty()
        if (selected.isBlank()) {
            toast(R.string.title_file_chooser)
            return
        }
        val subId = MmkvManager.decodeServerConfig(selected)?.subscriptionId.orEmpty()
        val groups = mainViewModel.getSubscriptions(this)
        val index = groups.indexOfFirst { it.id == subId }
        if (index >= 0) {
            mainViewModel.subscriptionIdChanged(groups[index].id)
            binding.viewPager.setCurrentItem(index, false)
        }
        binding.viewPager.postDelayed({
            supportFragmentManager.fragments
                .filterIsInstance<GroupServerFragment>()
                .firstOrNull { it.isVisible }
                ?.scrollToSelectedServer()
        }, 180L)
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this)
            .setMessage(R.string.del_config_comfirm)
            .setPositiveButton(R.string.mobiletina_confirm) { _, _ ->
                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        setupGroupTab()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.progressBar.visibility = View.INVISIBLE
                        refreshSelectedServerUi()
                    }
                }
            }
            .setNegativeButton(R.string.mobiletina_cancel, null)
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this)
            .setMessage(R.string.del_config_comfirm)
            .setPositiveButton(R.string.mobiletina_confirm) { _, _ ->
                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.progressBar.visibility = View.INVISIBLE
                    }
                }
            }
            .setNegativeButton(R.string.mobiletina_cancel, null)
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this)
            .setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(R.string.mobiletina_confirm) { _, _ ->
                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.progressBar.visibility = View.INVISIBLE
                    }
                }
            }
            .setNegativeButton(R.string.mobiletina_cancel, null)
            .show()
    }

    private fun sortByTestResults() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            withContext(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.progressBar.visibility = View.INVISIBLE
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.z0 -> startActivity(Intent(this, X7::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.mobiletina_subscriptions -> startActivity(
                Intent(this, SubSettingActivity::class.java)
                    .putExtra(SubSettingActivity.EXTRA_HIDDEN_MODE, true)
            )
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.mobiletina_reset -> confirmResetVpn()
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun confirmResetVpn() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mobiletina_reset_title)
            .setMessage(R.string.mobiletina_reset_message)
            .setPositiveButton(R.string.mobiletina_reset_confirm) { _, _ ->
                MobileTinaResetManager.reset(this)
                clearSmartConnectState()
                setupGroupTab()
                mainViewModel.reloadServerList()
                refreshSelectedServerUi()
                toast(R.string.mobiletina_reset_done)
            }
            .setNegativeButton(R.string.mobiletina_cancel, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        smartConnectJob?.cancel()
        smartStartWatchdogJob?.cancel()
        smartStopWatchdogJob?.cancel()
        manualPrewarmJob?.cancel()
        markerRecoveryJob?.cancel()
        if (expiryReceiverRegistered) {
            unregisterReceiver(expiryDataChangedReceiver)
            expiryReceiverRegistered = false
        }
        cancelDrawerReveal()
        drawerSecretHandler.removeCallbacksAndMessages(null)
        drawerRevealRunnable = null
        drawerHideRunnable = null
        internetDialogHandler.removeCallbacksAndMessages(null)
        internetDialog?.dismiss()
        internetDialog = null
        firstLaunchDialog?.dismiss()
        firstLaunchDialog = null
        manualSubscriptionDialog?.dismiss()
        manualSubscriptionDialog = null
        tabMediator?.detach()
        super.onDestroy()
    }

    companion object {
        private const val MODE_AUTO = 0
        private const val MODE_MANUAL = 1
        private const val FIRST_RUN_COMPLETED = "permissions_completed"
        private const val SUBSCRIPTION_REFRESH_GUARD_MS = 30_000L
        private const val DRAWER_SUBSCRIPTION_HOLD_MS = 10_000L
        private const val DRAWER_SUBSCRIPTION_VISIBLE_MS = 60_000L
        private const val MANUAL_SUBSCRIPTION_HOLD_MS = 10_000L
        private const val SMART_SUBSCRIPTION_REFRESH_GRACE_MS = 1_200L
        private const val SMART_FIRST_PING_TIMEOUT_MS = 6_000L
        private const val SMART_RETRY_PING_TIMEOUT_MS = 5_000L
        private const val SMART_RETRY_DELAY_MS = 300L
        private const val SMART_START_CONFIRM_TIMEOUT_MS = 15_000L
        private const val SMART_BATCH_ID_MULTIPLIER = 10L
        private const val SMART_BATCH_START_POLL_MS = 20L
        private const val SMART_PING_RESULT_POLL_MS = 60L
        private const val SMART_FAST_SETTLE_MS = 350L
        private val SMART_STOP_RETRY_DELAYS_MS = longArrayOf(300L, 900L, 1_800L)
        private const val MODE_SWITCH_ANIMATION_MS = 180L
        private const val INTERNET_DIALOG_DURATION_MS = 3_000L
        private val DEFAULT_SUBSCRIPTION_NAME: String get() = w.a()
    }
}
