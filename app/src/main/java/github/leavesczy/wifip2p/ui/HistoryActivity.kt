package github.leavesczy.wifip2p.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivityHistoryBinding
import github.leavesczy.wifip2p.databinding.ItemRecordBinding
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.storage.FileOpener
import github.leavesczy.wifip2p.transfer.FileState
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transfer.HistoryEntry
import github.leavesczy.wifip2p.transferHub

/** One line of the history screen, already worded. */
data class RecordRow(
    val key: String,
    val title: String,
    val info: String,
    val primaryText: Int,
    val secondaryText: Int,
    val action: Action,
    val location: String?,
    val name: String
) {
    enum class Action { RESUME, CLEAN_OUTGOING, CLEAN_INCOMING, OPEN, NONE }
}

/**
 * Unfinished transfers and history, from the app's own records. Nothing here runs in the background:
 * the list is read when the screen opens and after every action.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var records: TransferHub.Records? = null
    private var showUnfinished = true

    private val adapter = RecordAdapter(
        onPrimary = { row -> onAction(row, primary = true) },
        onSecondary = { row -> onAction(row, primary = false) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvRecords.layoutManager = LinearLayoutManager(this)
        binding.rvRecords.adapter = adapter
        binding.btnTabUnfinished.setOnClickListener {
            showUnfinished = true
            render()
        }
        binding.btnTabHistory.setOnClickListener {
            showUnfinished = false
            render()
        }
        binding.btnClearHistory.setOnClickListener {
            transferHub().clearHistory()
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        transferHub().loadRecords { loaded ->
            records = loaded
            render()
        }
    }

    private fun render() {
        val current = records ?: return
        val now = System.currentTimeMillis()
        binding.btnTabUnfinished.text = getString(R.string.tab_unfinished_count, current.unfinished.size)
        binding.btnClearHistory.show(!showUnfinished)
        val rows = if (showUnfinished) {
            current.unfinished.map { row -> unfinishedRow(row, now) }
        } else {
            current.history.map { entry -> historyRow(entry) }
        }
        adapter.submitList(rows)
        binding.tvEmpty.show(rows.isEmpty())
        binding.tvEmpty.setText(if (showUnfinished) R.string.unfinished_empty else R.string.history_empty)
    }

    private fun unfinishedRow(row: TransferHub.UnfinishedRow, now: Long): RecordRow {
        val daysLeft = ((row.updatedAt + EXPIRY_MS) - now) / DAY_MS
        val expiry = if (daysLeft >= 1) {
            getString(R.string.row_expires_days, daysLeft.toInt())
        } else {
            getString(R.string.row_expires_today)
        }
        return if (row.outgoing) {
            val info = getString(R.string.row_info_outgoing, Formatters.size(row.size), expiry)
            RecordRow(
                key = row.key, title = row.name,
                info = if (row.reason.isNotEmpty()) info + "\n" + row.reason else info,
                primaryText = R.string.action_resume, secondaryText = R.string.action_clean,
                action = RecordRow.Action.RESUME, location = null, name = row.name
            )
        } else {
            val info = getString(
                R.string.row_info_incoming,
                Formatters.size(row.stored), Formatters.size(row.size), expiry
            )
            RecordRow(
                key = row.key, title = row.name, info = info,
                primaryText = R.string.action_clean, secondaryText = 0,
                action = RecordRow.Action.CLEAN_INCOMING, location = null, name = row.name
            )
        }
    }

    private fun historyRow(entry: HistoryEntry): RecordRow {
        val direction = getString(if (entry.outgoing) R.string.history_sent else R.string.history_received)
        val date = android.text.format.DateFormat.format("d MMM HH:mm", entry.time).toString()
        val info = listOf(direction, Formatters.size(entry.size), date, stateLabel(entry.state))
            .joinToString(" • ")
        val openable = !entry.outgoing && entry.state == FileState.DONE && entry.location.isNotEmpty()
        return RecordRow(
            key = entry.time.toString() + entry.name,
            title = entry.name,
            info = if (entry.reason.isNotEmpty() && entry.state != FileState.DONE) info + "\n" + entry.reason else info,
            primaryText = if (openable) R.string.action_open else 0,
            secondaryText = 0,
            action = if (openable) RecordRow.Action.OPEN else RecordRow.Action.NONE,
            location = entry.location.takeIf { openable },
            name = entry.name
        )
    }

    private fun stateLabel(state: FileState): String = getString(
        when (state) {
            FileState.DONE -> R.string.state_done_short
            FileState.CANCELLED -> R.string.state_cancelled
            FileState.INTERRUPTED -> R.string.state_interrupted
            else -> R.string.state_failed
        }
    )

    private fun onAction(row: RecordRow, primary: Boolean) {
        val hub = transferHub()
        when (row.action) {
            RecordRow.Action.RESUME -> if (primary) {
                hub.resume(row.key)
                Toast.makeText(this, R.string.msg_resume_queued, Toast.LENGTH_SHORT).show()
            } else {
                hub.clean(row.key)
            }

            RecordRow.Action.CLEAN_OUTGOING -> hub.clean(row.key)
            RecordRow.Action.CLEAN_INCOMING -> hub.cleanIncoming(row.key)
            RecordRow.Action.OPEN -> row.location?.let {
                if (!FileOpener.open(this, it, row.name)) {
                    Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                }
            }

            RecordRow.Action.NONE -> Unit
        }
        if (row.action != RecordRow.Action.OPEN && row.action != RecordRow.Action.NONE) {
            // the list changes after an action: read it again once the worker has done it
            binding.root.postDelayed({ reload() }, 300)
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val EXPIRY_MS = 5L * DAY_MS
    }
}

/** Shows the rows of the history screen. Each row has an optional primary and secondary action. */
class RecordAdapter(
    private val onPrimary: (RecordRow) -> Unit,
    private val onSecondary: (RecordRow) -> Unit
) : ListAdapter<RecordRow, RecordAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val b = holder.binding
        b.tvTitle.text = row.title
        b.tvInfo.text = row.info
        bindButton(b.btnPrimary, row.primaryText) { onPrimary(row) }
        bindButton(b.btnSecondary, row.secondaryText) { onSecondary(row) }
    }

    private fun bindButton(button: MaterialButton, textRes: Int, onClick: () -> Unit) {
        if (textRes == 0) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.setText(textRes)
            button.setOnClickListener { onClick() }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecordRow>() {
            override fun areItemsTheSame(oldItem: RecordRow, newItem: RecordRow): Boolean =
                oldItem.key == newItem.key && oldItem.title == newItem.title

            override fun areContentsTheSame(oldItem: RecordRow, newItem: RecordRow): Boolean = oldItem == newItem
        }
    }
}
