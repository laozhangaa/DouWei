package com.laozhang.mockgps.widget

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.textfield.TextInputLayout
import com.laozhang.mockgps.R
import com.laozhang.mockgps.manager.FavoritesManager
import com.laozhang.mockgps.model.FavoriteEntry
import com.laozhang.mockgps.model.FavoriteKind
import com.laozhang.mockgps.model.TravelMode
import java.util.Locale

/** Uses the same compact rounded card as history confirmations. */
open class FavoriteCardDialog(context: Context, layout: Int) : Dialog(context) {
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(layout)
        window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            attributes = attributes.apply {
                width = minOf(ScreenUtils.getScreenWidth() - ConvertUtils.dp2px(36f), ConvertUtils.dp2px(440f))
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                dimAmount = 0.38f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}

class FavoriteNameDialog(
    context: Context, title: String, initialName: String, note: String,
    private val save: (String, (String?) -> Unit) -> Unit
) : FavoriteCardDialog(context, R.layout.dialog_favorite_name) {
    private val input = findViewById<EditText>(R.id.et_favorite_name)
    private val field = findViewById<TextInputLayout>(R.id.til_favorite_name)
    private val button = findViewById<Button>(R.id.btn_favorite_save)
    private var saving = false
    init {
        findViewById<TextView>(R.id.tv_favorite_title).text = title
        findViewById<TextView>(R.id.tv_favorite_note).text = note
        input.setText(initialName.take(40))
        input.setSelection(input.text.length)
        button.setOnClickListener { commit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { commit(); true } else false
        }
        findViewById<View>(R.id.btn_favorite_cancel).setOnClickListener { dismiss() }
    }

    private fun commit() {
        if (saving) return
        val name = input.text.toString().trim()
        if (name.isEmpty() || name.length > 40) {
            field.error = "名称请填写 1～40 个字"
            return
        }
        field.error = null
        saving = true
        button.isEnabled = false
        button.text = "保存中…"
        save(name) { error ->
            if (isShowing) {
                saving = false
                button.isEnabled = true
                button.text = "保存"
                if (error == null) dismiss() else field.error = error
            }
        }
    }
}

class FavoritesDialog(
    context: Context, private val kind: FavoriteKind,
    private val onUse: (FavoriteEntry) -> Unit
) : FavoriteCardDialog(context, R.layout.dialog_favorites) {
    private val list = findViewById<RecyclerView>(R.id.list_favorites)
    private val empty = findViewById<TextView>(R.id.tv_favorites_empty)
    private var child: Dialog? = null
    private var revision = 0
    init {
        findViewById<TextView>(R.id.tv_favorites_title).text = if (kind == FavoriteKind.LOCATION) "我的位置" else "我的路线"
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = null
        findViewById<View>(R.id.btn_favorites_close).setOnClickListener { dismiss() }
    }
    override fun show() { super.show(); refresh() }
    override fun dismiss() { revision++; child?.dismiss(); child = null; super.dismiss() }

    private fun refresh() {
        val version = ++revision
        FavoritesManager.list(kind) { result ->
            if (!isShowing || version != revision) return@list
            val entries = result.getOrNull().orEmpty()
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            empty.text = result.exceptionOrNull()?.message ?: if (kind == FavoriteKind.LOCATION)
                "还没有收藏位置\n选好地点后，点击“收藏位置”保存" else
                "还没有收藏路线\n选好起终点后，点击“收藏路线”保存"
            list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            list.adapter = EntryAdapter(entries)
            list.layoutParams = list.layoutParams.apply {
                height = minOf(entries.size * ConvertUtils.dp2px(108f), (ScreenUtils.getScreenHeight() * 0.48).toInt())
            }
        }
    }

    private fun manage(entry: FavoriteEntry) {
        child?.dismiss()
        child = FavoriteCardDialog(context, R.layout.dialog_favorite_actions).apply {
            findViewById<TextView>(R.id.tv_favorite_action_title).text = entry.name
            findViewById<View>(R.id.btn_favorite_action_cancel).setOnClickListener { dismiss() }
            findViewById<View>(R.id.btn_favorite_rename).setOnClickListener {
                dismiss()
                child = FavoriteNameDialog(context, "重命名收藏", entry.name, "为常用${kind.label}设置一个容易辨认的名字") { name, done ->
                    FavoritesManager.rename(entry.id, name) { result ->
                        done(result.exceptionOrNull()?.message)
                        if (result.isSuccess && this@FavoritesDialog.isShowing) refresh()
                    }
                }.also { it.show() }
            }
            findViewById<View>(R.id.btn_favorite_delete).setOnClickListener {
                dismiss()
                child = DeleteHistoryDialog(context, entry.name, recordType = "收藏") {
                    FavoritesManager.delete(entry.id) { result ->
                        if (this@FavoritesDialog.isShowing) {
                            if (result.isSuccess) refresh() else ToastUtils.showShort(result.exceptionOrNull()?.message ?: "删除失败")
                        }
                    }
                }.also { it.show() }
            }
            show()
        }
    }

    private inner class EntryAdapter(private val entries: List<FavoriteEntry>) : RecyclerView.Adapter<EntryHolder>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false))
        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val entry = entries[position]
            holder.itemView.findViewById<TextView>(R.id.tv_favorite_name).text = entry.name
            holder.itemView.findViewById<TextView>(R.id.tv_favorite_detail).text = if (kind == FavoriteKind.LOCATION) {
                val point = entry.points.first()
                String.format(Locale.CHINA, "经度 %.6f · 纬度 %.6f", point.longitude, point.latitude)
            } else {
                val metadata = entry.routeMetadata
                val source = when (metadata?.provider) { "AMAP" -> "高德"; "BAIDU" -> "百度"; else -> "已保存道路" }
                val preference = if (entry.travelMode == TravelMode.DRIVING.name)
                    " · ${com.laozhang.mockgps.model.DrivingPreference.fromId(metadata?.preference).label}" else ""
                "${TravelMode.fromId(entry.travelMode).label} · ${entry.speedKmH} km/h · $source$preference\n" +
                    entry.points.joinToString(" → ") { it.name.ifBlank { "已选地点" } }
            }
            holder.itemView.setOnClickListener { dismiss(); onUse(entry) }
            holder.itemView.setOnLongClickListener { manage(entry); true }
            holder.itemView.findViewById<View>(R.id.btn_favorite_manage).apply {
                contentDescription = "管理收藏：${entry.name}"
                setOnClickListener { manage(entry) }
            }
        }
    }
    private class EntryHolder(view: View) : RecyclerView.ViewHolder(view)
}
