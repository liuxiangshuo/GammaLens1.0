package com.gammalens.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gammalens.app.MainActivity
import com.gammalens.app.R
import com.gammalens.app.data.EventItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsFragment : Fragment() {

    private var eventsList: RecyclerView? = null
    private var adapter: EventsAdapter? = null
    private var emptyEvents: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_events, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eventsList = view.findViewById(R.id.eventsList)
        emptyEvents = view.findViewById(R.id.emptyEvents)
        eventsList?.layoutManager = LinearLayoutManager(requireContext())
        adapter = EventsAdapter(emptyList()) { item ->
            Toast.makeText(requireContext(), "blobCount=${item.blobCount}, maxArea=${item.maxArea}, nonZero=${item.nonZeroCount}\n${item.suppressReason ?: ""}", Toast.LENGTH_LONG).show()
        }
        eventsList?.adapter = adapter

        view.findViewById<Button>(R.id.btnExportCsv)?.setOnClickListener { exportCsv() }

        val main = requireActivity() as? MainActivity ?: return
        lifecycleScope.launch {
            main.eventRepository.events.collectLatest { list ->
                adapter?.update(list)
                val isEmpty = list.isEmpty()
                eventsList?.visibility = if (isEmpty) View.GONE else View.VISIBLE
                emptyEvents?.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        }
    }

    private fun exportCsv() {
        val main = requireActivity() as? MainActivity ?: return
        val list = main.eventRepository.getEventsForExport()
        val df = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val filename = "gammalens_events_${df.format(Date())}.csv"
        val dir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val file = File(dir, filename)
        val header = "id,timestampMs,streamId,scoreEma,blobCount,maxArea,nonZeroCount,suppressed,suppressReason\n"
        val rows = list.joinToString("") { e ->
            "${e.id},${e.timestampMs},${e.streamId},${e.scoreEma},${e.blobCount},${e.maxArea},${e.nonZeroCount},${e.suppressed},\"${e.suppressReason ?: ""}\"\n"
        }
        file.writeText(header + rows)
        val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_csv)))
        Toast.makeText(requireContext(), "已保存: $filename", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        eventsList = null
        adapter = null
        emptyEvents = null
    }
}

class EventsAdapter(
    private var items: List<EventItem>,
    private val onItemClick: (EventItem) -> Unit,
) : RecyclerView.Adapter<EventsAdapter.Holder>() {

    fun update(list: List<EventItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        return Holder(v, onItemClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View, private val onItemClick: (EventItem) -> Unit) : RecyclerView.ViewHolder(v) {
        private val eventTime: TextView = v.findViewById(R.id.eventTime)
        private val eventScore: TextView = v.findViewById(R.id.eventScore)
        private val eventSuppressedBadge: TextView = v.findViewById(R.id.eventSuppressedBadge)

        fun bind(item: EventItem) {
            val date = Date(item.timestampMs)
            eventTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
            eventScore.text = "scoreEma ${String.format("%.1f", item.scoreEma)}"
            eventSuppressedBadge.visibility = if (item.suppressed) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
