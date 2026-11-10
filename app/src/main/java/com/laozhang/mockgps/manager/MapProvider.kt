package com.laozhang.mockgps.manager

import com.blankj.utilcode.util.Utils

enum class MapProvider(val label: String) {
    AMAP("高德地图"), BAIDU("百度地图");

    companion object {
        private val preferences get() = Utils.getApp().getSharedPreferences("map_provider", 0)
        var current: MapProvider
            get() = values().firstOrNull { it.name == preferences.getString("provider", null) } ?: BAIDU
            set(value) { preferences.edit().putString("provider", value.name).commit() }
    }
}
