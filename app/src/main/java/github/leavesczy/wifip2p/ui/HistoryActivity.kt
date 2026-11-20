package github.leavesczy.wifip2p.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivityHistoryBinding
import github.leavesczy.wifip2p.databinding.ItemRecordBinding
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.transferHub

/** One line of a records list, already worded. */
data class RecordRow(
    val key: String,
    val title: String,
    val info: String,
    val primaryText: Int,
    val primaryAction: Action,
    val secondaryText: Int,
    val secondaryAction: Action,
    val location: String?,
    val name: String
) {
    enum class Action { NONE, RESUME, CLEAN_OUTGOING, CLEAN_INCOMING, OPEN }
}

/**
 * Unfinished transfers and history, read from the app's own records. Nothing runs in the background:
 * the lists are read when the screen opens and after every action.
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
            refreshSoon()
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

    private fun refreshSoon() {
        binding.root.postDelayed({ reload() }, 300)
    }

    private fun render() {
        val current = records ?: return
        val now = System.currentTimeMillis()
        binding.btnTabUnfinished.text = getString(R.string.tab_unfinished_count, current.unfinished.size)
        binding.btnClearHistory.show(!showUnfinished)
        val rows = if (showUnfinished) {
            current.unfinished.map { RecordRows.unfinished(this, it, now) }
        } else {
            current.history.map { RecordRows.history(this, it) }
        }
        adapter.submitList(rows)
        binding.tvEmpty.show(rows.isEmpty())
        binding.tvEmpty.setText(if (showUnfinished) R.string.unfinished_empty else R.string.history_empty)
    }

    private fun onAction(row: RecordRow, primary: Boolean) {
        RecordRows.perform(this, transferHub(), row, primary) { refreshSoon() }
    }
}

/** Shows the rows of a records list. Each row has an optional primary and secondary button. */
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
