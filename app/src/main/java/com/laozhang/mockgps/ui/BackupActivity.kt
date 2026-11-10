package com.laozhang.mockgps.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import com.castiel.common.base.BaseActivity
import com.castiel.common.base.BaseViewModel
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.ActivityBackupBinding
import com.laozhang.mockgps.manager.FavoritesManager
import com.laozhang.mockgps.model.FavoriteKind
import com.laozhang.mockgps.widget.NavigationInfoDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : BaseActivity<ActivityBackupBinding, BaseViewModel>() {
    private var confirmation: NavigationInfoDialog? = null
    private val exportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) setBusy(false, "已取消导出")
        else {
            setBusy(true, "正在导出收藏…")
            FavoritesManager.exportBackup({ contentResolver.openOutputStream(uri, "wt") }) { saved ->
                if (!isDestroyed) {
                    setBusy(false, saved.fold({ "已导出 $it 条收藏。地图凭证未包含在文件中。" },
                        { "导出失败：${it.message ?: "请重新选择保存位置"}" }))
                }
            }
        }
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) setBusy(false, "已取消选择文件")
        else {
            setBusy(true, "正在检查备份文件…")
            FavoritesManager.readBackup({ contentResolver.openInputStream(uri) }) { parsed ->
                if (isDestroyed || isFinishing) return@readBackup
                setBusy(false)
                parsed.fold({ entries ->
                    val locations = entries.count { it.kind == FavoriteKind.LOCATION }
                    confirmation?.dismiss()
                    confirmation = NavigationInfoDialog(this, "导入收藏", "文件包含 $locations 个位置、${entries.size - locations} 条路线。\n\n确认后合并导入，已有收藏不会覆盖，重复内容会跳过。路线保留原道路、坐标系和行驶设置。",
                        positive = "合并导入", onPositive = { dialog ->
                            dialog.busy(true)
                            setBusy(true, "正在合并收藏…")
                            FavoritesManager.importBackup(entries) { imported ->
                                if (isDestroyed) return@importBackup
                                dialog.busy(false)
                                imported.fold({ count ->
                                    dialog.dismiss()
                                    setBusy(false, "导入完成：新增 ${count.added} 条，跳过重复 ${count.skipped} 条。")
                                    refreshCounts()
                                }, { error ->
                                    setBusy(false, "导入失败：${error.message ?: "请检查存储空间后重试"}")
                                    dialog.message("合并未完成：${error.message ?: "请检查存储空间"}\n已有收藏保留。")
                                })
                            }
                        }).also { it.show() }
                }, { error -> setBusy(false, "文件未导入：${error.message ?: "备份内容无效"}") })
            }
        }
    }

    override fun initViewModel() = BaseViewModel::class.java
    override fun getLayout() = R.layout.activity_backup
    override fun initView() {
        dataBinding.toolbarBackup.setNavigationOnClickListener { finish() }
        dataBinding.btnExportFavorites.setOnClickListener {
            val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            runCatching {
                exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "豆位收藏-$date.json")
                })
            }.onFailure { setBusy(false, "无法打开文件选择器，请检查手机是否支持文档保存") }
        }
        dataBinding.btnImportFavorites.setOnClickListener {
            runCatching {
                importPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
                })
            }.onFailure { setBusy(false, "无法打开文件选择器，请检查系统文件应用") }
        }
    }
    private fun setBusy(busy: Boolean, text: String? = null) {
        dataBinding.btnImportFavorites.isEnabled = !busy
        dataBinding.btnExportFavorites.isEnabled = !busy
        text?.let { dataBinding.tvBackupResult.text = it }
    }
    private fun refreshCounts() {
        FavoritesManager.counts { result ->
            if (!isDestroyed) dataBinding.tvBackupCounts.text = result.fold({ "已收藏 ${it.first} 个位置 · ${it.second} 条路线" }, { "收藏读取失败，请稍后重试" })
        }
    }
    override fun initData() { refreshCounts() }
    override fun initObserver() {}
    override fun onDestroy() { confirmation?.dismiss(); super.onDestroy() }
}
