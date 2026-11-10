package com.laozhang.mockgps.adaper

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.ItemNaviPoiCardBinding
import com.laozhang.mockgps.model.PoiInfoModel

/**
 * @author jiayu.liu
 */
class MultiplePoiAdapter : RecyclerView.Adapter<MultiplePoiAdapter.ViewHolder>() {
    var list: MutableList<PoiInfoModel> = arrayListOf()
    var clickListener: OnItemClickListener? = null
    var onPointsChanged: (() -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_navi_poi_card, parent, false)
        return ViewHolder(DataBindingUtil.bind(view)!!)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val item = list[position]
        val isWay = position > 0 && position < list.lastIndex
        val role = when (position) { 0 -> "起点"; list.lastIndex -> "终点"; else -> "途经点 $position" }
        viewHolder.binding.isWay = isWay
        viewHolder.binding.tvRouteRole.text = role
        viewHolder.binding.routeDot.setBackgroundResource(when (position) {
            0 -> R.drawable.bg_route_start
            list.lastIndex -> R.drawable.bg_route_end
            else -> R.drawable.bg_route_waypoint
        })
        viewHolder.binding.poiName = item.name?.takeIf { it.isNotBlank() }
            ?: if (item.latLng != null) "%.6f, %.6f".format(item.latLng!!.latitude, item.latLng!!.longitude)
            else when (position) { 0 -> "选择起点"; list.lastIndex -> "选择终点"; else -> "选择途经地点" }
        viewHolder.binding.tvNaviNameEnd.setTextColor(ContextCompat.getColor(viewHolder.itemView.context,
            if (item.latLng == null) R.color.black_50 else R.color.black_80))

        viewHolder.binding.ivRemove.setOnClickListener {
            val index = viewHolder.adapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnClickListener
            if (index > 0 && index < list.lastIndex) {
                list.removeAt(index)
                notifyDataSetChanged()
                onPointsChanged?.invoke()
            } else {
                clickListener?.onItemClick(viewHolder.binding.root, index)
            }
        }
        clickListener?.let { listener ->
            viewHolder.binding.root.setOnClickListener {
                val index = viewHolder.adapterPosition
                if (index != RecyclerView.NO_POSITION) listener.onItemClick(viewHolder.binding.root, index)
            }
            viewHolder.binding.ivMove.setOnTouchListener { _, event ->
                if (event?.action == MotionEvent.ACTION_DOWN) {
                    listener.onItemMove(viewHolder)
                    true
                } else {
                    false
                }
            }

        }
    }

    fun submitList(list: List<PoiInfoModel>) {
        this.list.clear()
        this.list.addAll(list)
        notifyDataSetChanged()
        onPointsChanged?.invoke()
    }

    fun currentList(): MutableList<PoiInfoModel> {
        return list
    }

    override fun getItemCount(): Int {
        return list.size
    }

    class ViewHolder(val binding: ItemNaviPoiCardBinding) : RecyclerView.ViewHolder(binding.root)


    interface OnItemClickListener {
        fun onItemClick(view: View, position: Int)

        fun onItemMove(viewHolder: RecyclerView.ViewHolder)
    }
}
