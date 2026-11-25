package com.mhplayer.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.mhplayer.app.R
import com.mhplayer.app.databinding.ItemPosterBarBinding
import com.mhplayer.app.databinding.ItemPosterBinding

/**
 * 2:3 portrait poster. Loads `stream_icon` / cover with Coil; placeholder =
 * dark card with the red MH mark. Focusable with a red outline (DPAD).
 *
 * Title styles (DESIGN_TOKENS.md §11.5 — kept per screen, not unified):
 * - [Style.NONE]: no title (Home rows).
 * - [Style.BELOW]: plain white title below the poster (Movies).
 * - [Style.BAR]: title inside a dark bar below the poster (Series).
 */
class PosterAdapter(
    private var items: List<PosterItem> = emptyList(),
    private val style: Style = Style.NONE,
    private val onClick: ((PosterItem) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Style { NONE, BELOW, BAR }

    data class PosterItem(val id: Int, val title: String, val imageUrl: String?)

    private inner class PosterViewHolder(val binding: ItemPosterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(items[position])
            }
        }
    }

    private inner class BarViewHolder(val binding: ItemPosterBarBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick?.invoke(items[position])
            }
        }
    }

    override fun getItemViewType(position: Int): Int = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == Style.BAR.ordinal) {
            BarViewHolder(ItemPosterBarBinding.inflate(inflater, parent, false))
        } else {
            PosterViewHolder(ItemPosterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is PosterViewHolder -> {
                holder.binding.posterImage.load(item.imageUrl) {
                    placeholder(R.drawable.poster_placeholder)
                    error(R.drawable.poster_placeholder)
                    transformations(RoundedCornersTransformation(12f))
                }
                holder.binding.posterTitle.text = item.title
                holder.binding.posterTitle.visibility =
                    if (style == Style.BELOW) View.VISIBLE else View.GONE
            }
            is BarViewHolder -> {
                holder.binding.posterImage.load(item.imageUrl) {
                    placeholder(R.drawable.poster_placeholder)
                    error(R.drawable.poster_placeholder)
                    transformations(RoundedCornersTransformation(12f, 12f, 0f, 0f))
                }
                holder.binding.posterTitle.text = item.title
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<PosterItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
