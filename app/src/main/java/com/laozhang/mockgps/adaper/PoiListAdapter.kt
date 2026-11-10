package com.laozhang.mockgps.adaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.laozhang.mockgps.manager.PlaceSuggestion
import com.laozhang.mockgps.R
import kotlinx.android.synthetic.main.item_poiinfo.view.*

class PoiListAdapter : ListAdapter<PlaceSuggestion, PoiListAdapter.ViewHolder>(object : DiffUtil.ItemCallback<PlaceSuggestion>() {
    override fun areItemsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion) =
        oldItem.uid == newItem.uid && oldItem.point == newItem.point
    override fun areContentsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion) =
        oldItem.name == newItem.name && oldItem.district == newItem.district &&
        oldItem.address == newItem.address
}) {
    private var listener: OnItemClickListener? = null
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_poiinfo, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val poi = getItem(position)
        holder.itemView.tv_item_poi_name.text = poi.name
        holder.itemView.tv_item_poi_address.text = listOfNotNull(poi.district, poi.address)
            .filter { it.isNotBlank() }.joinToString(" ")
        holder.itemView.setOnClickListener { listener?.onItemClick(poi) }
    }
    fun setData(list: List<PlaceSuggestion>?) { submitList(list?.toList()) }
    fun setOnItemClickListener(listener: OnItemClickListener?) { this.listener = listener }
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    interface OnItemClickListener { fun onItemClick(poiInfo: PlaceSuggestion) }
}
