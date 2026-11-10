package com.laozhang.mockgps.widget

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ToastUtils
import com.laozhang.mockgps.R
import com.laozhang.mockgps.manager.MapProvider
import com.laozhang.mockgps.manager.MapSdkCredentials
import com.laozhang.mockgps.manager.FloatingViewManger
import com.laozhang.mockgps.server.SimulationStateStore
import kotlinx.android.synthetic.main.dialog_map_service.*

/** Rounded provider/credential card shared by 扩展功能 and 模拟功能设置. */
class MapServiceDialog(
    context: Context,
    private val onSaved: () -> Unit = {}
) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(LayoutInflater.from(context).inflate(R.layout.dialog_map_service, null, false))
        rb_amap.isChecked = MapProvider.current == MapProvider.AMAP
        rb_baidu.isChecked = MapProvider.current == MapProvider.BAIDU
        et_amap_key.setText(MapSdkCredentials.amapKey)
        et_baidu_ak.setText(MapSdkCredentials.baiduAk)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                til_amap_key.error = null
                til_baidu_ak.error = null
                tv_map_error.visibility = android.view.View.GONE
                updateApplyLabel()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        et_amap_key.addTextChangedListener(watcher)
        et_baidu_ak.addTextChangedListener(watcher)
        rg_map_provider.setOnCheckedChangeListener { _, _ ->
            til_amap_key.error = null
            til_baidu_ak.error = null
            updateProviderFields()
        }
        updateProviderFields()
        updateApplyLabel()
        btn_map_cancel.setOnClickListener { dismiss() }
        btn_map_save.setOnClickListener {
            val provider = if (rb_amap.isChecked) MapProvider.AMAP else MapProvider.BAIDU
            val amap = et_amap_key.text?.toString().orEmpty().trim()
            val baidu = et_baidu_ak.text?.toString().orEmpty().trim()
            if (amap.any { it.isWhitespace() } || (provider == MapProvider.AMAP && amap.isEmpty())) {
                rb_amap.isChecked = true
                til_amap_key.error = "请输入完整的高德 Android Key，不含空格"
                et_amap_key.requestFocus()
                return@setOnClickListener
            }
            if (baidu.any { it.isWhitespace() } || (provider == MapProvider.BAIDU && baidu.isEmpty())) {
                rb_baidu.isChecked = true
                til_baidu_ak.error = "请输入完整的百度 Android AK，不含空格"
                et_baidu_ak.requestFocus()
                return@setOnClickListener
            }
            val restart = MapSdkCredentials.needsRestart(amap, baidu)
            if (restart && SimulationStateStore.current.hasSession) {
                showError("请先停止当前模拟，再修改已使用的凭证")
                return@setOnClickListener
            }
            if (!MapSdkCredentials.save(amap, baidu)) {
                showError("保存失败，请重试")
                return@setOnClickListener
            }
            MapProvider.current = provider
            dismiss()
            if (restart) AppUtils.relaunchApp(true)
            else {
                onSaved()
                ToastUtils.showShort("地图服务已保存")
            }
        }
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            attributes = attributes.apply {
                width = minOf(ScreenUtils.getScreenWidth() - ConvertUtils.dp2px(36f), ConvertUtils.dp2px(360f))
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                dimAmount = 0.38f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        setOnShowListener { FloatingViewManger.INSTANCE.setTemporarilyHidden(true) }
        setOnDismissListener { FloatingViewManger.INSTANCE.setTemporarilyHidden(false) }
    }

    private fun updateApplyLabel() {
        val restart = MapSdkCredentials.needsRestart(et_amap_key.text.toString(), et_baidu_ak.text.toString())
        btn_map_save.text = if (restart) "保存并重启" else "保存"
        tv_map_note.text = if (restart) "修改已使用的凭证需要重启豆位，重新加载地图服务。"
            else "填写所选地图的 Android 凭证。两家配置分别保存，仅保存在本机，安装包不内置。"
    }

    private fun updateProviderFields() {
        val useAmap = rb_amap.isChecked
        til_amap_key.visibility = if (useAmap) View.VISIBLE else View.GONE
        til_baidu_ak.visibility = if (useAmap) View.GONE else View.VISIBLE
        // Switching services must not leave keyboard focus in a hidden field.
        if (useAmap && et_baidu_ak.hasFocus()) et_amap_key.requestFocus()
        if (!useAmap && et_amap_key.hasFocus()) et_baidu_ak.requestFocus()
    }

    private fun showError(message: String) {
        tv_map_error.text = message
        tv_map_error.visibility = android.view.View.VISIBLE
    }
}
