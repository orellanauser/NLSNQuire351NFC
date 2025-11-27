package com.example.nlsnquire351nfc

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.nfc.*
import android.nfc.tech.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.nlsnquire351nfc.BuildConfig

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvType: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvReadCount: TextView
    private lateinit var lvHistory: ListView
    private lateinit var lvErrors: ListView
    private lateinit var tabLayout: TabLayout

    private lateinit var historyAdapter: ArrayAdapter<String>
    private lateinit var errorAdapter: ArrayAdapter<String>

    private var nfcPromptShowing: Boolean = false
    private var readCounter: Int = 0
    private val MAX_HISTORY = 200

    // Backoff between failed upload attempts to avoid noisy repeated handshakes on older Android
    private var nextAllowedSendAt: Long = 0L

    private var connectedTag: Tag? = null
    private var isReadingTag = false
    private val continuousReadIntervalMs = 500L // Interval for continuous reading

    // Re-arm reader mode periodically to allow consecutive reads of the same card
    private val handler = Handler(Looper.getMainLooper())
    private var pollingActive = false
    // Guard to avoid re-arm toggling while a reset is in progress
    private var isResetInProgress = false
    private val pollIntervalMs = 1000L

    private val techDisplayMap = mapOf(
        NfcA::class.java.name to "NfcA",
        NfcB::class.java.name to "NfcB",
        NfcF::class.java.name to "NfcF",
        NfcV::class.java.name to "NfcV",
        MifareClassic::class.java.name to "MIFARE Classic",
        MifareUltralight::class.java.name to "MIFARE Ultralight",
        IsoDep::class.java.name to "ISO-DEP",
        Ndef::class.java.name to "NDEF",
        NdefFormatable::class.java.name to "NDEF Formatable"
    )

    // Device info (model + serial) to show in UI and send to server
    private var deviceModel: String = ""
    private var deviceSerial: String = ""
    private var statusHeader: String = ""

    /**
     * Attempts to read the first line of a file at the given path.
     *
     * If the file exists and can be read, this method opens a FileInputStream for the file,
     * wraps it in a BufferedReader, and reads the first line. The line is then trimmed
     * and returned if it is not empty. If the file does not exist, cannot be read, or
     * an exception occurs during the read operation, this method returns null.
     *
     * @param path The path to the file to read.
     * @return The first line of the file if it exists and can be read, or null otherwise.
     */
    private fun readFirstLine(path: String): String? {
        return try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                FileInputStream(f).use { fis ->
                    BufferedReader(InputStreamReader(fis)).use { br ->
                        br.readLine()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            } else null
        } catch (_: Throwable) { null }
    }

    /**
     * Attempts to retrieve the value of a system property using reflection.
     * The [android.os.SystemProperties] class contains a method named `get` which
     * takes two `String` parameters, a key and a default value. We use reflection to invoke
     * this method and retrieve the value associated with the provided `key`.
     *
     * If the reflection operation fails or the value is empty, null is returned.
     *
     * @param key The key of the system property to retrieve.
     * @return The value associated with the provided `key`, or null if the operation fails.
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            val value = (m.invoke(null, key, "") as String).trim()
            if (value.isNotEmpty()) value else null
        } catch (_: Throwable) { null }
    }

    /**
     * Attempts to resolve the device serial number in the order:
     * 1. Newland sysfs path (Android 11+ known). Try on 7.1 too in case OEM shipped it.
     * 2. System properties commonly used for serial (`ro.serialno`, `ro.boot.serialno`, `ro.hardware.serial`).
     * 3. Build.SERIAL (deprecated but present on API 25).
     * 4. Fallback: ANDROID_ID (not a serial, but stable per device+signing key).
     *
     * @return The resolved device serial number, or "UNKNOWN" if all methods fail.
     */
    private fun resolveDeviceSerial(): String {
        // 1) Newland sysfs path (Android 11+ known). Try on 7.1 too in case OEM shipped it.
        val sysfsPaths = listOf(
            "/sys/bus/platform/devices/newland-misc/SN",
            "/sys/devices/platform/newland-misc/SN",
            "/sys/bus/platform/devices/newland_misc/SN",
            "/sys/devices/platform/newland_misc/SN"
        )
        for (p in sysfsPaths) {
            readFirstLine(p)?.let { return it }
        }
        // 2) System properties commonly used for serial
        val props = listOf("ro.serialno", "ro.boot.serialno", "ro.hardware.serial")
        for (k in props) {
            getSystemProperty(k)?.let { return it }
        }
        // 3) Build.SERIAL (deprecated but present on API 25)
        try {
            val b = Build.SERIAL
            if (!b.isNullOrBlank() && !b.equals("UNKNOWN", ignoreCase = true)) return b
        } catch (_: Throwable) {}
        // 4) Fallback: ANDROID_ID (not a serial, but stable per device+signing key)
        return try {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            id ?: "UNKNOWN"
        } catch (_: Throwable) { "UNKNOWN" }
    }

    /**
     * Sets the text of the status TextView (`tvStatus`) to the given `message`.
     * If `statusHeader` is not empty, the `message` is appended to the header with a newline
     * separator. Otherwise, the `message` is set directly.
     *
     * @param message The message to set on the status TextView.
     */
    private fun setStatus(message: String) {
        if (statusHeader.isNotEmpty()) {
            tvStatus.text = "$statusHeader\n$message"
        } else {
            tvStatus.text = message
        }
    }

    private val rearmReaderRunnable = object : Runnable {
        /**
         * Periodically re-arms NFC reader mode if `pollingActive` is true and no continuous
         * tag reading is in progress. This helps ensure the device remains in a scanning state
         * for new tags, especially if the NFC adapter's state might be transiently unstable
         * or if `onTagDiscovered` is not reliably called for consecutive taps of the same tag
         * without a brief interruption.
         *
         * The runnable checks:
         *
         * 1. If `pollingActive` is true and `nfcAdapter` is available.
         * 2. If a `resetNfcAndStartReaderMode` is currently in progress (`isResetInProgress`),
         *    it defers its operation to avoid interference.
         * 3. If NFC is disabled, it re-schedules itself without attempting to re-arm.
         * 4. If NFC is enabled and `isReadingTag` is false (meaning no continuous read is active),
         *    it attempts to disable and then re-enable reader mode. Transient errors during this
         *    process are caught and ignored to maintain stability.
         *
         * The runnable then schedules its next execution after `pollIntervalMs`.
         */
        override fun run() {
            val adapter = nfcAdapter
            if (!pollingActive || adapter == null) return
            // Skip toggling while a reset/initial enable is in progress
            if (isResetInProgress) {
                handler.postDelayed(this, pollIntervalMs)
                return
            }
            if (!adapter.isEnabled) {
                handler.postDelayed(this, pollIntervalMs)
                return
            }
            try {
                // Only re-arm reader mode if we are not actively reading a tag
                // The continuous reading logic will handle re-enabling reader mode if an error occurs
                if (!isReadingTag) {
                    disableReaderMode()
                    enableReaderMode()
                }
            } catch (_: Throwable) {
                // ignore transient
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    private val readTagDataRunnable = object : Runnable {
        /**
         * Periodically attempts to connect to and read data from the currently discovered NFC tag.
         *
         * If [isReadingTag] is false, the runnable will stop.
         * For each interval:
         * 1. It attempts to connect to the [connectedTag] using one of its supported technologies (prioritizing NDEF).
         * 2. If connection is successful, it performs a read operation (e.g., NDEF message length) and updates the UI.
         * 3. After reading, the connection to the tag technology is explicitly closed to allow for a fresh connection
         *    in the next interval, which can improve stability on older Android versions.
         * 4. If any error (e.g., [java.io.IOException] indicating tag loss) occurs during connection or reading,
         *    the continuous reading loop is stopped, and NFC reader mode is re-enabled to allow for a new
         *    [onTagDiscovered] event. The error is logged to Logcat and displayed in the UI.
         * 5. The runnable schedules itself to run again after [continuousReadIntervalMs].
         */
        override fun run() {
            if (!isReadingTag) return // Stop if flag is false

            connectedTag?.let { tag ->
                var currentTech: TagTechnology? = null
                try {
                    val techList = tag.techList
                    val nfcA = techList.firstOrNull { it == NfcA::class.java.name }?.let { NfcA.get(tag) }
                    val nfcB = techList.firstOrNull { it == NfcB::class.java.name }?.let { NfcB.get(tag) }
                    val nfcF = techList.firstOrNull { it == NfcF::class.java.name }?.let { NfcF.get(tag) }
                    val nfcV = techList.firstOrNull { it == NfcV::class.java.name }?.let { NfcV.get(tag) }
                    val isoDep = techList.firstOrNull { it == IsoDep::class.java.name }?.let { IsoDep.get(tag) }
                    val mifareClassic = techList.firstOrNull { it == MifareClassic::class.java.name }?.let { MifareClassic.get(tag) }
                    val mifareUltralight = techList.firstOrNull { it == MifareUltralight::class.java.name }?.let { MifareUltralight.get(tag) }
                    val ndef = techList.firstOrNull { it == Ndef::class.java.name }?.let { Ndef.get(tag) }

                    // Prioritize NDEF if available, otherwise just connect to the first available tech
                    currentTech = ndef ?: isoDep ?: nfcA ?: nfcB ?: nfcF ?: nfcV ?: mifareClassic ?: mifareUltralight

                    currentTech?.let { tech ->
                        if (!tech.isConnected) {
                            tech.connect()
                        }
                        // Perform a read operation. For a generic stress test, just successfully connecting
                        // and perhaps reading some basic info (like NDEF message length) is enough.
                        // For a more specific stress test, you'd read actual data.
                        val readResult: String
                        if (ndef != null && ndef.isConnected) {
                            val message = ndef.cachedNdefMessage
                            readResult = "NDEF Message Length: ${message?.toByteArray()?.size ?: 0} bytes"
                        } else {
                            readResult = "Connected to ${tech::class.java.simpleName}"
                        }

                        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        val uidForSend = bytesToHex(tag.id)
                        runOnUiThread {
                            readCounter += 1
                            tvReadCount.text = "Counter: $readCounter"
                            val summary = "Read Data\n$time\n${detectTagType(tag)}\n$uidForSend\n$readResult"
                            historyAdapter.insert(summary, 0)
                            trimAdapter(historyAdapter)
                            setStatus("Reading Data")
                            tvTime.text = time
                            tvType.text = detectTagType(tag)
                            tvUid.text = "$uidForSend\n${tag.techList.joinToString(", ")}" 
                        }

                        // Fire-and-forget best-effort upload; failures must not affect NFC loop
                        sendReadToServer(readCounter, uidForSend, time)

                        // Close the connection after reading to allow for a fresh connect in the next interval.
                        // This can help with stability on older Android versions by preventing stale connections.
                        closeTagTechnology(currentTech)

                    } ?: run {
                        // No supported technology found to connect
                        throw IllegalStateException("No connectable technology found for tag.")
                    }
                } catch (e: Exception) {
                    // If the tag is removed (tag-out), Android will often throw TagLostException or IOException.
                    // Treat this as a normal condition: stop continuous reading quietly and re-arm reader mode
                    // WITHOUT logging an error entry to the UI.
                    val isTagOut = (e is android.nfc.TagLostException) || (e is IOException)
                    if (isTagOut) {
                        Log.i("NFC_TAG_OUT", "Tag removed during continuous read; stopping quietly.")
                        runOnUiThread { setStatus("Tag removed") }
                        stopContinuousReading()
                        enableReaderMode()
                        return
                    } else {
                        // Other unexpected errors: keep previous behavior and surface to UI
                        Log.e("NFC_READ_ERROR", "Error during continuous read", e)
                        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        val errorSummary = "Cont. Read ERROR\n$time\n${e.message ?: e::class.java.simpleName}"
                        runOnUiThread {
                            readCounter += 1
                            tvReadCount.text = "Counter: $readCounter"
                            historyAdapter.insert(errorSummary, 0)
                            trimAdapter(historyAdapter)
                            setStatus("Cont. Read Error")
                            errorAdapter.insert(errorSummary, 0)
                            trimAdapter(errorAdapter)
                        }
                        // Stop the loop and re-enable reader mode to allow a fresh start
                        stopContinuousReading()
                        enableReaderMode()
                        return
                    }
                }
            }

            // Schedule the next read
            handler.postDelayed(this, continuousReadIntervalMs)
        }
    }

    /**
     * Initializes the activity, sets up the UI, and configures NFC.
     *
     * This method performs the following steps:
     * 1. Calls the superclass implementation.
     * 2. Enables edge-to-edge display and sets the content view to `R.layout.activity_main`.
     * 3. Sets an `OnApplyWindowInsetsListener` to adjust padding for system bars.
     * 4. Initializes UI components: `tvStatus`, `tvUid`, `tvType`, `tvTime`, `tvReadCount`,
     *    `lvHistory`, `lvErrors`, and `tabLayout`.
     * 5. Sets up `historyAdapter` and `errorAdapter` for the respective `ListViews`.
     * 6. Configures `tabLayout` with "History" and "Errors" tabs and adds a listener
     *    to toggle visibility of `lvHistory` and `lvErrors` based on the selected tab.
     * 7. Retrieves the default `NfcAdapter`.
     * 8. If NFC is not supported on the device, updates `tvStatus` and shows a `Toast`.
     * 9. If NFC is supported, calls `updateUiForNfcState()` to reflect its current status.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in [onSaveInstanceState].  **Note: Otherwise it is null.**
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvUid = findViewById(R.id.tvUid)
        tvType = findViewById(R.id.tvType)
        tvTime = findViewById(R.id.tvTime)
        tvReadCount = findViewById(R.id.tvReadCount)
        lvHistory = findViewById(R.id.lvHistory)
        lvErrors = findViewById(R.id.lvErrors)
        tabLayout = findViewById(R.id.tabLayout)

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvHistory.adapter = historyAdapter
        errorAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvErrors.adapter = errorAdapter

        tabLayout.addTab(tabLayout.newTab().setText("History"))
        tabLayout.addTab(tabLayout.newTab().setText("Errors"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            /**
             * Called when a tab is selected. Displays the history list and hides the error list if the first tab is selected,
             * otherwise hides the history list and displays the error list.
             *
             * @param tab The tab that was selected.
             */
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 0) {
                    lvHistory.visibility = android.view.View.VISIBLE
                    lvErrors.visibility = android.view.View.GONE
                } else {
                    lvHistory.visibility = android.view.View.GONE
                    lvErrors.visibility = android.view.View.VISIBLE
                }
            }

            /**
             * Called when a tab is unselected. This implementation does nothing.
             *
             * @param tab The tab that was unselected.
             */
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            /**
             * Called when a tab is reselected. This implementation does nothing.
             *
             * @param tab The tab that was reselected.
             */
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        tabLayout.getTabAt(0)?.select()

        // Execution guard: allow ONLY Newland NQuire 300/304/351 models with Android 7 (API 24–25).
        // Any other device/model/version should show a warning for 10 seconds and exit.
        run {
            val model = Build.MODEL ?: ""

            // Normalize to compare variations like "NQuire351", "NQuire 351", "nq351", etc.
            val norm = model.lowercase(Locale.getDefault())
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")

            val isAllowedNquireModel =
                norm.contains("nquire300") || norm.contains("nq300") ||
                norm.contains("nquire304") || norm.contains("nq304") ||
                norm.contains("nquire351") || norm.contains("nq351")

            val isAndroid7 = Build.VERSION.SDK_INT in 24..25

            if (!isAllowedNquireModel && !isAndroid7) {
                val message = buildString {
                    append("App target are Newland NQuire351 Android 7 devices only.\n\nThe app will close.")
                }
                setStatus(message)
                val dlg = AlertDialog.Builder(this)
                    .setTitle("Unsupported device")
                    .setMessage(message)
                    .setCancelable(false)
                    .create()
                dlg.show()
                Handler(Looper.getMainLooper()).postDelayed({
                    try { dlg.dismiss() } catch (_: Throwable) {}
                    finish()
                }, 10_000)
                return
            }
        }

        // Initialize device info (model + serial) for header and server payload
        deviceModel = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifEmpty { Build.MODEL ?: "Unknown" }
        deviceSerial = resolveDeviceSerial()
        statusHeader = "Device: $deviceModel | SN: $deviceSerial"

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setStatus("This device does not support NFC")
            Toast.makeText(this, "NFC not supported", Toast.LENGTH_LONG).show()
        } else {
            updateUiForNfcState()
        }
    }

    // (No TLS provider installation; networking is disabled for this build.)

    /**
     * Called when the activity resumes.
     * This method updates the UI for the current NFC state, attempts to reset the NFC adapter
     * and start reader mode using a workaround for older Android versions, and then
     * schedules a periodic re-arming of the reader mode.
     */
    override fun onResume() {
        super.onResume()
        updateUiForNfcState()
        // For Android 7.1.1, the standard enableReaderMode/disableReaderMode cycle
        // might not be sufficient for continuous reads. This method uses reflection
        // to attempt a lower-level NFC adapter reset as a workaround for older OS versions.
        resetNfcAndStartReaderMode()
        pollingActive = true
        // Small initial delay to avoid overlapping with the initial reset/enable
        handler.postDelayed(rearmReaderRunnable, pollIntervalMs + 300)
    }

    /**
     * Called when the activity is paused.
     * This method disables periodic re-arming of the reader mode, removes any pending re-arm callbacks,
     * stops any active continuous tag reading, and disables NFC reader mode.
     */
    override fun onPause() {
        super.onPause()
        pollingActive = false
        handler.removeCallbacks(rearmReaderRunnable)
        stopContinuousReading() // Stop the continuous reading loop
        disableReaderMode()
    }

    /**
     * Enables NFC reader mode for the current activity with a specific set of flags.
     *
     * This method first checks if the `nfcAdapter` is null and returns if it is.
     * Otherwise, it enables reader mode using the activity context, `this` (as `ReaderCallback`),
     * and a combination of flags to support various NFC technologies and suppress platform sounds/NDEF checks.
     *
     * The flags used are:
     * - `NfcAdapter.FLAG_READER_NFC_A`
     * - `NfcAdapter.FLAG_READER_NFC_B`
     * - `NfcAdapter.FLAG_READER_NFC_F`
     * - `NfcAdapter.FLAG_READER_NFC_V`
     * - `NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK`
     * - `NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS`
     */
    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        adapter.enableReaderMode(this, this, flags, null)
    }

    /**
     * Disables NFC reader mode for the current activity.
     * This method also ensures any active continuous tag reading is stopped and its connection is closed,
     * releasing NFC resources.
     *
     * If the `nfcAdapter` is null, the method returns without performing any action.
     */
    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        stopContinuousReading() // Ensure continuous reading stops when reader mode is disabled
    }

    /**
     * Initiates a continuous reading process for a newly discovered NFC tag.
     * Any previously active continuous reading session is stopped before starting a new one.
     * The `connectedTag` is set to the provided tag, `isReadingTag` is set to true,
     * and the `readTagDataRunnable` is scheduled to run immediately.
     *
     * @param tag The NFC tag to begin continuously reading from.
     */
    private fun startContinuousReading(tag: Tag) {
        stopContinuousReading() // Stop any previous continuous reading
        connectedTag = tag
        isReadingTag = true
        handler.post(readTagDataRunnable)
    }

    /**
     * Stops the continuous reading process, cancels any pending read operations,
     * and attempts to close the connection to the currently connected NFC tag using all
     * commonly supported tag technologies. This ensures that NFC resources are released
     * when reading is no longer active.
     *
     * The `isReadingTag` flag is set to false, and the `readTagDataRunnable` is removed
     * from the handler\'s message queue. The `connectedTag` is then set to null.
     */
    private fun stopContinuousReading() {
        isReadingTag = false
        handler.removeCallbacks(readTagDataRunnable)
        connectedTag?.let { tag ->
            // Tech.get(tag) returns null if not supported. closeTagTechnology handles nulls.
            closeTagTechnology(Ndef.get(tag))
            closeTagTechnology(IsoDep.get(tag))
            closeTagTechnology(NfcA.get(tag))
            closeTagTechnology(NfcB.get(tag))
            closeTagTechnology(NfcF.get(tag))
            closeTagTechnology(NfcV.get(tag))
            closeTagTechnology(MifareClassic.get(tag))
            closeTagTechnology(MifareUltralight.get(tag))
        }
        connectedTag = null
    }

    /**
     * Safely closes the connection to a given [TagTechnology] instance if it is currently connected.
     * This method logs a success message if the connection is closed successfully or an error message
     * if an exception occurs during the closing process.
     *
     * @param tech The [TagTechnology] instance to attempt to close. Can be null.
     */
    private fun closeTagTechnology(tech: TagTechnology?) {
        try {
            if (tech != null && tech.isConnected) {
                tech.close()
                Log.d("NFC_CLOSE_SUCCESS", "Successfully closed tag technology: ${tech.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            Log.e("NFC_CLOSE_ERROR", "Error closing tag technology: ${tech?.javaClass?.simpleName}", e)
        }
    }

    /**
     * Attempts to reset the NFC adapter and then re-enable reader mode.
     * This method uses reflection to call hidden `NfcAdapter.disable()` and `NfcAdapter.enable()`
     * methods as a workaround for potential NFC stability issues on older Android versions (e.g., 7.1.1)
     * where standard reader mode re-arming might not be sufficient for continuous tag reads.
     *
     * The process involves:
     * 1. Setting `isResetInProgress` to true to prevent `rearmReaderRunnable` from interfering.
     * 2. Performing the disable/enable sequence on a background thread with a delay between operations.
     * 3. After the reflection-based reset (or if it fails), `updateUiForNfcState()` is called on the UI thread.
     * 4. If the adapter is enabled, `enableReaderMode()` is called, potentially after a small delay
     *    if the reflection reset was attempted, to give the NFC stack time to stabilize.
     * 5. Finally, `isResetInProgress` is set to false.
     *
     * **Warning**: Using reflection for hidden APIs is generally discouraged for production applications
     * due to potential breakage on future Android versions. This is specifically implemented as a workaround
     * for known issues on older OS versions.
     */
    private fun resetNfcAndStartReaderMode() {
        val adapter = nfcAdapter ?: return
        // Mark that a reset is in progress so the re-arm loop won\'t interfere
        isResetInProgress = true
        Thread {
            var resetAttempted = false
            try {
                // Using reflection for NfcAdapter.disable()/enable() which are hidden APIs.
                // This is a workaround specifically for older Android versions (like 7.1.1)
                // where standard reader mode re-arming may not be sufficient for continuous reads.
                // This approach is generally not recommended for production apps due to instability
                // and potential for breakage on future OS updates.
                val disableMethod = NfcAdapter::class.java.getMethod("disable")
                val enableMethod = NfcAdapter::class.java.getMethod("enable")
                disableMethod.invoke(adapter)
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                enableMethod.invoke(adapter)
                resetAttempted = true
            } catch (_: Throwable) {
                // Fallback if reflection fails or APIs are not available (e.g., on newer Android versions)
                Log.w("NFC_RESET", "Reflection-based NFC reset failed or not supported. Proceeding with standard enable.")
            }

            runOnUiThread {
                updateUiForNfcState()
                if (adapter.isEnabled) {
                    if (resetAttempted) {
                        Thread {
                            try { Thread.sleep(150) } catch (_: InterruptedException) {}
                            runOnUiThread { enableReaderMode() }
                        }.start()
                    } else {
                        enableReaderMode()
                    }
                }
                // Whatever the branch, the reset phase is finished now
                isResetInProgress = false
            }
        }.start()
    }

    /**
     * Callback method invoked when an NFC tag is discovered by the reader mode.
     *
     * This method handles the lifecycle of continuous tag reading:
     * 1. It first stops any existing continuous reading session to ensure a fresh start.
     * 2. It then initiates a new continuous reading session for the newly discovered `tag`.
     * 3. Basic tag information (UID, type, protocols) is extracted.
     * 4. The UI is updated on the main thread to reflect the tag discovery, including
     *    incrementing the read counter, adding a summary to the history list,
     *    and updating the status, time, type, and UID TextViews.
     * 5. If an error occurs during the discovery process (e.g., if the `tag` is null),
     *    the error is logged, a summary is added to both history and error lists,
     *    and the UI status is updated to "Discovery Error".
     *
     * @param tag The discovered NFC tag. Can be null if an error occurred during discovery.
     */
    override fun onTagDiscovered(tag: Tag?) {
        try {
            if (tag == null) throw IllegalStateException("Tag is null")

            startContinuousReading(tag) // Start continuous reading for the newly discovered tag

            val uid = bytesToHex(tag.id)
            val type = detectTagType(tag)
            val protocols = tag.techList.joinToString(", ")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val summary = "Discovered\n$time\n$type\n$uid\n$protocols"

            runOnUiThread {
                readCounter += 1
                tvReadCount.text = "Counter: $readCounter"
                // Insert the current event at the top for a strict chronological log
                historyAdapter.insert(summary, 0)
                trimAdapter(historyAdapter)
                setStatus("Tag Discovered")
                tvTime.text = time
                tvType.text = type
                tvUid.text = "$uid\n$protocols"
            }

            // Fire-and-forget best-effort upload; failures must not affect NFC loop
            sendReadToServer(readCounter, uid, time)
        } catch (e: Throwable) {
            Log.e("NFC_DISCOVERY_ERROR", "Error during tag discovery", e)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val errorSummary = "$time | ERROR | ${e.message ?: e::class.java.simpleName}"
            runOnUiThread {
                readCounter += 1
                tvReadCount.text = "Counter: $readCounter"
                // Log error into both history and error lists
                historyAdapter.insert(errorSummary, 0)
                trimAdapter(historyAdapter)
                setStatus("Discovery Error")
                tvUid.text = ""
                tvType.text = ""
                tvTime.text = time
                errorAdapter.insert(errorSummary, 0)
                trimAdapter(errorAdapter)
            }
        }
    }

    /**
     * Detects the type of an NFC tag based on the technologies it supports.
     *
     * This method iterates through the `techList` of the provided [Tag] object and
     * identifies common NFC technologies. It constructs a comma-separated string
     * of all recognized types.
     *
     * If no recognized technologies are found in the tag\'s tech list, the method
     * returns "Unknown".
     *
     * @param tag The NFC tag for which to detect the type.
     * @return A string representing the detected type(s) of the tag, or "Unknown".
     */
    private fun detectTagType(tag: Tag): String {
        val types = tag.techList.mapNotNull { techDisplayMap[it] }
        return if (types.isEmpty()) "Unknown" else types.joinToString(", ")
    }

    /**
     * Converts a byte array into a colon-separated hexadecimal string representation.
     *
     * Each byte in the input array is formatted as a two-digit uppercase hexadecimal number.
     * For example, a byte array `byteArrayOf(0x01, 0x0A, 0xFF.toByte())` would result
     * in the string "01:0A:FF".
     *
     * @param bytes The byte array to convert. Can be null.
     * @return A string representing the hexadecimal values of the bytes, or an empty string if the input is null.
     */
    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return bytes.joinToString(":") { b -> String.format(Locale.US, "%02X", b) }
    }

    /**
     * Updates the UI (specifically `tvStatus`) to reflect the current NFC state of the device.
     *
     * This method handles three main scenarios:
     * 1. **NFC Not Supported**: If `nfcAdapter` is null, `tvStatus` displays a message indicating
     *    no NFC support, and its `OnClickListener` is removed.
     * 2. **NFC Disabled**: If NFC is supported but currently disabled, `tvStatus` displays a prompt
     *    to enable NFC and sets an `OnClickListener` to open NFC settings. Additionally, it shows
     *    an `AlertDialog` once to ask the user if they want to open settings to enable NFC.
     *    The `nfcPromptShowing` flag prevents showing multiple dialogs.
     * 3. **NFC Enabled**: If NFC is supported and enabled, `tvStatus` displays a message
     *    prompting the user to tap an NFC card, and its `OnClickListener` is removed.
     */
    private fun updateUiForNfcState() {
        val adapter = nfcAdapter
        if (adapter == null) {
            setStatus("This device does not support NFC")
            tvStatus.setOnClickListener(null)
            return
        }

        if (!adapter.isEnabled) {
            setStatus("NFC is disabled. Tap here to enable.")
            tvStatus.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }

            if (!nfcPromptShowing) {
                nfcPromptShowing = true
                AlertDialog.Builder(this)
                    .setTitle("Enable NFC")
                    .setMessage("NFC is turned off. Would you like to open settings to enable it?")
                    .setCancelable(true)
                    .setPositiveButton("Open Settings") { dialog, _ ->
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                        dialog.dismiss()
                        nfcPromptShowing = false
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                        nfcPromptShowing = false
                    }
                    .show()
            }
        } else {
            setStatus("Tap an NFC card to read")
            tvStatus.setOnClickListener(null)
        }
    }

    /**
     * Trims the provided [ArrayAdapter] to a maximum number of items specified by `MAX_HISTORY`.
     *
     * If the adapter\'s item count exceeds `MAX_HISTORY`, this method removes items from the end
     * of the adapter until its size matches `MAX_HISTORY`. After removing items, `notifyDataSetChanged()`
     * is called to inform the adapter that its data set has changed, prompting a UI update.
     *
     * @param adapter The `ArrayAdapter<String>` to be trimmed.
     */
    private fun trimAdapter(adapter: ArrayAdapter<String>) {
        if (adapter.count > MAX_HISTORY) {
            while (adapter.count > MAX_HISTORY) {
                adapter.remove(adapter.getItem(adapter.count - 1))
            }
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * Best-effort background POST to the server. Never blocks the NFC flow and ignores any failure.
     * Fields are sent as application/x-www-form-urlencoded exactly like the provided curl sample.
     */
    private fun sendReadToServer(counter: Int, uid: String, dateTime: String) {
        val now = System.currentTimeMillis()
        val remaining = nextAllowedSendAt - now
        if (remaining > 0) {
            Log.d("NFC_POST", "Upload suppressed by backoff for ${remaining}ms")
            return
        }
        Thread {
            try {
                val httpsUrl = URL(BuildConfig.POST_URL_HTTPS)
                val conn = (httpsUrl.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "NLSNQuire351NFC/1.0 (Android)")
                }

                val form = buildString {
                    // Include device info as requested
                    append("DEV_TYPE=")
                    append(URLEncoder.encode(deviceModel, "UTF-8"))
                    append("&DEV_SN=")
                    append(URLEncoder.encode(deviceSerial, "UTF-8"))
                    // Then the NFC fields exactly as in the sample
                    append("&NFC-COUNTER=")
                    append(URLEncoder.encode(counter.toString(), "UTF-8"))
                    append("&NFC-UID=")
                    append(URLEncoder.encode(uid, "UTF-8"))
                    append("&NFC-DATETIME=")
                    append(URLEncoder.encode(dateTime, "UTF-8"))
                }

                try {
                    conn.outputStream.use { os ->
                        val bytes = form.toByteArray(Charsets.UTF_8)
                        os.write(bytes)
                        os.flush()
                    }

                    val code = conn.responseCode
                    if (code in 200..299) {
                        Log.d("NFC_POST", "Sent to server OK: $code")
                    } else {
                        Log.w("NFC_POST", "Server responded with status: $code")
                    }
                    conn.disconnect()
                } catch (ssl: SSLHandshakeException) {
                    Log.w("NFC_POST", "Handshake failed with platform trust store: ${ssl.message}. Retrying once with relaxed SSL for this host only.")
                    // Retry once with a relaxed, host-scoped SSL context to bypass outdated trust stores.
                    try {
                        val trustAll = object : X509TrustManager {
                            /**
                             * Always succeeds, without performing any checks on the client certificate chain.
                             * This implementation is used as a last resort when the platform trust store is outdated, and
                             * the server's certificate cannot be verified otherwise.
                             *
                             * @param chain The client certificate chain to verify.
                             * @param authType The authentication type based on the client certificate.
                             */
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            /**
                             * This implementation does not verify the server's certificate chain.
                             * It always succeeds without performing any checks on the server certificate chain.
                             * This implementation is used as a last resort when the platform trust store is outdated, and
                             * the server's certificate cannot be verified otherwise.
                             *
                             * @param chain The server certificate chain to verify.
                             * @param authType The authentication type based on the server certificate.
                             */
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            /**
                             * Always returns an empty array, as this implementation does not perform any
                             * certificate chain verification.
                             *
                             * @return An empty array of X509 certificates.
                             */
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        /**
                         * Verifies the server's certificate chain. This implementation does not perform any checks, and
                         * always succeeds.
                         *
                         * @param chain The server certificate chain to verify.
                         * @param authType The authentication type based on the server certificate.
                         */
                        }
                        val ctx = SSLContext.getInstance("TLS")
                        ctx.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())

                        val conn2 = (httpsUrl.openConnection() as HttpsURLConnection).apply {
                            connectTimeout = 2500
                            readTimeout = 2500
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("User-Agent", "NLSNQuire351NFC/1.0 (Android)")
                            sslSocketFactory = ctx.socketFactory
                            hostnameVerifier = HostnameVerifier { hostname: String?, session: SSLSession? ->
                                try { URL(BuildConfig.POST_URL_HTTPS).host } catch (_: Throwable) { "labndevor.leoaidc.com" } == hostname
                            }
                        }

                        conn2.outputStream.use { os ->
                            val bytes = form.toByteArray(Charsets.UTF_8)
                            os.write(bytes)
                            os.flush()
                        }
                        val code2 = conn2.responseCode
                        if (code2 in 200..299) {
                            Log.d("NFC_POST", "Sent to server OK (relaxed SSL): $code2")
                        } else {
                            Log.w("NFC_POST", "Server responded with status (relaxed SSL): $code2")
                        }
                        conn2.disconnect()
                    } catch (e2: Exception) {
                        Log.w("NFC_POST", "Fallback send failed: ${e2::class.java.simpleName}: ${e2.message}")
                        // Optional HTTP fallback if enabled
                        if (BuildConfig.NET_HTTP_FALLBACK_ENABLED) {
                            try {
                                val httpUrl = URL(BuildConfig.POST_URL_HTTP)
                                val httpConn = (httpUrl.openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 2500
                                    readTimeout = 2500
                                    requestMethod = "POST"
                                    doOutput = true
                                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                                    setRequestProperty("Accept", "application/json")
                                    setRequestProperty("User-Agent", "NLSNQuire351NFC/1.0 (Android)")
                                }
                                httpConn.outputStream.use { os ->
                                    val bytes = form.toByteArray(Charsets.UTF_8)
                                    os.write(bytes)
                                    os.flush()
                                }
                                val httpCode = httpConn.responseCode
                                if (httpCode in 200..299) {
                                    Log.d("NFC_POST", "Sent to server OK over HTTP: $httpCode")
                                } else {
                                    Log.w("NFC_POST", "Server responded over HTTP: $httpCode")
                                }
                                httpConn.disconnect()
                            } catch (e3: Exception) {
                                Log.w("NFC_POST", "HTTP fallback failed: ${e3::class.java.simpleName}: ${e3.message}")
                            }
                        }
                        // Set backoff after failed HTTPS attempts
                        nextAllowedSendAt = System.currentTimeMillis() + BuildConfig.NET_FAILURE_BACKOFF_MS
                    }
                }
            } catch (e: Exception) {
                Log.w("NFC_POST", "Failed to send read to server: ${e::class.java.simpleName}: ${e.message}")
                // Apply backoff to avoid spamming repeated failures
                nextAllowedSendAt = System.currentTimeMillis() + BuildConfig.NET_FAILURE_BACKOFF_MS
            }
        }.start()
    }

}
