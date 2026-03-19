package com.gammalens.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.gammalens.app.MainActivity
import com.gammalens.app.R
import com.gammalens.app.data.DeviceProfile
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalibrationFragment : Fragment() {

    private var profileSummary: TextView? = null
    private var baselineTodo: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_calibration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileSummary = view.findViewById(R.id.profileSummary)
        baselineTodo = view.findViewById(R.id.baselineTodo)

        val main = requireActivity() as? MainActivity ?: return
        val profile = main.deviceProfile
        profileSummary?.text = buildString {
            append("model: ${profile.deviceModel}\n")
            append("cameraId: ${profile.cameraId}\n")
            append("pairWindowNs: ${profile.pairWindowNs}\n")
            append("flashThreshold: ${profile.flashThreshold}\n")
            append("area: ${profile.areaRangeMin}–${profile.areaRangeMax}\n")
            append("captureSize: ${profile.captureSize}\n")
            append("fpsTarget: ${profile.fpsTarget}\n")
        }

        view.findViewById<Button>(R.id.btnBaseline)?.setOnClickListener {
            baselineTodo?.visibility = View.VISIBLE
            Toast.makeText(requireContext(), getString(R.string.todo_not_implemented), Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnExportProfile)?.setOnClickListener { exportProfile(main.deviceProfile) }
    }

    private fun exportProfile(profile: DeviceProfile) {
        val json = JSONObject().apply {
            put("deviceModel", profile.deviceModel)
            put("cameraId", profile.cameraId)
            put("pairWindowNs", profile.pairWindowNs)
            put("flashThreshold", profile.flashThreshold)
            put("areaRangeMin", profile.areaRangeMin)
            put("areaRangeMax", profile.areaRangeMax)
            put("captureSize", profile.captureSize)
            put("fpsTarget", profile.fpsTarget)
        }
        val df = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val filename = "gammalens_profile_${df.format(Date())}.json"
        val dir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val file = File(dir, filename)
        file.writeText(json.toString(2))
        Toast.makeText(requireContext(), "已保存: $filename", Toast.LENGTH_SHORT).show()
        val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_profile)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        profileSummary = null
        baselineTodo = null
    }
}
