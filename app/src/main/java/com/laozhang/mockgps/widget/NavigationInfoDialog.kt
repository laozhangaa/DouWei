package com.laozhang.mockgps.widget

import android.content.Context
import android.view.View
import android.widget.TextView
import com.laozhang.mockgps.R

open class NavigationInfoDialog(context: Context, title: String, message: String,
    positive: String? = null, neutral: String? = null,
    onPositive: (NavigationInfoDialog) -> Unit = {}, onNeutral: (NavigationInfoDialog) -> Unit = {}
) : FavoriteCardDialog(context, R.layout.dialog_navigation_info) {
    private val text = findViewById<TextView>(R.id.tv_navigation_info_message)
    init {
        findViewById<TextView>(R.id.tv_navigation_info_title).text = title
        text.text = message
        findViewById<View>(R.id.btn_navigation_info_cancel).setOnClickListener { dismiss() }
        findViewById<TextView>(R.id.btn_navigation_info_confirm).apply {
            visibility = if (positive == null) View.GONE else View.VISIBLE
            this.text = positive
            setOnClickListener { onPositive(this@NavigationInfoDialog) }
        }
        findViewById<TextView>(R.id.btn_navigation_info_neutral).apply {
            visibility = if (neutral == null) View.GONE else View.VISIBLE
            this.text = neutral
            setOnClickListener { onNeutral(this@NavigationInfoDialog) }
        }
    }
    fun message(value: String) { if (text.text.toString() != value) text.text = value }
    fun busy(value: Boolean) {
        setCancelable(!value)
        for (id in listOf(R.id.btn_navigation_info_cancel, R.id.btn_navigation_info_confirm, R.id.btn_navigation_info_neutral)) {
            findViewById<View>(id).isEnabled = !value
        }
    }
}
