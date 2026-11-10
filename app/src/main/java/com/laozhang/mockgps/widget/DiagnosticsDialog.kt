package com.laozhang.mockgps.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.ToastUtils
import com.laozhang.mockgps.manager.NavigationRecoveryStore
import com.laozhang.mockgps.server.InjectionDiagnostics
import com.laozhang.mockgps.server.SimulationStateStore
import com.laozhang.mockgps.utils.Utils

class DiagnosticsDialog(context: Context) : NavigationInfoDialog(context, "运行诊断", "正在读取状态…",
    positive = "权限设置", neutral = "省电设置",
    onPositive = { dialog ->
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            .onFailure { ToastUtils.showShort("请在系统设置中打开豆位应用详情") }
        dialog.dismiss()
    }, onNeutral = { dialog ->
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { ToastUtils.showShort("请在系统设置中检查豆位的省电限制") }
        dialog.dismiss()
    }) {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (!isShowing) return
            val data = InjectionDiagnostics
            val age = if (data.lastAcceptedNanos > 0) (SystemClock.elapsedRealtimeNanos() - data.lastAcceptedNanos) / 1_000_000L else null
            val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val locationEnabled = runCatching {
                if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
                else location.isProviderEnabled("gps") || location.isProviderEnabled("network")
            }.getOrDefault(false)
            val background = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            val unlimited = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(context.packageName)
            val accepted = when {
                !data.active -> "未运行"
                age == null -> "等待首次写入"
                age > 1_000L -> "更新延迟"
                else -> "系统已接受模拟位置"
            }
            message(buildString {
                append("定位写入：$accepted\n")
                append("模拟应用：${if (Utils.isAllowMockLocation(context)) "已选择豆位" else "请在开发者选项选择豆位"}\n")
                append("系统定位：${if (locationEnabled) "已开启" else "未开启"}\n")
                append("精确位置：${if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "已授权" else "未授权"}\n")
                append("后台定位：${if (background) "已授权" else "未授权"}\n\n")
                append("写入定位源：${data.providerCount} 个\n")
                append("最近写入：${age?.let { "$it 毫秒前" } ?: "暂无"}\n")
                append("最近写入间隔：${data.lastIntervalMillis} 毫秒\n")
                append("省电模式：${if (power.isPowerSaveMode) "开启" else "关闭"}\n")
                append("电池优化：${if (unlimited) "不受限制" else "受系统管理"}\n")
                (data.lastError ?: SimulationStateStore.current.error)?.let { append("\n最近异常：$it\n") }
                NavigationRecoveryStore.lastError?.let { append("\n恢复记录：$it\n") }
                append("\n这里只确认 Android 是否接受模拟位置；其他 App 的缓存、联网定位与地图显示可能存在延迟。锁屏后中断时，请检查后台权限和省电限制。")
            })
            handler.postDelayed(this, 1_000L)
        }
    }
    override fun show() { super.show(); handler.post(refresh) }
    override fun dismiss() { handler.removeCallbacks(refresh); super.dismiss() }
}
