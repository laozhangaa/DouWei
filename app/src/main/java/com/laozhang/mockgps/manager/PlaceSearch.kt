package com.laozhang.mockgps.manager

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.*
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.*
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption

data class PlaceSuggestion(val uid: String?, val name: String?, val city: String?,
                           val district: String?, val address: String?, val point: LatLng)
data class PlaceAddress(val name: String?, val city: String?)

class PlaceSearch(private val context: Context, private val provider: MapProvider) {
    private var suggestionSearch: SuggestionSearch? = null
    private var geoCoder: GeoCoder? = null
    private var destroyed = false

    fun suggest(keyword: String, cityName: String, cityCode: String?,
                callback: (Result<List<PlaceSuggestion>>) -> Unit) {
        try {
            check(MapSdkCredentials.initialize(provider)) { "请先在设置中配置${provider.label}凭证" }
            if (provider == MapProvider.BAIDU) {
                suggestionSearch?.destroy()
                val search = SuggestionSearch.newInstance()
                suggestionSearch = search
                search.setOnGetSuggestionResultListener { result ->
                    if (destroyed || suggestionSearch !== search) return@setOnGetSuggestionResultListener
                    when (result?.error) {
                        SearchResult.ERRORNO.NO_ERROR -> callback(Result.success(result.allSuggestions.orEmpty()
                            .filter { it.pt != null }.map {
                                PlaceSuggestion(it.uid, it.key, it.city, it.district, it.address, it.pt.toAppPoint())
                            }))
                        SearchResult.ERRORNO.RESULT_NOT_FOUND -> callback(Result.success(emptyList()))
                        else -> callback(Result.failure(IllegalStateException("百度搜索失败(${result?.error})")))
                    }
                }
                if (!search.requestSuggestion(SuggestionSearchOption().keyword(keyword)
                        .city(cityName.ifBlank { "全国" }).citylimit(cityName.isNotBlank()))) {
                    callback(Result.failure(IllegalStateException("百度搜索请求未能提交")))
                }
            } else {
                val query = InputtipsQuery(keyword, cityCode?.takeIf { it.isNotBlank() } ?: cityName)
                query.setCityLimit(cityName.isNotBlank())
                Inputtips(context, query).apply {
                    setInputtipsListener { tips, code ->
                        if (destroyed) return@setInputtipsListener
                        if (code == AMapException.CODE_AMAP_SUCCESS) {
                            callback(Result.success(tips.orEmpty().filter { it.point != null }.map {
                                PlaceSuggestion(it.poiID, it.name, cityName, it.district, it.address,
                                    LatLng(it.point.latitude, it.point.longitude))
                            }))
                        } else callback(Result.failure(IllegalStateException("高德搜索失败($code)")))
                    }
                    requestInputtipsAsyn()
                }
            }
        } catch (e: Exception) { callback(Result.failure(e)) }
    }

    fun reverse(point: LatLng, callback: (Result<PlaceAddress>) -> Unit) {
        try {
            check(MapSdkCredentials.initialize(provider)) { "请先在设置中配置${provider.label}凭证" }
            if (provider == MapProvider.BAIDU) {
                geoCoder?.destroy()
                val search = GeoCoder.newInstance()
                geoCoder = search
                search.setOnGetGeoCodeResultListener(object : OnGetGeoCoderResultListener {
                    override fun onGetGeoCodeResult(result: GeoCodeResult?) {}
                    override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                        if (destroyed || geoCoder !== search) return
                        if (result?.error == SearchResult.ERRORNO.NO_ERROR) {
                            callback(Result.success(PlaceAddress(
                                result.poiList?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                                    ?: result.address,
                                result.addressDetail?.city?.takeIf { it.isNotBlank() }
                                    ?: result.addressDetail?.province)))
                        } else callback(Result.failure(IllegalStateException("百度地址解析失败(${result?.error})")))
                    }
                })
                if (!search.reverseGeoCode(ReverseGeoCodeOption().location(point.toBaiduPoint())
                        .newVersion(1).radius(500))) {
                    callback(Result.failure(IllegalStateException("百度地址解析请求未能提交")))
                }
            } else {
                GeocodeSearch(context).apply {
                    setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                        override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {}
                        override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                            if (destroyed) return
                            val address = result?.regeocodeAddress
                            if (code == AMapException.CODE_AMAP_SUCCESS && address != null) {
                                callback(Result.success(PlaceAddress(
                                    address.pois?.firstOrNull()?.title?.takeIf { it.isNotBlank() }
                                        ?: address.formatAddress,
                                    address.city?.takeIf { it.isNotBlank() } ?: address.province)))
                            } else callback(Result.failure(IllegalStateException("高德地址解析失败($code)")))
                        }
                    })
                    getFromLocationAsyn(RegeocodeQuery(LatLonPoint(point.latitude, point.longitude),
                        500f, GeocodeSearch.AMAP))
                }
            }
        } catch (e: Exception) { callback(Result.failure(e)) }
    }

    fun destroy() {
        destroyed = true
        suggestionSearch?.destroy(); suggestionSearch = null
        geoCoder?.destroy(); geoCoder = null
    }
}
