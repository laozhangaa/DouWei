package com.laozhang.mockgps.widget

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.laozhang.mockgps.R
import kotlinx.android.synthetic.main.dialog_delete_history.btn_delete_cancel
import kotlinx.android.synthetic.main.dialog_delete_history.btn_delete_confirm
import kotlinx.android.synthetic.main.dialog_delete_history.tv_delete_message
import kotlinx.android.synthetic.main.dialog_delete_history.tv_delete_title

/** Compact confirmation card used by the history list's long-press action. */
class DeleteHistoryDialog(
    context: Context,
    title: String,
    clearAll: Boolean = false,
    recordType: String = "历史记录",
    onConfirm: () -> Unit
) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LayoutInflater.from(context)
            .inflate(R.layout.dialog_delete_history, null, false)
        setContentView(content)
        if (clearAll) {
            tv_delete_title.text = "清空历史记录"
            tv_delete_message.text = "将清空当前模式的全部历史记录，删除后无法恢复。"
            btn_delete_confirm.text = "清空"
        } else {
            tv_delete_title.text = "删除$recordType"
            tv_delete_message.text = "确定删除这条${recordType}吗？\n$title"
        }
        btn_delete_cancel.setOnClickListener { dismiss() }
        btn_delete_confirm.setOnClickListener {
            onConfirm()
            dismiss()
        }
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            attributes = attributes.apply {
                width = ScreenUtils.getScreenWidth() - ConvertUtils.dp2px(36f)
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                dimAmount = 0.38f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
