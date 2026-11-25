package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mhplayer.app.R
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.databinding.ItemChannelBinding

/**
 * Channel rows (Channels screen): small round logo + name.
 * Selected channel: FULL red background (square corners, fills the column).
 */
class ChannelAdapter(
    private var channels: List<LiveChannel> = emptyList(),
    private val onClick: ((LiveChannel) -> Unit)? = null,
    private val onLongClick: ((LiveChannel) -> Boolean)? = null,
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    var selectedStreamId: Int = -1
        set(value) {
            val old = field
            field = value
            if (old != -1) notifyItemChanged(indexOf(old))
            if (value != -1) notifyItemChanged(indexOf(value))
        }

    private fun indexOf(streamId: Int): Int = channels.indexOfFirst { it.streamId == streamId }

    inner class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(channels[position])
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onLongClick?.invoke(channels[position]) ?: false
                else false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        val selected = channel.streamId == selectedStreamId
        holder.binding.channelName.text = channel.name
        holder.binding.channelName.setTypeface(
            null,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
        holder.binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_channel_selected else 0,
        )
        holder.binding.channelLogo.load(channel.icon) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }
        holder.binding.channelLogo.visibility = View.VISIBLE
    }

    override fun getItemCount(): Int = channels.size

    fun submitList(newChannels: List<LiveChannel>) {
        channels = newChannels
        notifyDataSetChanged()
    }
}
