package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MobileTinaGeoAssetManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MobileTinaIntegrityGuard

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        // Hardened final releases validate package identity, immutable artwork, branded text,
        // and the APK signing certificate before any application state is initialized.
        MobileTinaIntegrityGuard.verify(this)

        MMKV.initialize(this)

        // Prepare bundled geo assets synchronously in every Android process before CoreNativeManager
        // can initialize Xray. This removes the first-run race that could leave geosite.dat partially
        // written and then permanently skipped on subsequent launches.
        MobileTinaGeoAssetManager.ensureReady(this, assets)

        // MobileTina no longer exposes or supports the two root-only Settings options.
        // Clear stale values from upgrades so an invisible root mode can never remain active.
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        MmkvManager.encodeSettings(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
        MmkvManager.encodeSettings(AppConfig.PREF_IS_BOOTED, false)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)
        SettingsManager.setNightMode()

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 300)
            .apply()
    }
}
