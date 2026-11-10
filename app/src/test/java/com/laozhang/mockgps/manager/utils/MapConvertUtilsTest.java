package com.laozhang.mockgps.manager.utils;

import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.DrivePathV2;
import com.amap.api.services.route.DriveStepV2;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MapConvertUtilsTest {
    @Test public void baiduRouteKeepsGeometryDistanceAndDuration() {
        com.baidu.mapapi.search.route.DrivingRouteLine route =
                new com.baidu.mapapi.search.route.DrivingRouteLine();
        com.baidu.mapapi.search.route.DrivingRouteLine.DrivingStep first =
                new com.baidu.mapapi.search.route.DrivingRouteLine.DrivingStep();
        com.baidu.mapapi.search.route.DrivingRouteLine.DrivingStep second =
                new com.baidu.mapapi.search.route.DrivingRouteLine.DrivingStep();
        first.setPathList(Arrays.asList(new com.baidu.mapapi.model.LatLng(26.59, 106.70),
                new com.baidu.mapapi.model.LatLng(26.60, 106.71)));
        second.setPathList(Arrays.asList(new com.baidu.mapapi.model.LatLng(26.60, 106.71),
                new com.baidu.mapapi.model.LatLng(26.61, 106.72)));
        route.setSteps(Arrays.asList(first, second));
        route.setDistance(2100);
        route.setDuration(300);
        DrivePathV2 converted = MapConvertUtils.INSTANCE.fromBaiduRoute(route);
        List<LatLng> points = MapConvertUtils.INSTANCE.convertLatLngList(converted);
        assertEquals(3, points.size());
        assertEquals(26.59, points.get(0).latitude, 0.0000001);
        assertEquals(106.72, points.get(2).longitude, 0.0000001);
        assertEquals(2100f, converted.getDistance(), 0f);
        assertEquals(300L, converted.getDuration());
    }

    @Test public void baiduRouteWithoutStepsHasNoUsableGeometry() {
        assertTrue(MapConvertUtils.INSTANCE.convertLatLngList(MapConvertUtils.INSTANCE.fromBaiduRoute(
                new com.baidu.mapapi.search.route.DrivingRouteLine())).isEmpty());
    }

    private DriveStepV2 step(LatLonPoint... points) {
        DriveStepV2 step = new DriveStepV2();
        step.setPolyline(Arrays.asList(points));
        return step;
    }

    @Test public void missingRouteHasNoGeometry() {
        assertTrue(MapConvertUtils.INSTANCE.convertLatLngList(null).isEmpty());
        assertTrue(MapConvertUtils.INSTANCE.convertLatLngList(new DrivePathV2()).isEmpty());
    }

    @Test public void stepsJoinWithoutRepeatingSharedBoundary() {
        LatLonPoint a = new LatLonPoint(39.9, 116.4);
        LatLonPoint b = new LatLonPoint(39.91, 116.41);
        LatLonPoint c = new LatLonPoint(39.92, 116.42);
        DrivePathV2 path = new DrivePathV2();
        path.setSteps(Arrays.asList(step(a, b), step(b, c)));
        List<LatLng> result = MapConvertUtils.INSTANCE.convertLatLngList(path);
        assertEquals(Arrays.asList(new LatLng(39.9, 116.4),
                new LatLng(39.91, 116.41), new LatLng(39.92, 116.42)), result);
    }

    @Test public void emptyStepsDoNotDropOtherRoadGeometry() {
        DriveStepV2 empty = new DriveStepV2();
        empty.setPolyline(Collections.emptyList());
        DrivePathV2 path = new DrivePathV2();
        path.setSteps(Arrays.asList(empty, step(new LatLonPoint(31.2, 121.4),
                new LatLonPoint(31.3, 121.5)), empty));
        List<LatLng> result = MapConvertUtils.INSTANCE.convertLatLngList(path);
        assertEquals(2, result.size());
        assertEquals(31.2, result.get(0).latitude, 0.0);
        assertEquals(121.4, result.get(0).longitude, 0.0);
    }

    @Test public void nearbyPointsAndLaterRevisitsArePreserved() {
        LatLonPoint a = new LatLonPoint(31.2, 121.4);
        LatLonPoint nearby = new LatLonPoint(31.20000001, 121.40000001);
        DrivePathV2 path = new DrivePathV2();
        path.setSteps(Collections.singletonList(step(a, nearby, a)));
        assertEquals(3, MapConvertUtils.INSTANCE.convertLatLngList(path).size());
    }

    @Test public void walkingAndCyclingKeepTheirOwnAmapRoads() {
        com.amap.api.services.route.WalkStep walkStep = new com.amap.api.services.route.WalkStep();
        walkStep.setRoad("步行道");
        walkStep.setInstruction("通过公园");
        walkStep.setPolyline(Arrays.asList(new LatLonPoint(26.40, 106.60),
                new LatLonPoint(26.405, 106.606), new LatLonPoint(26.41, 106.61)));
        com.amap.api.services.route.WalkPath walk = new com.amap.api.services.route.WalkPath();
        walk.setSteps(Collections.singletonList(walkStep));
        walk.setDistance(1400f);
        walk.setDuration(1000L);

        com.amap.api.services.route.RideStep rideStep = new com.amap.api.services.route.RideStep();
        rideStep.setRoad("自行车道");
        rideStep.setPolyline(Arrays.asList(new LatLonPoint(26.40, 106.60),
                new LatLonPoint(26.409, 106.602), new LatLonPoint(26.41, 106.61)));
        com.amap.api.services.route.RidePath ride = new com.amap.api.services.route.RidePath();
        ride.setSteps(Collections.singletonList(rideStep));
        ride.setDistance(1700f);
        ride.setDuration(400L);

        DrivePathV2 walking = MapConvertUtils.INSTANCE.fromAmapWalk(walk);
        DrivePathV2 cycling = MapConvertUtils.INSTANCE.fromAmapRide(ride);
        assertTrue(MapConvertUtils.INSTANCE.hasRoadGeometry(walking));
        assertTrue(MapConvertUtils.INSTANCE.hasRoadGeometry(cycling));
        assertNotEquals(MapConvertUtils.INSTANCE.convertLatLngList(walking), MapConvertUtils.INSTANCE.convertLatLngList(cycling));
        assertEquals(1400f, walking.getDistance(), 0f);
        assertEquals(400L, cycling.getDuration());
        assertEquals("步行道", walking.getSteps().get(0).getRoad());
        assertEquals("通过公园", walking.getSteps().get(0).getInstruction());
    }

    @Test public void baiduWalkingAndCyclingGeometryAndTotalsArePreserved() {
        com.baidu.mapapi.search.route.WalkingRouteLine.WalkingStep walkingStep =
                new com.baidu.mapapi.search.route.WalkingRouteLine.WalkingStep();
        com.baidu.mapapi.search.route.BikingRouteLine.BikingStep cyclingStep =
                new com.baidu.mapapi.search.route.BikingRouteLine.BikingStep();
        List<com.baidu.mapapi.model.LatLng> road = Arrays.asList(
                new com.baidu.mapapi.model.LatLng(26.4, 106.6),
                new com.baidu.mapapi.model.LatLng(26.405, 106.606),
                new com.baidu.mapapi.model.LatLng(26.41, 106.61));
        walkingStep.setWayPoints(road);
        cyclingStep.setWayPoints(road);
        walkingStep.setInstructions("沿步行道前行");
        com.baidu.mapapi.search.route.WalkingRouteLine walk = new com.baidu.mapapi.search.route.WalkingRouteLine();
        walk.setSteps(Collections.singletonList(walkingStep));
        walk.setDistance(1500);
        walk.setDuration(1200);
        com.baidu.mapapi.search.route.BikingRouteLine ride = new com.baidu.mapapi.search.route.BikingRouteLine();
        ride.setSteps(Collections.singletonList(cyclingStep));
        ride.setDistance(1500);
        ride.setDuration(300);
        DrivePathV2 walking = MapConvertUtils.INSTANCE.fromBaiduWalk(walk);
        DrivePathV2 cycling = MapConvertUtils.INSTANCE.fromBaiduRide(ride);
        assertEquals(3, MapConvertUtils.INSTANCE.convertLatLngList(walking).size());
        assertEquals(MapConvertUtils.INSTANCE.convertLatLngList(walking), MapConvertUtils.INSTANCE.convertLatLngList(cycling));
        assertEquals(1500f, walking.getDistance(), 0f);
        assertEquals(300L, cycling.getDuration());
        assertEquals("沿步行道前行", walking.getSteps().get(0).getInstruction());
    }

    private DrivePathV2 path(double... latitudes) {
        DrivePathV2 path = new DrivePathV2();
        LatLonPoint[] points = new LatLonPoint[latitudes.length];
        for (int i = 0; i < latitudes.length; i++) points[i] = new LatLonPoint(latitudes[i], 106.6);
        path.setSteps(Collections.singletonList(step(points)));
        path.setDistance(100);
        path.setDuration(20L);
        return path;
    }

    @Test public void allWaypointLegsJoinInOrderWithoutDuplicateBoundary() {
        DrivePathV2 merged = MapConvertUtils.INSTANCE.mergeRoadSegments(Arrays.asList(
                path(26.4, 26.401, 26.402), path(26.402, 26.403), path(26.403, 26.404)));
        List<LatLng> road = MapConvertUtils.INSTANCE.convertLatLngList(merged);
        assertEquals(5, road.size());
        assertEquals(26.4, road.get(0).latitude, 0f);
        assertEquals(26.404, road.get(4).latitude, 0f);
        assertEquals(300f, merged.getDistance(), 0f);
        assertEquals(60L, merged.getDuration());
    }

    @Test public void missingLegNeverProducesAPartialRoute() {
        assertThrows(IllegalArgumentException.class, () -> MapConvertUtils.INSTANCE.mergeRoadSegments(
                Arrays.asList(path(26.4, 26.401), new DrivePathV2(), path(26.402, 26.403))));
        assertThrows(IllegalArgumentException.class, () -> MapConvertUtils.INSTANCE.mergeRoadSegments(Collections.emptyList()));
    }

    @Test public void disconnectedWaypointLegsAreRejectedInsteadOfStraightLineFallback() {
        assertThrows(IllegalArgumentException.class, () -> MapConvertUtils.INSTANCE.mergeRoadSegments(
                Arrays.asList(path(26.4, 26.401), path(26.5, 26.501))));
    }

    @Test public void unchangedPointAndInvalidRawCoordinatesAreNotUsableRoads() {
        assertFalse(MapConvertUtils.INSTANCE.hasRoadGeometry(path(26.4, 26.4)));
        assertFalse(MapConvertUtils.INSTANCE.hasRoadGeometry(path(26.4, Double.NaN)));
        assertFalse(MapConvertUtils.INSTANCE.hasRoadGeometry(path(26.4, 91.0)));
    }

    @Test public void savedRoadRestorationPreservesBendsAndReturnVisits() {
        List<com.laozhang.mockgps.model.RouteCoordinate> saved = Arrays.asList(
                new com.laozhang.mockgps.model.RouteCoordinate(26.4, 106.6),
                new com.laozhang.mockgps.model.RouteCoordinate(26.405, 106.605),
                new com.laozhang.mockgps.model.RouteCoordinate(26.41, 106.6),
                new com.laozhang.mockgps.model.RouteCoordinate(26.4, 106.6));
        DrivePathV2 restored = MapConvertUtils.INSTANCE.fromCoordinates(saved);
        assertEquals(4, MapConvertUtils.INSTANCE.convertLatLngList(restored).size());
        assertTrue(restored.getDistance() > 1000);
        assertTrue(MapConvertUtils.INSTANCE.hasRoadGeometry(restored));
    }
}
