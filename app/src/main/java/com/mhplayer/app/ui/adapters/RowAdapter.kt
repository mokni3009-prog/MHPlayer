package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhplayer.app.databinding.ItemRowBinding

/**
 * Horizontal content row: section title + a horizontal poster list
 * (Home "Films populaires" / "Séries", Playlist rows).
 */
class RowAdapter(
    private var sections: List<RowSection> = emptyList(),
    private val onPosterClick: ((PosterAdapter.PosterItem) -> Unit)? = null,
) : RecyclerView.Adapter<RowAdapter.ViewHolder>() {

    data class RowSection(val title: String, val posters: List<PosterAdapter.PosterItem>)

    inner class ViewHolder(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val section = sections[position]
        holder.binding.rowTitle.text = section.title
        holder.binding.rowTitle.visibility =
            if (section.title.isBlank()) View.GONE else View.VISIBLE
        val adapter = PosterAdapter(section.posters, PosterAdapter.Style.NONE, onPosterClick)
        holder.binding.rowList.layoutManager =
            LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        holder.binding.rowList.adapter = adapter
    }

    override fun getItemCount(): Int = sections.size

    fun submitList(newSections: List<RowSection>) {
        sections = newSections
        notifyDataSetChanged()
    }
}
