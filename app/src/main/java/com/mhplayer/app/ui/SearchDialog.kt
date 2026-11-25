package com.mhplayer.app.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.databinding.DialogSearchBinding
import kotlinx.coroutines.launch

/**
 * Simple in-app search (Home / Movies / Series search icon): filters the
 * cached catalog (channels, movies, series) by name, live as the user
 * types. Tap a result to open it (channel -> fullscreen player,
 * movie/series -> details).
 */
class SearchDialog : DialogFragment() {

    private var _binding: DialogSearchBinding? = null
    private val binding get() = _binding!!

    private var channels: List<LiveChannel> = emptyList()
    private var vod: List<VodItem> = emptyList()
    private var series: List<SeriesItem> = emptyList()

    private data class Result(val kind: String, val id: Int, val title: String)

    private val adapter = object : RecyclerView.Adapter<ResultViewHolder>() {
        var results: List<Result> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
            val binding = com.mhplayer.app.databinding.ItemSearchResultBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return ResultViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
            val result = results[position]
            holder.binding.resultTitle.text = result.title
            holder.binding.resultKind.text = when (result.kind) {
                "live" -> "Live TV"
                "vod" -> "Movie"
                else -> "Series"
            }
            holder.itemView.setOnClickListener { openResult(result) }
        }

        override fun getItemCount(): Int = results.size
    }

    private class ResultViewHolder(val binding: com.mhplayer.app.databinding.ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSearchBinding.inflate(layoutInflater)
        binding.searchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResults.adapter = adapter
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            channels = app.repository.getAllLiveChannels()
            vod = app.repository.getAllVod()
            series = app.repository.getAllSeries()
            if (!isAdded) return@launch
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        if (!isAdded || _binding == null) return
        val q = query.trim()
        if (q.length < 2) {
            adapter.results = emptyList()
            binding.searchHint.visibility = View.VISIBLE
            binding.searchHint.text = getString(R.string.search_empty)
            return
        }
        val results = mutableListOf<Result>()
        results += channels.filter { it.name.contains(q, ignoreCase = true) }
            .take(10).map { Result("live", it.streamId, it.name) }
        results += vod.filter { it.name.contains(q, ignoreCase = true) }
            .take(10).map { Result("vod", it.streamId, it.name) }
        results += series.filter { it.name.contains(q, ignoreCase = true) }
            .take(10).map { Result("series", it.seriesId, it.name) }
        adapter.results = results
        binding.searchHint.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        if (results.isEmpty()) {
            binding.searchHint.text = getString(R.string.search_no_results, q)
        }
    }

    private fun openResult(result: Result) {
        val activity = requireActivity() as MainActivity
        dismiss()
        when (result.kind) {
            "live" -> {
                channels.firstOrNull { it.streamId == result.id }?.let {
                    activity.openPlayer(PlaybackHelper.liveArgs(it))
                }
            }
            "vod" -> {
                vod.firstOrNull { it.streamId == result.id }?.let {
                    activity.openDetails(PlaybackHelper.vodDetailsArgs(it))
                }
            }
            else -> {
                series.firstOrNull { it.seriesId == result.id }?.let {
                    activity.openDetails(PlaybackHelper.seriesDetailsArgs(it))
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
