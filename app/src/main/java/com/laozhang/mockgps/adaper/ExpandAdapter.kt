package com.laozhang.mockgps.adaper

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.castiel.common.base.BaseListAdapter
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.ItemTitleBinding
import com.laozhang.mockgps.model.ExpandModel

/**
 * @author jiayu.liu
 */
class ExpandAdapter :
    BaseListAdapter<ExpandModel, ExpandAdapter.ViewHolder>(object :
        DiffUtil.ItemCallback<ExpandModel>() {
        override fun areItemsTheSame(oldItem: ExpandModel, newItem: ExpandModel): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: ExpandModel, newItem: ExpandModel): Boolean {
            return oldItem.msg == newItem.msg
        }

    }) {

    class ViewHolder(val binding: ItemTitleBinding) : RecyclerView.ViewHolder(binding.root)


    override fun onBindViewHolderModel(holder: ViewHolder, position: Int) {
        getItem(position)?.run {
            holder.binding.title = title
            holder.binding.msg = msg
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding: ItemTitleBinding? = DataBindingUtil.bind(
            LayoutInflater.from(parent.context).inflate(R.layout.item_title, parent, false)
        )
        return ViewHolder(binding!!)
    }


}