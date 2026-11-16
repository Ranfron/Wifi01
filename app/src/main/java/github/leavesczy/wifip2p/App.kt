package github.leavesczy.wifip2p

import android.app.Application
import android.content.Context
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.service.TransferService
import github.leavesczy.wifip2p.storage.UriSendSource

class App : Application() {

    lateinit var hub: TransferHub
        private set

    override fun onCreate() {
        super.onCreate()
        hub = TransferHub(this)
        TransferService.ensureChannel(this)
        // Files that had to be staged for a previous (killed) session are useless now.
        UriSendSource.clearStaging(this)
    }
}

/** The process-wide transfer session controller. */
fun Context.transferHub(): TransferHub = (applicationContext as App).hub
