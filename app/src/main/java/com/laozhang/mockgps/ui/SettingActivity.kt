package com.laozhang.mockgps.ui

import android.widget.Toast
import com.laozhang.mockgps.manager.MapProvider
import com.laozhang.mockgps.manager.FloatingViewManger
import com.laozhang.mockgps.manager.MapSdkCredentials
import androidx.recyclerview.widget.LinearLayoutManager
import com.castiel.common.base.BaseActivity
import com.castiel.common.base.BaseViewModel
import com.castiel.common.recycler.decoration.VerticalItemDecoration
import com.laozhang.mockgps.R
import com.laozhang.mockgps.adaper.SettingAdapter
import com.laozhang.mockgps.databinding.ActivitySettingBinding
import com.laozhang.mockgps.databinding.ItemSettingBinding
import com.laozhang.mockgps.model.SettingMsgModel
import com.laozhang.mockgps.utils.MMKVUtils
import com.laozhang.mockgps.widget.InputLocationVibrationDialog
import com.laozhang.mockgps.widget.MapServiceDialog

/**
 * @author jiayu.liu
 */
class SettingActivity : BaseActivity<ActivitySettingBinding, BaseViewModel>() {
    private var providerDialog: MapServiceDialog? = null
    private var mAdapter: SettingAdapter? = null
    private val mTitle = arrayOf(
        "模拟定位震动功能",
    )
    private val mMsg = arrayOf(
        "定位开启后，自动微调参数(方向、经纬度等)，当前随机半径范围为:",
    )

    override fun initViewModel(): Class<BaseViewModel> {
        return BaseViewModel::class.java
    }

    override fun getLayout(): Int {
        return R.layout.activity_setting
    }

    override fun initView() {
        updateProviderLabel()
        dataBinding.mapProvider.setOnClickListener {
            providerDialog?.dismiss()
            providerDialog = MapServiceDialog(this) {
                FloatingViewManger.INSTANCE.refreshMapProvider()
                updateProviderLabel()
            }.also { it.show() }
        }

        dataBinding.recycler.layoutManager = LinearLayoutManager(this)
        mAdapter = SettingAdapter()
        dataBinding.recycler.adapter = mAdapter
        dataBinding.recycler.addItemDecoration(
            VerticalItemDecoration(
                this,
                R.drawable.shape_item_line_verticall,
                10f,
                0f
            )
        )

        mAdapter?.listener = object : SettingAdapter.OnItemListener {
            override fun onItemSwitch(
                dataBinding: ItemSettingBinding?,
                model: SettingMsgModel,
                isChecked: Boolean
            ) {
                when (model.title) {
                    mTitle[0] -> {
                        MMKVUtils.saveSettingConfig(
                            MMKVUtils.KEY_LOCATION_VIBRATION,
                            isChecked
                        )
                        dataBinding?.isShowSetting = isChecked
                    }

                    else -> {}
                }
            }

            override fun onSettingClick(model: SettingMsgModel) {
                when (model.title) {
                    mTitle[0] -> {
                        InputLocationVibrationDialog(
                            this@SettingActivity,
                            MMKVUtils.getLocationVibrationValue(),
                            MMKVUtils.getLocationFrequencyValue()
                        ).apply {
                            mDismissListener = object :
                                InputLocationVibrationDialog.LocationVibrationDismissListener {
                                override fun onDismiss() {
                                    Toast.makeText(
                                        this@SettingActivity,
                                        "频率设置需要等待上次频率触发后生效",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    updateData()
                                }
                            }
                            show()
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    override fun initData() {
        updateData()
    }

    private fun updateData() {
        val settingModel = MMKVUtils.getSettingModel()
        mAdapter?.submitList(
            mutableListOf(
                SettingMsgModel(
                    title = mTitle[0],
                    msg = "${mMsg[0]}${MMKVUtils.getLocationVibrationValue()}m，当前震动频率:${MMKVUtils.getLocationFrequencyValue()}s",
                    isSwitch = settingModel.isLocationQuiver
                )
            )
        )
    }

    private fun updateProviderLabel() {
        dataBinding.mapProvider.text = "地图服务：${MapProvider.current.label}（${if (MapSdkCredentials.isConfigured()) "已配置" else "未配置"}）"
    }

    override fun onDestroy() {
        providerDialog?.dismiss()
        super.onDestroy()
    }

    override fun initObserver() {
    }


}
