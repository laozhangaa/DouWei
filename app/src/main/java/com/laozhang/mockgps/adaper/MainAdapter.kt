package com.laozhang.mockgps.adaper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.LayoutMainCardHeaderBinding
import com.laozhang.mockgps.model.MockMessageModel
import com.laozhang.mockgps.model.NaviType
import com.laozhang.mockgps.model.PoiInfoModel
import com.laozhang.mockgps.model.PoiInfoType
import com.laozhang.mockgps.model.SimulationState
import com.laozhang.mockgps.model.SimulationStatus
import com.laozhang.mockgps.model.TravelMode
import com.laozhang.mockgps.model.NavigationProgress
import com.laozhang.mockgps.widget.NavigationProgressText
import com.laozhang.mockgps.utils.MMKVUtils
import kotlinx.android.synthetic.main.item_history.view.tv_info
import kotlinx.android.synthetic.main.item_history.view.tv_poi_name
import kotlinx.android.synthetic.main.item_history.view.iv_history_type
import java.util.ArrayList
import java.util.Collections


/**
 * @author jiayu.liu
 */
@Suppress("UNREACHABLE_CODE")
class MainAdapter(val headerAdapter: MultiplePoiAdapter) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    lateinit var dataBinding: LayoutMainCardHeaderBinding
    val isHeaderReady: Boolean get() = ::dataBinding.isInitialized
    private var simulationState = SimulationState()
    private var navigationProgress: NavigationProgress? = null
    private var recoveryAvailable = false
    private var list: ArrayList<MockMessageModel?> = arrayListOf()
    private var headerTouchHelper: ItemTouchHelper? = null
    private var travelMode = MMKVUtils.getTravelMode()
    private var speed = MMKVUtils.getSpeed().coerceIn(1, 500)
    private var renderingTravelMode = false
    private val HEADER_VIEW_TYPE = 0
    private val ITEM_VIEW_TYPE = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == HEADER_VIEW_TYPE) {
            dataBinding = DataBindingUtil.bind(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.layout_main_card_header, parent, false)
            )!!
            initHeaderView(parent.context, dataBinding)
            HeaderViewHolder(
                dataBinding
            )
        } else {
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_history, parent, false)
            )
        }
    }

    private fun initHeaderView(context: Context, dataBinding: LayoutMainCardHeaderBinding) {
        dataBinding.includeNaviCard.recyclerNavi.isNestedScrollingEnabled = false

        dataBinding.includeNaviCard.recyclerNavi.layoutManager = LinearLayoutManager(context)

        dataBinding.includeNaviCard.recyclerNavi.adapter = headerAdapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {

            override fun isItemViewSwipeEnabled(): Boolean {
                return false
            }

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                val currentList = headerAdapter.currentList()
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION ||
                    fromPosition !in currentList.indices || toPosition !in currentList.indices) return false
                Collections.swap(currentList, fromPosition, toPosition)
                updatePoiInfoType(fromPosition, currentList)
                updatePoiInfoType(toPosition, currentList)
                headerAdapter.notifyItemMoved(fromPosition, toPosition)
                headerAdapter.onPointsChanged?.invoke()
                return true
            }

            private fun updatePoiInfoType(
                position: Int,
                currentList: MutableList<PoiInfoModel>
            ) {
                when (position) {
                    0 -> currentList[position].poiInfoType = PoiInfoType.NAVI_START

                    currentList.size - 1 -> currentList[position].poiInfoType =
                        PoiInfoType.NAVI_END

                    else -> currentList[position].poiInfoType = PoiInfoType.DEFAULT
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {

            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                headerAdapter.notifyDataSetChanged()
            }

        })
        itemTouchHelper.attachToRecyclerView(dataBinding.includeNaviCard.recyclerNavi)
        headerTouchHelper = itemTouchHelper
        dataBinding.includeNaviCard.rgTravelMode.setOnCheckedChangeListener { _, id ->
            if (!renderingTravelMode) {
                val mode = when (id) {
                    R.id.travel_cycling -> TravelMode.CYCLING
                    R.id.travel_walking -> TravelMode.WALKING
                    R.id.travel_running -> TravelMode.RUNNING
                    else -> TravelMode.DRIVING
                }
                mOnItemClickListener?.onTravelModeSelected(mode)
            }
        }
        renderTravelMode()
        renderSimulationState()
    }

    fun updateTravelMode(mode: TravelMode) {
        travelMode = mode
        if (isHeaderReady) renderTravelMode()
    }

    fun updateSpeed(value: Int) {
        speed = value
        if (isHeaderReady) dataBinding.includeNaviCard.ivNaviSetting.text = "$speed km/h"
    }

    private fun renderTravelMode() {
        renderingTravelMode = true
        dataBinding.includeNaviCard.rgTravelMode.check(when (travelMode) {
            TravelMode.DRIVING -> R.id.travel_driving
            TravelMode.CYCLING -> R.id.travel_cycling
            TravelMode.WALKING -> R.id.travel_walking
            TravelMode.RUNNING -> R.id.travel_running
        })
        renderingTravelMode = false
        dataBinding.includeNaviCard.tvRoadHint.text = when (travelMode) {
            TravelMode.DRIVING -> "沿驾车道路规划 · 支持途经点和多路线选择"
            TravelMode.CYCLING -> "沿骑行道路规划 · 途经点逐段连接，采用各段推荐路线"
            TravelMode.WALKING -> "沿步行道路规划 · 途经点逐段连接，采用各段推荐路线"
            TravelMode.RUNNING -> "沿步行道路规划 · 使用跑步速度，支持途经点"
        }
        dataBinding.includeNaviCard.ivNaviSetting.text = "$speed km/h"
        updateNavigationOptions()
    }

    fun updateNavigationOptions() {
        if (!isHeaderReady) return
        val options = MMKVUtils.getNavigationOptions()
        dataBinding.includeNaviCard.btnRouteOptions.text = "路线与行驶 · " +
            (if (travelMode == TravelMode.DRIVING) options.drivingPreference.label + " · " else "") +
            if (options.realistic) "真实行驶${if (options.simulateStops && travelMode == TravelMode.DRIVING) " / 模拟停车" else ""}" else "匀速"
    }

    fun updateNavigationProgress(progress: NavigationProgress?) {
        navigationProgress = progress
        if (isHeaderReady) renderSimulationState()
    }

    fun updateRecoveryAvailable(available: Boolean) {
        recoveryAvailable = available
        if (isHeaderReady) renderSimulationState()
    }

    fun updateSimulationState(state: SimulationState) {
        simulationState = state
        if (isHeaderReady) renderSimulationState()
    }

    private fun renderSimulationState() {
        val state = simulationState
        val mode = when (state.mode) {
            NaviType.NAVI -> "导航"
            NaviType.NAVI_FILE -> "文件导航"
            else -> "定位"
        }
        dataBinding.tvSimulationStatus.text = when (state.status) {
            SimulationStatus.STOPPED -> "未启动模拟"
            SimulationStatus.STARTING -> "正在启动 · $mode"
            SimulationStatus.RUNNING -> "模拟中 · $mode"
            SimulationStatus.PAUSED -> "已暂停 · $mode"
            SimulationStatus.ERROR -> "模拟失败"
        }
        val statusColor = Color.parseColor(when (state.status) {
            SimulationStatus.RUNNING -> "#237445"
            SimulationStatus.STARTING, SimulationStatus.PAUSED -> "#946000"
            SimulationStatus.ERROR -> "#B43A35"
            else -> "#5F6E80"
        })
        dataBinding.tvSimulationStatus.setTextColor(statusColor)
        dataBinding.simulationStatusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        dataBinding.tvSimulationDetail.text = when (state.status) {
            SimulationStatus.STOPPED -> "选择位置或路线后启动"
            SimulationStatus.ERROR -> state.error
            else -> state.target.ifBlank { "正在模拟所选位置或路线" }
        }
        dataBinding.btnSimulationPause.visibility =
            if (state.status == SimulationStatus.RUNNING || state.status == SimulationStatus.PAUSED)
                View.VISIBLE else View.GONE
        dataBinding.btnSimulationPause.text =
            if (state.status == SimulationStatus.PAUSED) "继续" else "暂停"
        dataBinding.btnSimulationStop.visibility = if (state.hasSession) View.VISIBLE else View.GONE
        val progress = navigationProgress?.takeIf { state.hasSession && state.mode != NaviType.LOCATION }
        dataBinding.tvNavigationProgress.visibility = if (progress != null) View.VISIBLE else View.GONE
        dataBinding.btnNavigationProgress.visibility = if (progress != null) View.VISIBLE else View.GONE
        dataBinding.btnRestoreNavigation.visibility = if (!state.hasSession && recoveryAvailable) View.VISIBLE else View.GONE
        if (progress != null) {
            dataBinding.tvNavigationProgress.text = NavigationProgressText.format(
                if (state.status == SimulationStatus.PAUSED) progress.copy(speedKmH = 0f) else progress)
            if (progress.finished && state.status == SimulationStatus.RUNNING) dataBinding.tvSimulationStatus.text = "已到终点 · 保持位置"
        }
        for (button in listOf(dataBinding.includeLocationCard.btnStartLocation,
            dataBinding.includeNaviCard.btnStartNavi)) {
            button.isEnabled = state.status != SimulationStatus.STARTING
            button.alpha = if (button.isEnabled) 1f else 0.6f
            button.text = when (state.status) {
                SimulationStatus.STARTING -> "启动中…"
                SimulationStatus.RUNNING -> "停止模拟"
                SimulationStatus.PAUSED -> "继续模拟"
                else -> if (button.id == R.id.btn_start_location) "启动模拟" else "开始模拟"
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) {
            return HEADER_VIEW_TYPE
        } else {
            return ITEM_VIEW_TYPE
        }
    }

    override fun getItemCount(): Int {
        return list.size + 1
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.dataBinding.clickListener = mOnItemClickListener
            holder.dataBinding.includeLocationCard.clickListener = mOnItemClickListener
            holder.dataBinding.includeNaviCard.clickListener = mOnItemClickListener
            holder.dataBinding.btnSimulationPause.setOnClickListener { mOnItemClickListener?.onClick(it) }
            holder.dataBinding.btnSimulationStop.setOnClickListener { mOnItemClickListener?.onClick(it) }
            holder.dataBinding.btnNavigationProgress.setOnClickListener { mOnItemClickListener?.onClick(it) }
            holder.dataBinding.btnRestoreNavigation.setOnClickListener { mOnItemClickListener?.onClick(it) }
            holder.dataBinding.btnSimulationDiagnostics.setOnClickListener { mOnItemClickListener?.onClick(it) }
            holder.dataBinding.tvHistoryHint.text = if (list.isEmpty()) "启动模拟后自动保存，方便下次使用"
                else "点按复用 · 长按删除 · 最多10条"
            renderSimulationState()
        } else {
            val model = list[position - 1]
            when (model!!.naviType) {
                NaviType.LOCATION -> {
                    holder.itemView.iv_history_type.setImageResource(R.drawable.ic_map_locate)
                    holder.itemView.tv_poi_name.text = model.locationModel?.name ?: ""
                    holder.itemView.tv_info.text = String.format(
                        "经度 %.6f · 纬度 %.6f",
                        model.locationModel?.latLng?.longitude ?: 0f,
                        model.locationModel?.latLng?.latitude ?: 0f
                    )
                }

                else -> {
                    holder.itemView.iv_history_type.setImageResource(R.drawable.ic_top_switch)
                    val format = StringBuffer().append("${model.startNavi?.name ?: ""} → ")
                    model.wayNaviList?.let {
                        for (poiInfoModel in it) {
                            format.append("${poiInfoModel.name ?: ""} → ")
                        }
                    }
                    format.append(model.endNavi?.name ?: "")
                    holder.itemView.tv_poi_name.text = format.toString()
                    holder.itemView.tv_info.text = when {
                        model.naviType == NaviType.NAVI_FILE -> "文件模拟路线"
                        !model.wayNaviList.isNullOrEmpty() -> "${TravelMode.fromId(model.travelMode).label} · ${model.speed} km/h · ${model.wayNaviList!!.size} 个途经点"
                        else -> "${TravelMode.fromId(model.travelMode).label} · ${model.speed} km/h · 起点至终点"
                    }
                }
            }
            mOnItemClickListener?.let { listener ->
                holder.itemView.setOnClickListener {
                    listener.onItemClick(it, model)
                }
                holder.itemView.setOnLongClickListener {
                    listener.onItemLongClick(it, model)
                }
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class HeaderViewHolder(val dataBinding: LayoutMainCardHeaderBinding) :
        RecyclerView.ViewHolder(dataBinding.root)

    private var mOnItemClickListener: OnItemClickListener? = null

    fun setOnItemClickListener(mOnItemClickListener: OnItemClickListener?) {
        this.mOnItemClickListener = mOnItemClickListener
    }

    fun onItemMove(viewHolder: RecyclerView.ViewHolder) {
        if (viewHolder.adapterPosition != RecyclerView.NO_POSITION) headerTouchHelper?.startDrag(viewHolder)
    }

    fun submitList(list: List<MockMessageModel?>) {
        this.list.clear()
        this.list.addAll(list)
        notifyDataSetChanged()
    }

    interface OnItemClickListener : View.OnClickListener {
        fun onTravelModeSelected(mode: TravelMode)
        fun onItemClick(view: View?, model: MockMessageModel)

        fun onItemLongClick(view: View?, model: MockMessageModel): Boolean
    }
}
