package com.laozhang.mockgps.widget

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laozhang.mockgps.R
import com.laozhang.mockgps.model.ProvinceCityData
import kotlin.math.roundToInt

class ProvinceCityPicker(private val activity: Activity,
                         private val onVisibilityChanged: (Boolean) -> Unit = {},
                         private val onSelected: (ProvinceCityData.City?) -> Unit) {
    private val provinces by lazy {
        activity.assets.open("province_cities.json").bufferedReader().use(ProvinceCityData::read)
    }
    private var dialog: Dialog? = null
    private var provinceIndex = 0

    fun show(selectedCityName: String? = null) {
        dismiss()
        val selectedProvince = provinces.indexOfFirst { province ->
            province.cities.any { it.name == selectedCityName }
        }
        if (selectedProvince >= 0) provinceIndex = selectedProvince
        val picker = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_province_city, null, false)
        picker.setContentView(content)
        val provinceList = content.findViewById<RecyclerView>(R.id.province_list)
        val cityList = content.findViewById<RecyclerView>(R.id.city_list)
        provinceList.layoutManager = LinearLayoutManager(activity)
        cityList.layoutManager = LinearLayoutManager(activity)
        provinceList.itemAnimator = null
        cityList.itemAnimator = null
        fun showCities() {
            val cities = provinces[provinceIndex].cities
            val cityAdapter = RegionAdapter(cities.map { it.name },
                cities.indexOfFirst { it.name == selectedCityName }) { index ->
                picker.dismiss()
                onSelected(cities[index])
            }
            cityList.adapter = cityAdapter
            cityList.scrollToPosition(cityAdapter.selectedIndex.coerceAtLeast(0))
        }
        val provinceAdapter = RegionAdapter(provinces.map { it.name }, provinceIndex) { index ->
            provinceIndex = index
            (provinceList.adapter as RegionAdapter).select(index)
            showCities()
        }
        provinceList.adapter = provinceAdapter
        provinceList.scrollToPosition(provinceIndex)
        showCities()
        content.findViewById<View>(R.id.close_picker).setOnClickListener { picker.dismiss() }
        content.findViewById<View>(R.id.search_nationwide).setOnClickListener {
            picker.dismiss()
            onSelected(null)
        }
        picker.setOnDismissListener {
            if (dialog === picker) dialog = null
            onVisibilityChanged(false)
        }
        dialog = picker
        picker.show()
        // Bound the window in pixels; AutoSize can change the density used by platform dialogs.
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val width = (decor.width.takeIf { it > 0 } ?: metrics.widthPixels) -
            (insets?.left ?: 0) - (insets?.right ?: 0)
        val height = (decor.height.takeIf { it > 0 } ?: metrics.heightPixels) -
            (insets?.top ?: 0) - (insets?.bottom ?: 0)
        val margin = (16f * metrics.density).roundToInt()
        picker.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setGravity(Gravity.CENTER)
            setDimAmount(0.28f)
            setLayout((width - margin * 2).coerceAtLeast(1),
                minOf((height * 0.68f).roundToInt(), (520 * metrics.density).roundToInt(),
                    (height - margin * 2).coerceAtLeast(1)))
        }
        onVisibilityChanged(true)
    }

    private class RegionAdapter(private val names: List<String>, var selectedIndex: Int,
                                private val onClick: (Int) -> Unit) : RecyclerView.Adapter<RegionAdapter.Holder>() {
        fun select(index: Int) {
            val previous = selectedIndex
            selectedIndex = index
            if (previous >= 0) notifyItemChanged(previous)
            notifyItemChanged(index)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_region, parent, false) as TextView)
        override fun getItemCount() = names.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val selected = position == selectedIndex
            holder.text.apply {
                text = names[position]
                isSelected = selected
                setTextColor(if (selected) Color.rgb(63, 129, 193) else Color.rgb(64, 81, 102))
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setBackgroundColor(if (selected) Color.rgb(229, 239, 250) else Color.TRANSPARENT)
                setOnClickListener { onClick(position) }
            }
        }
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }

    fun dismiss() { dialog?.dismiss(); dialog = null }
}
