package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mhplayer.app.R
import com.mhplayer.app.data.model.Category
import com.mhplayer.app.databinding.ItemBouquetBinding

/**
 * Bouquet rows (Channels screen): selected bouquet = FULL red background
 * (radius 12dp, inset from the column edges).
 */
class BouquetAdapter(
    private var bouquets: List<Category> = emptyList(),
    private val onClick: ((Category) -> Unit)? = null,
) : RecyclerView.Adapter<BouquetAdapter.ViewHolder>() {

    var selectedCategoryId: String? = null
        set(value) {
            val old = field
            field = value
            old?.let { notifyItemChanged(indexOf(it)) }
            value?.let { notifyItemChanged(indexOf(it)) }
        }

    private fun indexOf(categoryId: String): Int = bouquets.indexOfFirst { it.id == categoryId }

    inner class ViewHolder(val binding: ItemBouquetBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(bouquets[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBouquetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bouquet = bouquets[position]
        val selected = bouquet.id == selectedCategoryId
        holder.binding.bouquetName.text = bouquet.name
        holder.binding.bouquetName.setTypeface(
            null,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
        holder.binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_selected_full else 0,
        )
    }

    override fun getItemCount(): Int = bouquets.size

    fun submitList(newBouquets: List<Category>) {
        bouquets = newBouquets
        notifyDataSetChanged()
    }
}
