package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.extension.v2RayApplication
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.PerAppProxyViewModel
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class PerAppProxyActivity : BaseActivity() {
    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }

    private var adapter: PerAppProxyAdapter? = null
    private var appsAll: List<AppInfo>? = null
    private val viewModel: PerAppProxyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.per_app_proxy_settings))

        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)

        initList()

        binding.switchPerAppProxy.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, isChecked)
        }
        binding.switchPerAppProxy.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)

        binding.switchBypassApps.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, isChecked)
        }
        binding.switchBypassApps.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)

        binding.layoutSwitchBypassAppsTips.setOnClickListener {
            Toasty.info(this, R.string.summary_pref_per_app_proxy, Toast.LENGTH_LONG, true).show()
        }
    }

    private fun initList() {
        showLoading()

        lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val appsList = AppManagerUtil.loadNetworkAppList(this@PerAppProxyActivity)
                    val selectedPackages = viewModel.getAll()
                    if (selectedPackages.isNotEmpty()) {
                        appsList.forEach { app ->
                            app.isSelected = if (selectedPackages.contains(app.packageName)) 1 else 0
                        }
                    }
                    sortAppsWithPinnedFavorites(appsList)
                }

                appsAll = apps
                adapter = PerAppProxyAdapter(apps, viewModel)
                binding.recyclerView.adapter = adapter

            } catch (e: Exception) {
                LogUtil.e(ANG_PACKAGE, "Error loading apps", e)
            } finally {
                hideLoading()
            }
        }
    }

    /**
     * Keep the most frequently requested apps at the top when they are installed.
     * Everything else preserves the existing selected/system/name ordering.
     */
    private fun sortAppsWithPinnedFavorites(apps: List<AppInfo>): List<AppInfo> {
        val collator = Collator.getInstance()
        return apps.sortedWith { first, second ->
            val firstPinned = preferredAppPriority(first.packageName)
            val secondPinned = preferredAppPriority(second.packageName)
            when {
                firstPinned != secondPinned -> firstPinned.compareTo(secondPinned)
                first.isSelected != second.isSelected -> second.isSelected.compareTo(first.isSelected)
                first.isSystemApp != second.isSystemApp -> first.isSystemApp.compareTo(second.isSystemApp)
                else -> {
                    val byName = collator.compare(first.appName, second.appName)
                    if (byName != 0) byName else first.packageName.compareTo(second.packageName)
                }
            }
        }
    }

    private fun preferredAppPriority(packageName: String): Int = when (packageName) {
        // Telegram / Telegram X
        "org.telegram.messenger" -> 0
        "org.telegram.messenger.web" -> 1
        "org.thunderdog.challegram" -> 2

        // Instagram
        "com.instagram.android" -> 10

        // WhatsApp / WhatsApp Business
        "com.whatsapp" -> 20
        "com.whatsapp.w4b" -> 21

        // Chrome variants
        "com.android.chrome" -> 30
        "com.chrome.beta" -> 31
        "com.chrome.dev" -> 32
        "com.chrome.canary" -> 33

        // Google app
        "com.google.android.googlequicksearchbox" -> 40
        else -> Int.MAX_VALUE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bypass_list, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    filterProxyApp(newText.orEmpty())
                    return false
                }
            })
        }

        return super.onCreateOptionsMenu(menu)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.select_all -> {
            selectAllApp()
            allowPerAppProxy()
            true
        }

        R.id.invert_selection -> {
            invertSelection()
            allowPerAppProxy()
            true
        }

        R.id.select_proxy_app -> {
            selectProxyAppAuto()
            allowPerAppProxy()
            true
        }

        R.id.import_proxy_app -> {
            importProxyApp()
            allowPerAppProxy()
            true
        }

        R.id.export_proxy_app -> {
            exportProxyApp()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun selectAllApp() {
        adapter?.let { adapter ->
            val pkgNames = adapter.apps.map { it.packageName }
            val allSelected = pkgNames.all { viewModel.contains(it) }

            if (allSelected) {
                viewModel.removeAll(pkgNames)
            } else {
                viewModel.addAll(pkgNames)
            }
            refreshData()
        }
    }

    private fun invertSelection() {
        adapter?.let { adapter ->
            adapter.apps.forEach { app ->
                viewModel.toggle(app.packageName)
            }
            refreshData()
        }
    }

    private fun selectProxyAppAuto() {
        toast(R.string.msg_downloading_content)
        showLoading()

        val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
        lifecycleScope.launch(Dispatchers.IO) {
            var content = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000
                )
            )
            if (content.isNullOrEmpty()) {
                val proxyUsername = SettingsManager.getSocksUsername()
                val proxyPassword = SettingsManager.getSocksPassword()
                val httpPort = SettingsManager.getHttpPort()
                content = HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                ) ?: ""
            }
            launch(Dispatchers.Main) {
                selectProxyApp(content, true)
                toastSuccess(R.string.toast_success)
                hideLoading()
            }
        }
    }

    private fun importProxyApp() {
        val content = Utils.getClipboard(applicationContext)
        if (TextUtils.isEmpty(content)) return
        selectProxyApp(content, false)
        toastSuccess(R.string.toast_success)
    }

    private fun exportProxyApp() {
        var lst = binding.switchBypassApps.isChecked.toString()

        viewModel.getAll().forEach { pkg ->
            lst = lst + System.lineSeparator() + pkg
        }
        Utils.setClipboard(applicationContext, lst)
        toastSuccess(R.string.toast_success)
    }

    private fun allowPerAppProxy() {
        binding.switchPerAppProxy.isChecked = true
        SettingsChangeManager.makeRestartService()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun selectProxyApp(content: String, force: Boolean): Boolean {
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                Utils.readTextFromAssets(v2RayApplication, "proxy_package_name")
            } else {
                content
            }
            if (TextUtils.isEmpty(proxyApps)) return false

            viewModel.clear()

            if (binding.switchBypassApps.isChecked) {
                adapter?.let { adapter ->
                    adapter.apps.forEach { app ->
                        val packageName = app.packageName
                        if (!inProxyApps(proxyApps, packageName, force)) {
                            viewModel.add(packageName)
                        }
                    }
                    refreshData()
                }
            } else {
                adapter?.let { adapter ->
                    adapter.apps.forEach { app ->
                        val packageName = app.packageName
                        if (inProxyApps(proxyApps, packageName, force)) {
                            viewModel.add(packageName)
                        }
                    }
                    refreshData()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun inProxyApps(proxyApps: String, packageName: String, force: Boolean): Boolean {
        println(packageName)
        if (force) {
            if (packageName == "com.google.android.webview") return false
            if (packageName.startsWith("com.google")) return true
        }

        return proxyApps.indexOf(packageName) >= 0
    }

    private fun filterProxyApp(content: String): Boolean {
        val apps = ArrayList<AppInfo>()

        val key = content.uppercase()
        if (key.isNotEmpty()) {
            appsAll?.forEach {
                if (it.appName.uppercase().indexOf(key) >= 0
                    || it.packageName.uppercase().indexOf(key) >= 0
                ) {
                    apps.add(it)
                }
            }
        } else {
            appsAll?.forEach {
                apps.add(it)
            }
        }

        adapter = PerAppProxyAdapter(apps, adapter?.viewModel ?: viewModel)
        binding.recyclerView.adapter = adapter
        refreshData()
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        adapter?.notifyDataSetChanged()
    }
}
