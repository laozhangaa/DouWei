package com.laozhang.mockgps.widget

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.laozhang.mockgps.R
import com.laozhang.mockgps.manager.DrivingPolicyMapper
import com.laozhang.mockgps.manager.MapProvider
import com.laozhang.mockgps.model.NavigationOptions
import com.laozhang.mockgps.model.DrivingPreference
import com.laozhang.mockgps.model.TravelMode
import com.laozhang.mockgps.utils.MMKVUtils

class NavigationOptionsDialog(context: Context, mode: TravelMode,
    private val onSaved: (NavigationOptions) -> Unit
) : FavoriteCardDialog(context, R.layout.dialog_navigation_options) {
    private val original = MMKVUtils.getNavigationOptions()
    private val provider = MapProvider.current
    private val group = findViewById<RadioGroup>(R.id.rg_driving_preference)
    private val motion = findViewById<RadioGroup>(R.id.rg_motion_mode)
    private val stops = findViewById<CheckBox>(R.id.cb_junction_stops)
    private val seconds = findViewById<EditText>(R.id.et_stop_seconds)
    private val error = findViewById<TextView>(R.id.tv_navigation_options_error)
    private val choices = mutableMapOf<Int, DrivingPreference>()
    init {
        findViewById<TextView>(R.id.tv_options_provider).text = when {
            mode != TravelMode.DRIVING -> "路线按${mode.label}道路规划；驾车偏好仅在驾车时生效"
            provider == MapProvider.BAIDU -> "百度地图 · 不走高速、高速优先由高德提供"
            else -> "高德地图 · 选择本次驾车路线偏好"
        }
        DrivingPreference.values().forEach { preference ->
            val button = AppCompatRadioButton(context).apply {
                id = View.generateViewId(); choices[id] = preference
                text = preference.label + if (preference.amapOnly && provider == MapProvider.BAIDU) "（高德）" else ""
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.home_text_primary))
                buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.master_color))
                minHeight = ConvertUtils.dp2px(44f)
                isEnabled = mode == TravelMode.DRIVING && DrivingPolicyMapper.supports(provider, preference)
                alpha = if (isEnabled) 1f else 0.45f
            }
            group.addView(button)
            if (preference == original.drivingPreference) group.check(button.id)
        }
        if (mode != TravelMode.DRIVING) group.visibility = View.GONE
        motion.check(if (original.realistic) R.id.rb_motion_realistic else R.id.rb_motion_constant)
        stops.isChecked = original.simulateStops
        seconds.setText(original.stopSeconds.toString())
        fun updateEnabled() {
            stops.isEnabled = motion.checkedRadioButtonId == R.id.rb_motion_realistic && mode == TravelMode.DRIVING
            stops.alpha = if (stops.isEnabled) 1f else 0.5f
            seconds.isEnabled = stops.isEnabled && stops.isChecked
        }
        motion.setOnCheckedChangeListener { _, _ -> updateEnabled() }
        stops.setOnCheckedChangeListener { _, _ -> updateEnabled() }
        updateEnabled()
        findViewById<View>(R.id.btn_navigation_options_cancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_navigation_options_save).setOnClickListener {
            try {
                val preference = choices[group.checkedRadioButtonId] ?: original.drivingPreference
                require(mode != TravelMode.DRIVING || DrivingPolicyMapper.supports(provider, preference)) { "请选择百度支持的路线偏好，或到地图服务切换高德" }
                val realistic = motion.checkedRadioButtonId == R.id.rb_motion_realistic
                val value = NavigationOptions(preference.name, realistic, realistic && stops.isChecked,
                    seconds.text.toString().toIntOrNull() ?: error("停车时间请填写 5～120 秒"))
                value.validate()
                MMKVUtils.setNavigationOptions(value)
                onSaved(value)
                dismiss()
            } catch (failure: Exception) {
                error.text = failure.message ?: "设置保存失败，请重试"
                error.visibility = View.VISIBLE
            }
        }
    }
    override fun show() {
        super.show()
        window?.setLayout(window?.attributes?.width ?: -1, (ScreenUtils.getScreenHeight() * 0.86).toInt())
    }
}
