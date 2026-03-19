package com.gammalens.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gammalens.app.MainActivity
import com.gammalens.app.R
import com.gammalens.app.data.LiveStatsUiState
import com.gammalens.app.data.DualStatusUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.widget.Switch
import android.view.TextureView

class LiveFragment : Fragment() {

    private var scoreValue: TextView? = null
    private var riskLabel: TextView? = null
    private var metricEvents: TextView? = null
    private var metricRate: TextView? = null
    private var metricLastAgo: TextView? = null
    private var metricCooldown: TextView? = null
    private var chipSingleDual: TextView? = null
    private var chipPairing: TextView? = null
    private var chipSuppression: TextView? = null
    private var chipFallback: TextView? = null
    private var chipFps: TextView? = null
    private var measurementHint: TextView? = null
    private var systemStatus: TextView? = null
    private var metricConfidence: TextView? = null
    private var modeLabel: TextView? = null
    private var modeSwitch: Switch? = null
    private var previewTexture: TextureView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scoreValue = view.findViewById(R.id.scoreValue)
        riskLabel = view.findViewById(R.id.riskLabel)
        metricEvents = view.findViewById(R.id.metricEvents)
        metricRate = view.findViewById(R.id.metricRate)
        metricLastAgo = view.findViewById(R.id.metricLastAgo)
        metricCooldown = view.findViewById(R.id.metricCooldown)
        chipSingleDual = view.findViewById(R.id.chipSingleDual)
        chipPairing = view.findViewById(R.id.chipPairing)
        chipSuppression = view.findViewById(R.id.chipSuppression)
        chipFallback = view.findViewById(R.id.chipFallback)
        chipFps = view.findViewById(R.id.chipFps)
        measurementHint = view.findViewById(R.id.measurementHint)
        systemStatus = view.findViewById(R.id.systemStatus)
        metricConfidence = view.findViewById(R.id.metricConfidence)
        modeLabel = view.findViewById(R.id.modeLabel)
        modeSwitch = view.findViewById(R.id.modeSwitch)
        previewTexture = view.findViewById(R.id.previewTexture)

        // Fix overlap: apply system bar insets so overlay content is below status bar and above nav
        val overlay = view.findViewById<View>(R.id.overlay)
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        overlay.requestApplyInsets()

        (requireActivity() as? MainActivity)?.setPreviewTextureView(previewTexture)

        view.findViewById<ImageButton>(R.id.btnInfo)?.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(),
                "建议遮住摄像头并保持静止。\n先关注“测量条件”和“风险等级”，高级指标已自动处理。",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        modeSwitch?.setOnCheckedChangeListener { _, checked ->
            modeLabel?.text = if (checked) getString(R.string.mode_ambient) else getString(R.string.mode_shaded)
            (requireActivity() as? MainActivity)?.setMeasurementAmbientMode(checked)
        }
        modeLabel?.text = if (modeSwitch?.isChecked == true) getString(R.string.mode_ambient) else getString(R.string.mode_shaded)
        (requireActivity() as? MainActivity)?.setMeasurementAmbientMode(modeSwitch?.isChecked == true)

        val main = requireActivity() as? MainActivity ?: return
        lifecycleScope.launch {
            main.statsRepository.liveStats.collectLatest { s -> bindLive(s) }
        }
        lifecycleScope.launch {
            main.statsRepository.dualStatus.collectLatest { d -> bindDual(d) }
        }
    }

    private fun bindLive(s: LiveStatsUiState) {
        scoreValue?.text = String.format("%.1f", s.scoreEma)
        riskLabel?.text = when (s.riskLevel) {
            2 -> getString(R.string.risk_high)
            1 -> getString(R.string.risk_medium)
            else -> getString(R.string.risk_low)
        }
        val riskColor = when (s.riskLevel) {
            2 -> ContextCompat.getColor(requireContext(), R.color.risk_red)
            1 -> ContextCompat.getColor(requireContext(), R.color.risk_yellow)
            else -> ContextCompat.getColor(requireContext(), R.color.risk_green)
        }
        riskLabel?.setTextColor(riskColor)
        metricEvents?.text = "事件: ${s.events}"
        metricRate?.text = "率: ${s.rate60s}/min"
        metricLastAgo?.text = "上次: ${if (s.lastAgoSec >= 0) "${s.lastAgoSec}s" else "—"}"
        metricCooldown?.text = "冷却: ${if (s.cooldownLeftSec >= 0) "${s.cooldownLeftSec}s" else "—"}"
        chipFps?.text = if (s.fps > 0f) {
            "${String.format("%.0f", s.fps)}fps ${String.format("%.1f", s.processMsAvg)}ms ${s.detectionMode}"
        } else {
            "— fps"
        }
        measurementHint?.text = when {
            !s.darkFieldReady && !s.motionStable -> getString(R.string.hint_result_unreliable)
            !s.darkFieldReady -> getString(R.string.hint_dark_cover)
            !s.motionStable -> getString(R.string.hint_keep_still)
            s.reliability == "LIMITED" -> getString(R.string.hint_result_limited)
            else -> getString(R.string.hint_result_reliable)
        }
        measurementHint?.setTextColor(
            when {
                s.reliability == "RELIABLE" -> ContextCompat.getColor(requireContext(), R.color.risk_green)
                s.reliability == "LIMITED" -> ContextCompat.getColor(requireContext(), R.color.risk_yellow)
                else -> ContextCompat.getColor(requireContext(), R.color.risk_red)
            }
        )
        val conditionLabel = when (s.reliability) {
            "RELIABLE" -> "良好"
            "LIMITED" -> "受限"
            else -> "差"
        }
        val stabilityLabel = when (s.stabilityLevel) {
            "HIGH" -> "高"
            "MEDIUM" -> "中"
            else -> "低"
        }
        val checkLabel = when {
            !s.poissonWarmupReady -> "条件不足"
            s.riskTriggerState && s.riskScore >= 0.65 -> "高置信"
            s.cusumPass && s.poissonPass && s.classifierProbability >= 0.55 -> "全通过"
            else -> "部分等待"
        }
        metricConfidence?.text = "条件:$conditionLabel | 稳定:$stabilityLabel | 检验:$checkLabel | 置信:${String.format("%.2f", s.riskScore)}"
        if (s.systemStatusMessage.isBlank()) {
            systemStatus?.visibility = View.GONE
        } else {
            systemStatus?.visibility = View.VISIBLE
            systemStatus?.text = s.systemStatusMessage
            val color = when (s.systemStatusLevel) {
                "error" -> ContextCompat.getColor(requireContext(), R.color.risk_red)
                "warn" -> ContextCompat.getColor(requireContext(), R.color.risk_yellow)
                else -> ContextCompat.getColor(requireContext(), R.color.text_secondary)
            }
            systemStatus?.setTextColor(color)
        }
    }

    private fun bindDual(d: DualStatusUiState) {
        chipSingleDual?.text = if (d.dualActive) "双摄" else "单摄"
        chipPairing?.text = "配对: ${if (d.enablePairing) "开" else "—"}"
        chipSuppression?.text = "抑制: ${if (d.suppressionActive) "开" else "—"}"
        chipFallback?.visibility = if (d.fallback) View.VISIBLE else View.GONE
        chipFallback?.text = "降级"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as? MainActivity)?.setPreviewTextureView(null)
        previewTexture = null
        scoreValue = null
        riskLabel = null
        metricEvents = null
        metricRate = null
        metricLastAgo = null
        metricCooldown = null
        chipSingleDual = null
        chipPairing = null
        chipSuppression = null
        chipFallback = null
        chipFps = null
        measurementHint = null
        systemStatus = null
        metricConfidence = null
        modeLabel = null
        modeSwitch = null
    }
}
