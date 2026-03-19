package com.gammalens.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gammalens.app.MainActivity
import com.gammalens.app.R
import com.gammalens.app.data.HistogramUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SpectrumFragment : Fragment() {

    private var histogramContainer: LinearLayout? = null
    private var spectrumTotal: TextView? = null
    private var emptySpectrum: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_spectrum, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        histogramContainer = view.findViewById(R.id.histogramContainer)
        spectrumTotal = view.findViewById(R.id.spectrumTotal)
        emptySpectrum = view.findViewById(R.id.emptySpectrum)

        val main = requireActivity() as? MainActivity ?: return
        lifecycleScope.launch {
            main.histogramRepository.histogram.collectLatest { bind(it) }
        }
        main.histogramRepository.refresh()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? MainActivity)?.histogramRepository?.refresh()
    }

    private fun bind(state: HistogramUiState) {
        spectrumTotal?.text = "共 ${state.totalCount} 个事件 (${state.windowSec}秒窗口)"
        val container = histogramContainer ?: return
        container.removeAllViews()
        val maxCount = state.areaBuckets.maxOrNull()?.coerceAtLeast(1) ?: 1
        val isEmpty = state.totalCount <= 0 || state.areaBuckets.all { it <= 0 }
        emptySpectrum?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        container.visibility = if (isEmpty) View.GONE else View.VISIBLE
        state.areaBuckets.forEachIndexed { i, count ->
            val label = state.bucketLabels.getOrNull(i) ?: "?"
            container.addView(buildBarRow(label, count, maxCount))
        }
    }

    private fun buildBarRow(label: String, count: Int, maxCount: Int): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(0), dp(6), dp(0), dp(6))
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val barContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, dp(10), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_card_stroke))
        }
        val ratio = (count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
        val fill = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, ratio)
            setBackgroundColor(ContextCompat.getColor(context, R.color.brand_secondary))
        }
        val empty = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - ratio)
        }
        barContainer.addView(fill)
        barContainer.addView(empty)
        val valueView = TextView(context).apply {
            text = count.toString()
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        row.addView(labelView)
        row.addView(barContainer)
        row.addView(valueView)
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        histogramContainer = null
        spectrumTotal = null
        emptySpectrum = null
    }
}
