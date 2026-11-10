package com.laozhang.mockgps.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Long text scrolls inside the card while short confirmations retain their compact height. */
class DialogScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.55).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: cap
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(size, cap), MeasureSpec.AT_MOST))
    }
}
