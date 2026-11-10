package com.laozhang.mockgps.manager

import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.baidu.location.LocationClient
import com.baidu.mapapi.SDKInitializer
import com.blankj.utilcode.util.Utils

/**
 * User supplied credentials for the map SDKs.
 *
 * Credentials deliberately live only in app preferences.  Nothing in the
 * source tree or the manifest provides a fallback key, so a release build
 * cannot accidentally ship a developer's key.
 */
object MapSdkCredentials {
    private const val PREFS = "map_credentials"
    private const val AMAP_KEY = "amap_key"
    private const val BAIDU_AK = "baidu_ak"

    private val preferences
        get() = Utils.getApp().getSharedPreferences(PREFS, 0)

    val amapKey: String
        get() = preferences.getString(AMAP_KEY, "").orEmpty()

    val baiduAk: String
        get() = preferences.getString(BAIDU_AK, "").orEmpty()

    private val initializedKeys = mutableMapOf<MapProvider, String>()

    fun key(provider: MapProvider): String = if (provider == MapProvider.AMAP) amapKey else baiduAk

    fun isConfigured(provider: MapProvider = MapProvider.current): Boolean = key(provider).isNotBlank()

    /** A running SDK retains auth/native state, so changed credentials need a new process. */
    @Synchronized
    fun needsRestart(amap: String, baidu: String): Boolean = initializedKeys.any { (provider, value) ->
        value != (if (provider == MapProvider.AMAP) amap else baidu).trim()
    }

    fun save(amap: String, baidu: String): Boolean = preferences.edit()
            .putString(AMAP_KEY, amap.trim())
            .putString(BAIDU_AK, baidu.trim())
            .commit()

    /** Called before constructing any map, location client or search object. */
    @Synchronized
    fun initialize(provider: MapProvider): Boolean {
        val key = key(provider)
        if (key.isBlank()) return false
        initializedKeys[provider]?.let { return it == key }
        val app = Utils.getApp()
        when (provider) {
            MapProvider.AMAP -> {
                MapsInitializer.updatePrivacyShow(app, true, true)
                MapsInitializer.updatePrivacyAgree(app, true)
                AMapLocationClient.updatePrivacyShow(app, true, true)
                AMapLocationClient.updatePrivacyAgree(app, true)
                ServiceSettings.updatePrivacyShow(app, true, true)
                ServiceSettings.updatePrivacyAgree(app, true)
                MapsInitializer.setApiKey(key)
                AMapLocationClient.setApiKey(key)
                ServiceSettings.getInstance().setApiKey(key)
            }
            MapProvider.BAIDU -> {
                SDKInitializer.setAgreePrivacy(app, true)
                LocationClient.setAgreePrivacy(true)
                SDKInitializer.setApiKey(key)
                LocationClient.setKey(key)
                SDKInitializer.initialize(app)
                SDKInitializer.setCoordType(com.baidu.mapapi.CoordType.GCJ02)
            }
        }
        initializedKeys[provider] = key
        return true
    }
}
