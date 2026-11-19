package github.leavesczy.wifip2p.ui

import android.content.Context
import android.widget.Toast
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.storage.FileOpener
import github.leavesczy.wifip2p.transfer.FileState
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transfer.HistoryEntry

/** How unfinished and finished transfers look on the screens, and what their buttons do. Used by History and Session. */
object RecordRows {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val EXPIRY_MS = 5L * DAY_MS

    fun unfinished(context: Context, row: TransferHub.UnfinishedRow, now: Long): RecordRow {
        val daysLeft = ((row.updatedAt + EXPIRY_MS) - now) / DAY_MS
        val expiry = if (daysLeft >= 1) {
            context.getString(R.string.row_expires_days, daysLeft.toInt())
        } else {
            context.getString(R.string.row_expires_today)
        }
        return if (row.outgoing) {
            val info = context.getString(R.string.row_info_outgoing, Formatters.size(row.size), expiry)
            RecordRow(
                key = row.key,
                title = row.name,
                info = if (row.reason.isNotEmpty()) info + "\n" + row.reason else info,
                primaryText = R.string.action_resume,
                primaryAction = RecordRow.Action.RESUME,
                secondaryText = R.string.action_clean,
                secondaryAction = RecordRow.Action.CLEAN_OUTGOING,
                location = null,
                name = row.name
            )
        } else {
            RecordRow(
                key = row.key,
                title = row.name,
                info = context.getString(
                    R.string.row_info_incoming, Formatters.size(row.stored), Formatters.size(row.size), expiry
                ),
                primaryText = R.string.action_clean,
                primaryAction = RecordRow.Action.CLEAN_INCOMING,
                secondaryText = 0,
                secondaryAction = RecordRow.Action.NONE,
                location = null,
                name = row.name
            )
        }
    }

    fun history(context: Context, entry: HistoryEntry): RecordRow {
        val direction = context.getString(if (entry.outgoing) R.string.history_sent else R.string.history_received)
        val date = android.text.format.DateFormat.format("d MMM HH:mm", entry.time).toString()
        val info = listOf(direction, Formatters.size(entry.size), date, stateLabel(context, entry.state))
            .joinToString(" • ")
        val openable = !entry.outgoing && entry.state == FileState.DONE && entry.location.isNotEmpty()
        return RecordRow(
            key = entry.time.toString() + entry.name,
            title = entry.name,
            info = if (entry.reason.isNotEmpty() && entry.state != FileState.DONE) info + "\n" + entry.reason else info,
            primaryText = if (openable) R.string.action_open else 0,
            primaryAction = if (openable) RecordRow.Action.OPEN else RecordRow.Action.NONE,
            secondaryText = 0,
            secondaryAction = RecordRow.Action.NONE,
            location = entry.location.takeIf { openable },
            name = entry.name
        )
    }

    fun stateLabel(context: Context, state: FileState): String = context.getString(
        when (state) {
            FileState.DONE -> R.string.state_done_short
            FileState.CANCELLED -> R.string.state_cancelled
            FileState.INTERRUPTED -> R.string.state_interrupted
            else -> R.string.state_failed
        }
    )

    /** Runs the action of one button. [onDone] is called right away; the lists are read again by the caller. */
    fun perform(context: Context, hub: TransferHub, row: RecordRow, primary: Boolean, onDone: () -> Unit) {
        when (if (primary) row.primaryAction else row.secondaryAction) {
            RecordRow.Action.RESUME -> {
                hub.resume(row.key)
                Toast.makeText(context, R.string.msg_resume_queued, Toast.LENGTH_SHORT).show()
                onDone()
            }

            RecordRow.Action.CLEAN_OUTGOING -> {
                hub.clean(row.key)
                onDone()
            }

            RecordRow.Action.CLEAN_INCOMING -> {
                hub.cleanIncoming(row.key)
                onDone()
            }

            RecordRow.Action.OPEN -> openFile(context, row)
            RecordRow.Action.NONE -> Unit
        }
    }

    private fun openFile(context: Context, row: RecordRow) {
        val location = row.location ?: return
        if (!FileOpener.open(context, location, row.name)) {
            Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }
}
