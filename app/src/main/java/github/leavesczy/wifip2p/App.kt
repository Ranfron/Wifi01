package github.leavesczy.wifip2p

import android.app.Application
import android.content.Context
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.service.TransferService
import github.leavesczy.wifip2p.storage.DownloadsMedium
import github.leavesczy.wifip2p.storage.UriSendSource
import github.leavesczy.wifip2p.transfer.FileIncomingStore
import github.leavesczy.wifip2p.transfer.HistoryLog
import github.leavesczy.wifip2p.transfer.OutgoingLedger
import github.leavesczy.wifip2p.util.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.io.File

class App : Application() {

    lateinit var hub: TransferHub
        private set

    override fun onCreate() {
        super.onCreate()
        // English unless the user chose otherwise (the phone language is not followed)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Settings.language(this)))
        TransferService.ensureChannel(this)
        // Files that had to be staged for a previous (killed) session are useless now.
        UriSendSource.clearStaging(this)
        hub = TransferHub(
            app = this,
            ledger = OutgoingLedger(File(filesDir, "outgoing.log")),
            history = HistoryLog(File(filesDir, "history.log")),
            // received files are written once, straight into Downloads/WifiP2P
            incoming = FileIncomingStore(File(filesDir, "incoming-meta"), DownloadsMedium(this))
        )
    }
}

/** The process-wide transfer session controller. */
fun Context.transferHub(): TransferHub = (applicationContext as App).hub
