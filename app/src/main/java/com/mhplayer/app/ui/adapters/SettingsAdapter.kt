package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.mhplayer.app.databinding.ItemSettingsRowBinding

/**
 * Settings rows (Settings screen): icon + title + description + either a
 * gold->cyan switch (toggle rows) or a chevron (action rows that open a
 * dialog). The fragment owns the row list and the callbacks.
 */
class SettingsAdapter(
    private var rows: List<SettingRow> = emptyList(),
    private val onToggle: ((String, Boolean) -> Unit)? = null,
    private val onClick: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {

    enum class Kind { SWITCH, ACTION }

    data class SettingRow(
        val id: String,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int,
        @DrawableRes val iconRes: Int,
        val kind: Kind,
        val switchValue: Boolean = false,
    )

    inner class ViewHolder(val binding: ItemSettingsRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val row = rows[position]
                if (row.kind == Kind.SWITCH) {
                    binding.settingSwitch.isChecked = !binding.settingSwitch.isChecked
                    onToggle?.invoke(row.id, binding.settingSwitch.isChecked)
                } else {
                    onClick?.invoke(row.id)
                }
            }
            binding.settingSwitch.setOnCheckedChangeListener { _, checked ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                onToggle?.invoke(rows[position].id, checked)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingsRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.binding.settingIcon.setImageResource(row.iconRes)
        holder.binding.settingTitle.setText(row.titleRes)
        holder.binding.settingDescription.setText(row.descRes)
        if (row.kind == Kind.SWITCH) {
            holder.binding.settingSwitch.visibility = View.VISIBLE
            holder.binding.settingChevron.visibility = View.GONE
            // Avoid firing the listener while binding.
            holder.binding.settingSwitch.setOnCheckedChangeListener(null)
            holder.binding.settingSwitch.isChecked = row.switchValue
            holder.binding.settingSwitch.setOnCheckedChangeListener { _, checked ->
                onToggle?.invoke(row.id, checked)
            }
        } else {
            holder.binding.settingSwitch.visibility = View.GONE
            holder.binding.settingChevron.visibility = View.VISIBLE
        }
    }

    override fun getItemCount(): Int = rows.size

    fun submitList(newRows: List<SettingRow>) {
        rows = newRows
        notifyDataSetChanged()
    }
}
