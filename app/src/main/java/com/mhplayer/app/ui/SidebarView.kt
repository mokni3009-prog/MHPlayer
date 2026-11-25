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
 * Zeno Play sidebar (v1.6) — FIXED and identical on every screen, exactly
 * as in the approved Home image: the Z logo + "Zeno Play" in gold, then
 * Home / Live TV / Movies / Series / Favorites / Settings, with Account
 * pinned at the bottom. Only the active section changes: the selected row
 * gets the slate gradient pill background, a gold icon and white bold text;
 * unselected rows are transparent with muted icons and secondary text.
 * Every item is focusable with a visible gold focus highlight (DPAD + touch).
 * In portrait the fragment calls [setHorizontal] and the sidebar becomes a
 * top bar with the same items laid out horizontally.
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
        FAVORITES(R.string.sidebar_favorites, R.drawable.ic_favorite),
        SETTINGS(R.string.sidebar_settings, R.drawable.ic_settings),
        ACCOUNT(R.string.sidebar_account, R.drawable.ic_user),
    }

    var onItemSelected: ((Item) -> Unit)? = null

    private var selectedItem: Item = Item.HOME
    private var horizontal = false

    private val itemViews = mutableMapOf<Item, View>()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_sidebar, this, true)
        setBackgroundResource(R.drawable.bg_sidebar_panel)
        bindItem(Item.HOME, R.id.itemHome)
        bindItem(Item.CHANNELS, R.id.itemChannels)
        bindItem(Item.MOVIES, R.id.itemMovies)
        bindItem(Item.SERIES, R.id.itemSeries)
        bindItem(Item.FAVORITES, R.id.itemFavorites)
        bindItem(Item.SETTINGS, R.id.itemSettings)
        bindItem(Item.ACCOUNT, R.id.itemAccount)
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

    /** Marks [item] selected (slate pill + gold icon + white text). */
    fun select(item: Item, notify: Boolean = false) {
        selectedItem = item
        refresh()
        if (notify) onItemSelected?.invoke(item)
    }

    fun setSelected(item: Item) = select(item, notify = false)

    /**
     * The sidebar is a floating rounded panel on EVERY screen (the fixed
     * sidebar rule) — the parameter is kept for compatibility and ignored.
     */
    fun setFloating(@Suppress("UNUSED_PARAMETER") floating: Boolean) {
        setBackgroundResource(R.drawable.bg_sidebar_panel)
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
            row.setBackgroundResource(
                if (selected) R.drawable.bg_sidebar_selected else 0,
            )
            val iconColor = ContextCompat.getColor(
                context,
                if (selected) R.color.zeno_gold else R.color.zeno_text_muted,
            )
            val labelColor = ContextCompat.getColor(
                context,
                if (selected) R.color.zeno_text_primary else R.color.zeno_text_secondary,
            )
            icon.setColorFilter(iconColor)
            label.setTextColor(labelColor)
        }
    }
}
