package com.laozhang.mockgps.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri

import android.os.Build
import android.os.Process
import android.provider.Settings
import com.amap.api.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.collections.ArrayList


/**
 * @author jiayu.liu
 */
object Utils {

    fun isAllowMockLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT <= 22) { //6.0以下
            return Settings.Secure.getInt(
                context.applicationContext.getContentResolver(),
                Settings.Secure.ALLOW_MOCK_LOCATION,
                0
            ) != 0
        }
        // Permission checks must not remove the provider of an active simulation.
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * @param array 原数据
     * @param speed 速度  单位：M/S
     *
     */
    fun latLngToSpeedLatLng(array: ArrayList<LatLng>?, speed: Double): ArrayList<LatLng> {
        val latLngList = arrayListOf<LatLng>()
        runBlocking(Dispatchers.IO) {
            if (array == null || array.isEmpty() || array.size <= 1) {
                return@runBlocking latLngList
            }
            //单位：M/S
            val size = array.size
            //当前经纬度
            var currentLatLng = array[0]
            latLngList.add(currentLatLng)
            //起点
            for (index in 1 until size) {
                //节点经纬度
                val latLng = array[index]
                var dis = CalculationLogLatDistance.getDistance(currentLatLng, latLng)
                var yaw = CalculationLogLatDistance.getYaw(currentLatLng, latLng)
                if (dis < speed) {
                    currentLatLng = latLng
                    latLngList.add(latLng)
                    continue
                }
                while (dis > speed) {
                    val nextLonLat =
                        CalculationLogLatDistance.getNextLonLat(currentLatLng, yaw, speed)
                    dis = CalculationLogLatDistance.getDistance(currentLatLng, latLng)
                    yaw = CalculationLogLatDistance.getYaw(currentLatLng, latLng)
                    if (dis < speed) {
                        currentLatLng = latLng
                        latLngList.add(currentLatLng)
                    } else {
                        currentLatLng = nextLonLat
                        latLngList.add(currentLatLng)
                    }
                }
            }
        }.also {
            return latLngList
        }
    }

    fun checkFloatWindow(context: Context?): Boolean {
        if (context == null) {
            throw NullPointerException("Utils#checkFloatWindow: context is null");
        }
        //悬浮窗权限判断
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context.applicationContext)) {
                return false
            }
        }
        return true
    }

    /**
     * 跳转到浏览器
     */
    fun openBrowser(context: Context, url: String?, failure: () -> Unit) {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.data = Uri.parse(url)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(Intent.createChooser(intent, "选择浏览器"))
        } else {
            failure.invoke()
        }
    }

}
