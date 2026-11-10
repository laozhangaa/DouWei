package com.laozhang.mockgps.widget

import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.app.Activity
import android.os.Bundle
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import com.amap.api.maps.TextureMapView
import com.laozhang.mockgps.manager.AppMap
import com.laozhang.mockgps.manager.MapProvider
import com.laozhang.mockgps.manager.MapSdkCredentials
import com.laozhang.mockgps.ui.ExpandActivity

/** Creates only the selected provider; both providers use GCJ-02 throughout the app. */
class AppMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                          defStyleAttr: Int = 0) : FrameLayout(context, attrs, defStyleAttr) {
    val provider = MapProvider.current
    private val configured = MapSdkCredentials.initialize(provider)
    private val amapView = if (configured && provider == MapProvider.AMAP) TextureMapView(context) else null
    private val baiduView = if (configured && provider == MapProvider.BAIDU) com.baidu.mapapi.map.TextureMapView(context) else null
    val map: AppMap by lazy { AppMap(provider, amapView?.map, baiduView?.map) }

    init {
        val mapView = amapView ?: baiduView
        if (mapView != null) addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        else addView(TextView(context).apply {
            text = "请先配置${provider.label}凭证\n点击前往设置"
            gravity = Gravity.CENTER
            textSize = 14f
            setOnClickListener {
                context.startActivity(Intent(context, ExpandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        baiduView?.showZoomControls(false)
        baiduView?.showScaleControl(false)
    }

    fun onCreate(state: Bundle?) { amapView?.onCreate(state); baiduView?.onCreate(context, state) }
    fun onResume() {
        if (provider != MapProvider.current || (!configured && MapSdkCredentials.isConfigured())) {
            var host = context
            while (host is ContextWrapper && host !is Activity) host = host.baseContext
            (host as? Activity)?.recreate()
            return
        }
        amapView?.onResume(); baiduView?.onResume()
    }
    fun onPause() { amapView?.onPause(); baiduView?.onPause() }
    fun onSaveInstanceState(state: Bundle) { amapView?.onSaveInstanceState(state); baiduView?.onSaveInstanceState(state) }
    fun onDestroy() { map.destroy(); amapView?.onDestroy(); baiduView?.onDestroy() }
}
