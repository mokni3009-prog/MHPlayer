package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mhplayer.app.R
import com.mhplayer.app.data.model.Episode
import com.mhplayer.app.databinding.ItemEpisodeBinding
import com.mhplayer.app.ui.PlaybackHelper

/**
 * Episode cards (Details screen): 16:9 thumb + label bar below.
 * Selected episode: label text in red (bold); unselected: white.
 */
class EpisodeAdapter(
    private var episodes: List<Episode> = emptyList(),
    private val coverUrl: String? = null,
    private val onClick: ((Episode) -> Unit)? = null,
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    var selectedEpisodeId: Int = -1
        set(value) {
            val old = field
            field = value
            if (old != -1) notifyItemChanged(indexOf(old))
            if (value != -1) notifyItemChanged(indexOf(value))
        }

    private fun indexOf(episodeId: Int): Int = episodes.indexOfFirst { it.id == episodeId }

    inner class ViewHolder(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(episodes[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        val selected = episode.id == selectedEpisodeId
        holder.binding.episodeLabel.text = PlaybackHelper.episodeTitle(episode)
        holder.binding.episodeLabel.setTextColor(
            holder.itemView.context.getColor(
                if (selected) R.color.color_primary_red else R.color.color_text_primary,
            ),
        )
        holder.binding.episodeLabel.setTypeface(
            null,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
        holder.binding.episodeThumb.load(coverUrl) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }
    }

    override fun getItemCount(): Int = episodes.size

    fun submitList(newEpisodes: List<Episode>) {
        episodes = newEpisodes
        notifyDataSetChanged()
    }
}
