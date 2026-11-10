package com.laozhang.mockgps.widget

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import com.blankj.utilcode.util.ClickUtils
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.DialogInputSpeedBinding
import com.laozhang.mockgps.utils.MMKVUtils
import kotlinx.android.synthetic.main.dialog_input_speed.view.*

/**
 * @author jiayu.liu
 */
class InputSpeed(context: Context, private val onChanged: (Int) -> Unit = {}) : Dialog(context) {
    init {
        DataBindingUtil.bind<DialogInputSpeedBinding>(
            LayoutInflater.from(context)
                .inflate(R.layout.dialog_input_speed, null, false)
        )?.let { it ->
            setContentView(it.root)
            window?.run {
                setBackgroundDrawableResource(R.color.transparent);
                val lp = attributes;
                lp.width = ScreenUtils.getScreenWidth() - ConvertUtils.dp2px(20f)
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                attributes = lp
            }
            it.editSpeed.setText(MMKVUtils.getSpeed().toString())
            it.editSpeed.setSelection(it.editSpeed.text?.length ?: 0)
            ClickUtils.applySingleDebouncing(it.btnConfirm) { _ ->
                val speed = it.editSpeed.text.toString().toIntOrNull()
                if (speed == null || speed !in 1..500) {
                    it.editSpeed.error = "请输入 1～500 的整数"
                    return@applySingleDebouncing
                }
                MMKVUtils.setSpeed(speed)
                onChanged(speed)
                dismiss()
            }
        }
    }
}
