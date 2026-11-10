package com.laozhang.mockgps.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Observer
import com.laozhang.mockgps.R
import com.laozhang.mockgps.model.NavigationProgress
import com.laozhang.mockgps.model.SimulationStatus
import com.laozhang.mockgps.model.SimulationState
import com.laozhang.mockgps.server.GpsService
import com.laozhang.mockgps.server.SimulationStateStore
import java.util.Locale

object NavigationProgressText {
    fun format(progress: NavigationProgress): String {
        val state = when {
            progress.finished -> "已到终点"
            progress.stopRemainingSeconds > 0 -> "路口停车（模拟）· 剩余 ${progress.stopRemainingSeconds} 秒"
            else -> String.format(Locale.CHINA, "当前 %.1f km/h · 预计剩余 %d 分钟", progress.speedKmH,
                kotlin.math.ceil(progress.remainingSeconds / 60.0).toLong())
        }
        return String.format(Locale.CHINA, "已走 %.2f km · 剩余 %.2f km\n%s", progress.traveledMeters / 1000,
            (progress.totalMeters - progress.traveledMeters).coerceAtLeast(0.0) / 1000, state)
    }
}

class NavigationProgressDialog(context: Context) : FavoriteCardDialog(context, R.layout.dialog_navigation_progress) {
    private val seek = findViewById<SeekBar>(R.id.seek_navigation_progress)
    private val text = findViewById<TextView>(R.id.tv_navigation_progress_info)
    private var dragging = false
    private val observer = Observer<NavigationProgress?> { progress ->
        seek.isEnabled = progress != null && SimulationStateStore.current.hasSession
        findViewById<View>(R.id.btn_navigation_restart).isEnabled = seek.isEnabled
        text.text = progress?.let {
            val paused = SimulationStateStore.current.status == SimulationStatus.PAUSED
            (if (paused) "已暂停\n" else "") + NavigationProgressText.format(if (paused) it.copy(speedKmH = 0f) else it)
        } ?: "当前没有正在模拟的导航路线"
        if (!dragging && progress != null) seek.progress = if (progress.totalMeters > 0)
            (progress.traveledMeters / progress.totalMeters * 1000).toInt() else 1000
    }
    private val stateObserver = Observer<SimulationState> { observer.onChanged(SimulationStateStore.progress.value) }
    init {
        findViewById<View>(R.id.btn_navigation_progress_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_navigation_restart).setOnClickListener { seek(0.0) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) { dragging = false; seek((bar?.progress ?: 0) / 1000.0) }
        })
    }
    private fun seek(fraction: Double) {
        if (SimulationStateStore.current.hasSession) context.startService(Intent(context, GpsService::class.java)
            .setAction(GpsService.ACTION_SEEK).putExtra("seek_fraction", fraction))
    }
    override fun show() {
        super.show()
        SimulationStateStore.progress.observeForever(observer)
        SimulationStateStore.state.observeForever(stateObserver)
    }
    override fun dismiss() {
        SimulationStateStore.progress.removeObserver(observer)
        SimulationStateStore.state.removeObserver(stateObserver)
        super.dismiss()
    }
}
