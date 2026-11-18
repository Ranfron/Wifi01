package github.leavesczy.wifip2p.ui

import android.content.res.ColorStateList
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ItemPeerBinding
import github.leavesczy.wifip2p.databinding.ItemTransferBinding
import github.leavesczy.wifip2p.hub.Direction
import github.leavesczy.wifip2p.hub.TransferItem
import github.leavesczy.wifip2p.p2p.P2pPeer
import github.leavesczy.wifip2p.transfer.FileState
import github.leavesczy.wifip2p.transfer.Formatters

private val PEER_DIFF = object : DiffUtil.ItemCallback<P2pPeer>() {
    override fun areItemsTheSame(oldItem: P2pPeer, newItem: P2pPeer): Boolean = oldItem.address == newItem.address
    override fun areContentsTheSame(oldItem: P2pPeer, newItem: P2pPeer): Boolean = oldItem == newItem
}

private val TRANSFER_DIFF = object : DiffUtil.ItemCallback<TransferItem>() {
    override fun areItemsTheSame(oldItem: TransferItem, newItem: TransferItem): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: TransferItem, newItem: TransferItem): Boolean = oldItem == newItem
}

class PeerAdapter(
    private val onClick: (P2pPeer) -> Unit
) : ListAdapter<P2pPeer, PeerAdapter.Holder>(PEER_DIFF) {

    class Holder(val binding: ItemPeerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val peer = getItem(position)
        val binding = holder.binding
        binding.tvPeerName.text = peer.name
        binding.tvPeerStatus.setText(
            when (peer.status) {
                WifiP2pDevice.AVAILABLE -> R.string.peer_available
                WifiP2pDevice.CONNECTED -> R.string.peer_connected
                WifiP2pDevice.INVITED -> R.string.peer_invited
                WifiP2pDevice.FAILED -> R.string.peer_failed
                else -> R.string.peer_unavailable
            }
        )
        binding.root.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) {
                onClick(getItem(index))
            }
        }
    }
}

class TransferAdapter(
    private val onOpen: (TransferItem) -> Unit
) : ListAdapter<TransferItem, TransferAdapter.Holder>(TRANSFER_DIFF) {

    class Holder(val binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val binding = holder.binding
        val context = binding.root.context
        val outgoing = item.direction == Direction.OUT
        val size = Formatters.size(item.size)

        val icon: Int
        val color: Int
        val info: String
        when (item.state) {
            FileState.QUEUED -> {
                icon = R.drawable.ic_schedule
                color = R.color.status_neutral
                info = context.getString(R.string.state_waiting, size)
            }

            FileState.ACTIVE -> {
                icon = if (outgoing) R.drawable.ic_upload else R.drawable.ic_download
                color = R.color.status_active
                info = context.getString(if (outgoing) R.string.state_sending else R.string.state_receiving, size)
            }

            FileState.DONE -> {
                icon = R.drawable.ic_check_circle
                color = R.color.status_ok
                info = context.getString(if (outgoing) R.string.state_sent else R.string.state_saved, size)
            }

            FileState.FAILED -> {
                icon = R.drawable.ic_error
                color = R.color.status_error
                info = item.message ?: context.getString(R.string.state_failed)
            }

            FileState.INTERRUPTED -> {
                icon = R.drawable.ic_schedule
                color = R.color.status_neutral
                info = context.getString(R.string.state_interrupted)
            }

            FileState.CANCELLED -> {
                icon = R.drawable.ic_close
                color = R.color.status_neutral
                info = context.getString(R.string.state_cancelled)
            }
        }
        binding.tvName.text = item.name
        binding.tvInfo.text = info
        binding.ivStatus.setImageResource(icon)
        ImageViewCompat.setImageTintList(
            binding.ivStatus, ColorStateList.valueOf(ContextCompat.getColor(context, color))
        )

        val openable = !outgoing && item.state == FileState.DONE && item.location != null
        // setOnClickListener() makes the view clickable, so the flag must be set afterwards
        binding.root.setOnClickListener(if (openable) View.OnClickListener { onOpen(item) } else null)
        binding.root.isClickable = openable
    }
}
