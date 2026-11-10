package com.laozhang.mockgps.widget

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.laozhang.mockgps.R
import com.laozhang.mockgps.model.TravelMode
import com.laozhang.mockgps.utils.MMKVUtils

class NaviPopupWindow(
    context: Context, mode: TravelMode = TravelMode.DRIVING,
    private val onChanged: (Int) -> Unit = {}
) : PopupWindow(context) {
    private val label: TextView
    private val seekBar: SeekBar
    init {
        width = minOf(ConvertUtils.dp2px(260f), ScreenUtils.getScreenWidth() - ConvertUtils.dp2px(36f))
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isFocusable = true
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        contentView = LayoutInflater.from(context).inflate(R.layout.popupwindow_navi_setting, null, false)
        label = contentView.findViewById(R.id.tv_speed)
        seekBar = contentView.findViewById(R.id.seekbar)
        seekBar.max = when (mode) {
            TravelMode.DRIVING -> 119
            TravelMode.CYCLING -> 49
            TravelMode.WALKING -> 14
            TravelMode.RUNNING -> 29
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Setting the thumb when opening must never overwrite a saved custom speed.
                if (!fromUser) return
                val speed = progress + 1
                label.text = "当前速度：$speed km/h"
                MMKVUtils.setSpeed(speed)
                onChanged(speed)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        contentView.findViewById<View>(R.id.btn_input_speed).setOnClickListener {
            dismiss()
            InputSpeed(context, onChanged).show()
        }
    }
    fun show(view: View) {
        val speed = MMKVUtils.getSpeed().coerceIn(1, 500)
        seekBar.progress = (speed - 1).coerceIn(0, seekBar.max)
        label.text = "当前速度：$speed km/h"
        showAsDropDown(view, 0, ConvertUtils.dp2px(6f), Gravity.END)
    }
}
