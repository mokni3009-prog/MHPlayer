package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.mhplayer.app.R
import com.mhplayer.app.databinding.ItemThumbBinding

/**
 * 16:9 landscape thumbs (My Playlist Films / Séries rows): title overlaid
 * at the bottom centre of the image.
 */
class ThumbAdapter(
    private var items: List<PosterAdapter.PosterItem> = emptyList(),
    private val onClick: ((PosterAdapter.PosterItem) -> Unit)? = null,
) : RecyclerView.Adapter<ThumbAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemThumbBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.thumbTitle.text = item.title
        holder.binding.thumbImage.load(item.imageUrl) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
            transformations(RoundedCornersTransformation(12f))
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<PosterAdapter.PosterItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
