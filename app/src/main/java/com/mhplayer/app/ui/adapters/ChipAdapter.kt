package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mhplayer.app.R
import com.mhplayer.app.databinding.ItemChipBinding

/**
 * Genre chips (Movies / Series): selected chip = red pill, unselected = dark pill.
 */
class ChipAdapter(
    private val chips: List<String>,
    private val onClick: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<ChipAdapter.ViewHolder>() {

    var selectedChip: String = chips.firstOrNull().orEmpty()
        set(value) {
            val old = field
            field = value
            notifyItemChanged(chips.indexOf(old))
            notifyItemChanged(chips.indexOf(value))
        }

    inner class ViewHolder(val binding: ItemChipBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    selectedChip = chips[position]
                    onClick?.invoke(chips[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chip = chips[position]
        val selected = chip == selectedChip
        holder.binding.chipLabel.text = chip
        holder.binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected,
        )
    }

    override fun getItemCount(): Int = chips.size
}
