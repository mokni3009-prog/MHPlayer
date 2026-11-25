package com.mhplayer.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mhplayer.app.R

/**
 * Reusable narrow sidebar (DESIGN_TOKENS.md §1.4).
 *
 * Logo + menu Accueil / Chaînes / Films / Séries, with "Ma playlist"
 * pinned at the bottom. Selected item: red icon + red bold text, dark-red
 * tinted row background and a 4dp solid red bar on the left edge.
 * Every item is focusable with a visible red focus highlight (DPAD + touch).
 * In portrait the fragment calls [setHorizontal] and the sidebar becomes a
 * top bar with the same items laid out horizontally.
 *
 * "Films" appears exactly once (the movies mockup duplicated it — not reproduced).
 */
class SidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Item(val labelRes: Int, val iconRes: Int) {
        HOME(R.string.sidebar_home, R.drawable.ic_home),
        CHANNELS(R.string.sidebar_channels, R.drawable.ic_tv),
        MOVIES(R.string.sidebar_movies, R.drawable.ic_clapperboard),
        SERIES(R.string.sidebar_series, R.drawable.ic_grid),
        PLAYLIST(R.string.sidebar_playlist, R.drawable.ic_bookmark),
    }

    var onItemSelected: ((Item) -> Unit)? = null

    private var selectedItem: Item = Item.HOME
    private var horizontal = false

    private val itemViews = mutableMapOf<Item, View>()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_sidebar, this, true)
        setBackgroundColor(ContextCompat.getColor(context, R.color.color_sidebar_bg))
        bindItem(Item.HOME, R.id.itemHome)
        bindItem(Item.CHANNELS, R.id.itemChannels)
        bindItem(Item.MOVIES, R.id.itemMovies)
        bindItem(Item.SERIES, R.id.itemSeries)
        bindItem(Item.PLAYLIST, R.id.itemPlaylist)
        refresh()
    }

    private fun bindItem(item: Item, viewId: Int) {
        val row = findViewById<View>(viewId)
        val icon = row.findViewById<ImageView>(R.id.sidebarItemIcon)
        val label = row.findViewById<TextView>(R.id.sidebarItemLabel)
        icon.setImageResource(item.iconRes)
        label.setText(item.labelRes)
        row.isFocusable = true
        row.isClickable = true
        row.setOnClickListener { select(item, notify = true) }
        row.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && item != selectedItem) {
                row.setBackgroundResource(R.drawable.selector_focusable)
            } else if (!hasFocus && item != selectedItem) {
                row.background = null
            }
        }
        itemViews[item] = row
    }

    /** Marks [item] selected (red text/icon + tint + left bar). */
    fun select(item: Item, notify: Boolean = false) {
        selectedItem = item
        refresh()
        if (notify) onItemSelected?.invoke(item)
    }

    fun setSelected(item: Item) = select(item, notify = false)

    /** Floating rounded panel (Home) vs edge-to-edge panel (other screens). */
    fun setFloating(floating: Boolean) {
        if (floating) {
            setBackgroundResource(R.drawable.bg_sidebar_panel)
        } else {
            setBackgroundColor(ContextCompat.getColor(context, R.color.color_sidebar_bg))
        }
    }

    /** Portrait: the sidebar becomes a horizontal top bar. */
    fun setHorizontal(value: Boolean) {
        if (horizontal == value) return
        horizontal = value
        orientation = if (value) HORIZONTAL else VERTICAL
        findViewById<View>(R.id.sidebarMenu).let { menu ->
            (menu as? LinearLayout)?.orientation = if (value) HORIZONTAL else VERTICAL
        }
        findViewById<View>(R.id.sidebarSpacer).visibility = if (value) View.GONE else View.VISIBLE
        findViewById<View>(R.id.sidebarLogoText).visibility = if (value) View.GONE else View.VISIBLE
        for ((_, row) in itemViews) {
            row.layoutParams = row.layoutParams.apply {
                width = if (value) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
            }
        }
        findViewById<View>(R.id.sidebarLogo).let { logo ->
            (logo as? LinearLayout)?.setPadding(
                if (value) 8 else 24, if (value) 8 else 36, 8, if (value) 8 else 16,
            )
        }
        requestLayout()
    }

    private fun refresh() {
        for ((item, row) in itemViews) {
            val selected = item == selectedItem
            val icon = row.findViewById<ImageView>(R.id.sidebarItemIcon)
            val label = row.findViewById<TextView>(R.id.sidebarItemLabel)
            val bar = row.findViewById<View>(R.id.sidebarItemBar)
            bar.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.setBackgroundResource(
                if (selected) R.drawable.bg_sidebar_selected else 0,
            )
            val color = ContextCompat.getColor(
                context,
                if (selected) R.color.color_primary_red else R.color.color_text_secondary,
            )
            icon.setColorFilter(color)
            label.setTextColor(color)
            label.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }
}
