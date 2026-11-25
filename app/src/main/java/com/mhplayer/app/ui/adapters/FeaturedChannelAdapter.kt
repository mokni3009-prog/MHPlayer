package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mhplayer.app.R
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.databinding.ItemFeaturedChannelBinding

/**
 * Featured live channel cards (Home): 16:9 image + LIVE badge + name.
 * Tap -> plays the channel fullscreen.
 */
class FeaturedChannelAdapter(
    private var items: List<LiveChannel> = emptyList(),
    private val onClick: ((LiveChannel) -> Unit)? = null,
) : RecyclerView.Adapter<FeaturedChannelAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemFeaturedChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeaturedChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]
        holder.binding.featuredName.text = channel.name
        holder.binding.featuredImage.load(channel.icon) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<LiveChannel>) {
        items = newItems
        notifyDataSetChanged()
    }
}
