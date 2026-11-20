package github.leavesczy.wifip2p.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.util.LocaleHelper
import github.leavesczy.wifip2p.databinding.ActivityScanBinding
import github.leavesczy.wifip2p.qr.QrDecoder
import github.leavesczy.wifip2p.transfer.ConnectCode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner. Returns the text of the first valid WifiP2P code in [EXTRA_PAYLOAD].
 * CameraX shows the preview and delivers the frames, ZXing finds the code - everything works
 * offline and without Google Play services.
 */
class ScanActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    private lateinit var binding: ActivityScanBinding
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val decoder = QrDecoder()
    private val found = AtomicBoolean(false)

    @Volatile
    private var lastForeignHintAt = 0L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.overlay.applySystemBarPadding()
        binding.btnClose.setOnClickListener { finish() }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the analysis thread for every camera frame. */
    private fun analyze(image: ImageProxy) {
        try {
            if (found.get()) {
                return
            }
            // plane 0 of a YUV frame is the brightness image, which is all a QR decoder needs
            val plane = image.planes[0]
            val luminance = QrDecoder.packLuminance(
                plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height
            )
            val text = decoder.decode(luminance, image.width, image.height) ?: return
            if (ConnectCode.parse(text) != null) {
                if (found.compareAndSet(false, true)) {
                    runOnUiThread {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, text))
                        finish()
                    }
                }
            } else {
                hintForeignCode()
            }
        } catch (_: Exception) {
            // a bad frame is simply skipped
        } finally {
            image.close()
        }
    }

    private fun hintForeignCode() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastForeignHintAt < 4_000) {
            return
        }
        lastForeignHintAt = now
        runOnUiThread { Toast.makeText(this, R.string.msg_qr_invalid, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_PAYLOAD = "payload"
    }
}
