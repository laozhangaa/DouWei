package com.laozhang.mockgps.manager

import com.amap.api.services.route.RouteSearchV2
import com.baidu.mapapi.search.route.DrivingRoutePlanOption
import com.laozhang.mockgps.model.DrivingPreference

object DrivingPolicyMapper {
    fun supports(provider: MapProvider, preference: DrivingPreference) = provider == MapProvider.AMAP || !preference.amapOnly

    fun amap(preference: DrivingPreference) = when (preference) {
        DrivingPreference.RECOMMENDED -> RouteSearchV2.DrivingStrategy.DEFAULT
        DrivingPreference.AVOID_CONGESTION -> RouteSearchV2.DrivingStrategy.AVOID_CONGESTION
        DrivingPreference.LESS_TOLL -> RouteSearchV2.DrivingStrategy.LESS_CHARGE
        DrivingPreference.TIME_FIRST -> RouteSearchV2.DrivingStrategy.SPEED_PRIORITY
        DrivingPreference.AVOID_HIGHWAY -> RouteSearchV2.DrivingStrategy.AVOID_HIGHWAY
        DrivingPreference.HIGHWAY_FIRST -> RouteSearchV2.DrivingStrategy.HIGHWAY_PRIORITY
    }

    fun baidu(preference: DrivingPreference): DrivingRoutePlanOption.DrivingPolicy = when (preference) {
        DrivingPreference.RECOMMENDED, DrivingPreference.TIME_FIRST -> DrivingRoutePlanOption.DrivingPolicy.ECAR_TIME_FIRST
        DrivingPreference.AVOID_CONGESTION -> DrivingRoutePlanOption.DrivingPolicy.ECAR_AVOID_JAM
        DrivingPreference.LESS_TOLL -> DrivingRoutePlanOption.DrivingPolicy.ECAR_FEE_FIRST
        else -> throw IllegalArgumentException("百度当前不支持此偏好，请选择其他偏好或切换高德")
    }
}
