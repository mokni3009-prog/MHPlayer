package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mhplayer.app.R
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.databinding.ItemFavChannelBinding

/**
 * Favorite live channel rows (Favorites screen): logo + name + LIVE badge
 * + gold heart. Tap the row to play, tap the heart to remove.
 */
class FavoriteChannelAdapter(
    private var items: List<LiveChannel> = emptyList(),
    private val onClick: ((LiveChannel) -> Unit)? = null,
    private val onRemove: ((LiveChannel) -> Unit)? = null,
) : RecyclerView.Adapter<FavoriteChannelAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemFavChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(items[position])
            }
            binding.favChannelHeart.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onRemove?.invoke(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]
        holder.binding.favChannelName.text = channel.name
        holder.binding.favChannelLogo.load(channel.icon) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }
        holder.binding.favChannelHeart.setColorFilter(
            ContextCompat.getColor(holder.itemView.context, R.color.zeno_gold),
        )
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<LiveChannel>) {
        items = newItems
        notifyDataSetChanged()
    }
}
