package com.laozhang.mockgps.ui

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.castiel.common.base.BaseActivity
import com.castiel.common.base.BaseListAdapter
import com.castiel.common.base.BaseViewModel
import com.castiel.common.recycler.decoration.VerticalItemDecoration
import com.laozhang.mockgps.R
import com.laozhang.mockgps.adaper.ExpandAdapter
import com.laozhang.mockgps.databinding.ActivityExpandBinding
import com.laozhang.mockgps.manager.FloatingViewManger
import com.laozhang.mockgps.manager.MapProvider
import com.laozhang.mockgps.manager.MapSdkCredentials
import com.laozhang.mockgps.model.ExpandModel
import com.laozhang.mockgps.widget.HintDialog
import com.laozhang.mockgps.widget.MapServiceDialog
import com.laozhang.mockgps.widget.DiagnosticsDialog
import com.laozhang.mockgps.widget.NavigationOptionsDialog
import com.laozhang.mockgps.widget.FavoriteCardDialog
import com.laozhang.mockgps.utils.MMKVUtils

class ExpandActivity : BaseActivity<ActivityExpandBinding, BaseViewModel>() {
    private val adapter = ExpandAdapter()
    private var providerDialog: MapServiceDialog? = null
    private var featureDialog: FavoriteCardDialog? = null
    override fun initViewModel() = BaseViewModel::class.java
    override fun getLayout() = R.layout.activity_expand

    override fun initView() {
        dataBinding.recycler.layoutManager = LinearLayoutManager(this)
        dataBinding.recycler.adapter = adapter
        dataBinding.recycler.addItemDecoration(
            VerticalItemDecoration(this, R.drawable.shape_item_line_verticall, 10f, 0f))
        adapter.clickListener = object : BaseListAdapter.OnItemClickListener<ExpandModel> {
            override fun onItemClick(view: View?, t: ExpandModel, position: Int) {
                when (t.title) {
                    "地图服务" -> selectProvider()
                    "路线与行驶" -> {
                        featureDialog?.dismiss()
                        featureDialog = NavigationOptionsDialog(this@ExpandActivity, MMKVUtils.getTravelMode()) { initData() }.also { it.show() }
                    }
                    "收藏备份" -> startActivity(Intent(this@ExpandActivity, BackupActivity::class.java))
                    "运行诊断" -> {
                        featureDialog?.dismiss()
                        featureDialog = DiagnosticsDialog(this@ExpandActivity).also { it.show() }
                    }
                    "外部app启动模拟导航" -> HintDialog(this@ExpandActivity,
                        "外部广播", getString(R.string.receiver_hint)).show()
                    "模拟功能设置" -> startActivity(Intent(this@ExpandActivity, SettingActivity::class.java))
                    "模拟导航数据导入" -> startActivity(Intent(this@ExpandActivity, FileMockActivity::class.java))
                }
            }
        }
    }

    override fun initData() {
        adapter.submitList(listOf(
            ExpandModel("地图服务", "当前：${MapProvider.current.label} · ${if (MapSdkCredentials.isConfigured()) "已配置" else "未配置"}，点击切换或配置凭证"),
            ExpandModel("路线与行驶", "${MMKVUtils.getNavigationOptions().drivingPreference.label} · ${if (MMKVUtils.getNavigationOptions().realistic) "真实行驶" else "匀速回放"}，设置路口模拟停车"),
            ExpandModel("收藏备份", "导出位置和完整道路，合并导入、换机恢复"),
            ExpandModel("运行诊断", "检查模拟定位写入、权限、后台与省电状态"),
            ExpandModel("外部app启动模拟导航", "通过广播形式发送起终点信息"),
            ExpandModel("模拟功能设置", "模拟功能设置，扩展功能"),
            ExpandModel("模拟导航数据导入", "路径：/storage/emulated/0/Android/data/com.laozhang.mockgps/files/nav_path"),
            ExpandModel("免责声明", "此应用仅限开发学习和开发使用，软件的发布和使用均不收取任何费用。拒绝任何人或任何实体进行出售、重新修改后分发，严禁用于商业谋利用途。项目维护者对软件的滥用不承担任何责任。")
        ))
    }

    private fun selectProvider() {
        providerDialog?.dismiss()
        providerDialog = MapServiceDialog(this) {
            FloatingViewManger.INSTANCE.refreshMapProvider()
            initData()
        }.also { it.show() }
    }

    override fun initObserver() {}
    override fun onResume() { super.onResume(); initData() }
    override fun onDestroy() { providerDialog?.dismiss(); featureDialog?.dismiss(); super.onDestroy() }
}
