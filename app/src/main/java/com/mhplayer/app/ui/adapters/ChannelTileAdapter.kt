package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.databinding.ItemChannelTileBinding

/**
 * Channel icon tiles (My Playlist "Chaînes" row): coloured icon + label.
 * The icon tint cycles through a small palette (blue / green / gold / red /
 * orange) like the mockup.
 */
class ChannelTileAdapter(
    private var channels: List<LiveChannel> = emptyList(),
    private val onClick: ((LiveChannel) -> Unit)? = null,
) : RecyclerView.Adapter<ChannelTileAdapter.ViewHolder>() {

    private val tints = listOf(
        0xFF2196F3.toInt(), // blue
        0xFF4CAF50.toInt(), // green
        0xFFFFC107.toInt(), // gold
        0xFFF44336.toInt(), // red
        0xFFFF9800.toInt(), // orange
        0xFF9C27B0.toInt(), // purple
    )

    inner class ViewHolder(val binding: ItemChannelTileBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(channels[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.binding.tileLabel.text = channel.name
        holder.binding.tileIcon.setColorFilter(tints[position % tints.size])
    }

    override fun getItemCount(): Int = channels.size

    fun submitList(newChannels: List<LiveChannel>) {
        channels = newChannels
        notifyDataSetChanged()
    }
}
