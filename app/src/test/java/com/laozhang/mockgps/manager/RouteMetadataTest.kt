package com.laozhang.mockgps.manager

import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.*
import com.baidu.mapapi.search.route.DrivingRouteLine
import com.baidu.mapapi.search.route.DrivingRoutePlanOption
import com.laozhang.mockgps.manager.utils.*
import com.laozhang.mockgps.model.*
import org.junit.Assert.*
import org.junit.Test

class RouteMetadataTest {
    private fun path(lights: Int, road: String = "人民路") = DrivePathV2().apply {
        cost = Cost().apply { trafficLights = lights; duration = 500f; tolls = 7f }
        steps = (0..3).map { i -> DriveStepV2().apply {
            this.road = road; instruction = "前方路口右转"
            polyline = listOf(LatLonPoint(26.0, 106.0 + i * 0.002), LatLonPoint(26.0, 106.002 + i * 0.002))
        } }
    }

    @Test fun officialCountDurationAndFeeRemainDistinctFromEstimatedStopLocations() {
        val result = RouteMetadataFactory.fromPath(path(2), "AMAP", DrivingPreference.LESS_TOLL)
        result.validate()
        assertEquals(2, result.trafficLightCount)
        assertEquals(500L, result.sdkDurationSeconds)
        assertEquals(7f, result.tollYuan)
        assertEquals(listOf("人民路"), result.roadNames)
        assertEquals(2, result.estimatedStopMeters.size)
        assertTrue(result.estimatedStopMeters.first() > 20)
    }

    @Test fun zeroLightsAndHighwaysNeverInventSignalStops() {
        assertTrue(RouteMetadataFactory.fromPath(path(0), "AMAP", DrivingPreference.RECOMMENDED).estimatedStopMeters.isEmpty())
        for (road in listOf("兰海高速", "城市快速路", "连接匝道", "长隧道", "高架桥")) {
            val metadata = RouteMetadataFactory.fromPath(path(4, road), "BAIDU", DrivingPreference.RECOMMENDED)
            assertEquals(4, metadata.trafficLightCount)
            assertTrue(metadata.estimatedStopMeters.isEmpty())
        }
    }

    @Test fun baiduConversionPreservesOfficialSignalCountAndToll() {
        val baidu = DrivingRouteLine().apply { lightNum = 9; toll = 12; duration = 600 }
        val converted = MapConvertUtils.fromBaiduRoute(baidu)
        assertEquals(9, converted.cost.trafficLights)
        assertEquals(12f, converted.cost.tolls, 0f)
        assertEquals(600f, converted.cost.duration, 0f)
    }

    @Test fun providerPoliciesNeverSilentlyDowngradeUnsupportedHighwayPreferences() {
        assertEquals(DrivingRoutePlanOption.DrivingPolicy.ECAR_AVOID_JAM, DrivingPolicyMapper.baidu(DrivingPreference.AVOID_CONGESTION))
        assertEquals(DrivingRoutePlanOption.DrivingPolicy.ECAR_FEE_FIRST, DrivingPolicyMapper.baidu(DrivingPreference.LESS_TOLL))
        assertEquals(RouteSearchV2.DrivingStrategy.SPEED_PRIORITY, DrivingPolicyMapper.amap(DrivingPreference.TIME_FIRST))
        for (p in DrivingPreference.values()) {
            assertTrue(DrivingPolicyMapper.supports(MapProvider.AMAP, p))
            if (p.amapOnly) {
                assertFalse(DrivingPolicyMapper.supports(MapProvider.BAIDU, p))
                assertThrows(IllegalArgumentException::class.java) { DrivingPolicyMapper.baidu(p) }
            } else assertNotNull(DrivingPolicyMapper.baidu(p))
        }
    }
}
