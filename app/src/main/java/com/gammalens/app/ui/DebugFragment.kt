package com.gammalens.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.gammalens.app.MainActivity
import com.gammalens.app.R
import com.gammalens.app.camera.DetectionMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DebugFragment : Fragment() {

    private var panelMvp: LinearLayout? = null
    private var panelSync: LinearLayout? = null
    private var panelEvt: LinearLayout? = null
    private var panelPerf: LinearLayout? = null
    private var mvpExpand: TextView? = null
    private var syncExpand: TextView? = null
    private var evtExpand: TextView? = null
    private var perfExpand: TextView? = null
    private var tvDetectionMode: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_debug, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panelMvp = view.findViewById(R.id.panel_mvp)
        panelSync = view.findViewById(R.id.panel_sync)
        panelEvt = view.findViewById(R.id.panel_evt)
        panelPerf = view.findViewById(R.id.panel_perf)
        mvpExpand = view.findViewById(R.id.mvpExpand)
        syncExpand = view.findViewById(R.id.syncExpand)
        evtExpand = view.findViewById(R.id.evtExpand)
        perfExpand = view.findViewById(R.id.perfExpand)
        tvDetectionMode = view.findViewById(R.id.tvDetectionMode)

        mvpExpand?.setOnClickListener { toggle(panelMvp, mvpExpand) }
        syncExpand?.setOnClickListener { toggle(panelSync, syncExpand) }
        evtExpand?.setOnClickListener { toggle(panelEvt, evtExpand) }
        perfExpand?.setOnClickListener { toggle(panelPerf, perfExpand) }

        view.findViewById<View>(R.id.btnCopyDebug)?.setOnClickListener {
            val main = requireActivity() as? MainActivity ?: return@setOnClickListener
            val summary = main.debugLogRepository.getDebugSummary()
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GammaLens Debug", summary))
            Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btnExportRunFolder)?.setOnClickListener {
            val main = requireActivity() as? MainActivity ?: return@setOnClickListener
            val manager = main.runSnapshotManager ?: com.gammalens.app.data.RunSnapshotManager(requireContext())
            val runDir = manager.getCurrentRunDir() ?: manager.getLatestRunDir()
            if (runDir == null || !runDir.exists()) {
                Toast.makeText(requireContext(), "暂无运行记录可导出", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val zipFile = manager.zipRunFolder(runDir)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    zipFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.export_run_folder)))
                Toast.makeText(requireContext(), "已生成 ${zipFile.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        val main = requireActivity() as? MainActivity ?: return
        bindDetectionMode(main.getDetectionMode())
        view.findViewById<View>(R.id.btnModeMog2)?.setOnClickListener {
            main.setDetectionMode(DetectionMode.MOG2_ONLY)
            bindDetectionMode(DetectionMode.MOG2_ONLY)
            Toast.makeText(requireContext(), "已切换到 MOG2_ONLY", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnModeDiff)?.setOnClickListener {
            main.setDetectionMode(DetectionMode.DIFF_ONLY)
            bindDetectionMode(DetectionMode.DIFF_ONLY)
            Toast.makeText(requireContext(), "已切换到 DIFF_ONLY", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnModeFusion)?.setOnClickListener {
            main.setDetectionMode(DetectionMode.FUSION)
            bindDetectionMode(DetectionMode.FUSION)
            Toast.makeText(requireContext(), "已切换到 FUSION", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            main.debugLogRepository.mvpStatusLines.collectLatest { lines ->
                fillPanel(panelMvp, lines)
            }
        }
        lifecycleScope.launch {
            main.debugLogRepository.glSyncLines.collectLatest { lines ->
                fillPanel(panelSync, lines)
            }
        }
        lifecycleScope.launch {
            main.debugLogRepository.glEvtLines.collectLatest { lines ->
                fillPanel(panelEvt, lines)
            }
        }
        lifecycleScope.launch {
            main.debugLogRepository.perfStatusLines.collectLatest { lines ->
                fillPanel(panelPerf, lines)
            }
        }
    }

    private fun bindDetectionMode(mode: DetectionMode) {
        tvDetectionMode?.text = "当前: ${mode.name}"
    }

    private fun toggle(panel: LinearLayout?, expandLabel: TextView?) {
        if (panel == null) return
        val visible = panel.visibility == View.VISIBLE
        panel.visibility = if (visible) View.GONE else View.VISIBLE
        expandLabel?.text = if (visible) "展开" else "收起"
    }

    private fun fillPanel(panel: LinearLayout?, lines: List<String>) {
        panel ?: return
        panel.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface_card))
        panel.removeAllViews()
        lines.forEach { line ->
            val tv = TextView(requireContext()).apply {
                text = line
                textSize = 11f
                setPadding(8, 4, 8, 4)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            panel.addView(tv)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        panelMvp = null
        panelSync = null
        panelEvt = null
        panelPerf = null
        mvpExpand = null
        syncExpand = null
        evtExpand = null
        perfExpand = null
        tvDetectionMode = null
    }
}
