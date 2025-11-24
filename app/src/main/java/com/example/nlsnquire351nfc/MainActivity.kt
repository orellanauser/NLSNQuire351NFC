package com.example.nlsnquire351nfc

import android.app.AlertDialog
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var lastReadingSummary: String? = null
    private var nfcPromptShowing: Boolean = false
    private var readCounter: Int = 0
    private val MAX_HISTORY = 200

    private var connectedTag: Tag? = null
    private var isReadingTag = false
    private val continuousReadIntervalMs = 500L // Interval for continuous reading

    // Re-arm reader mode periodically to allow consecutive reads of the same card
    private val handler = Handler(Looper.getMainLooper())
    private var pollingActive = false
    // Guard to avoid re-arm toggling while a reset is in progress
    private var isResetInProgress = false
    private val pollIntervalMs = 1000L
    private val rearmReaderRunnable = object : Runnable {
        /**
         * Periodically re-arms NFC reader mode if `pollingActive` is true and no continuous
         * tag reading is in progress. This helps ensure the device remains in a scanning state
         * for new tags, especially if the NFC adapter's state might be transiently unstable
         * or if `onTagDiscovered` is not reliably called for consecutive taps of the same tag
         * without a brief interruption.
         *
         * The runnable checks:
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

                        runOnUiThread {
                            readCounter += 1
                            tvReadCount.text = "Counter: $readCounter"
                            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            val summary = "Read Data\n$time\n${detectTagType(tag)}\n${bytesToHex(tag.id)}\n$readResult"
                            historyAdapter.insert(summary, 0)
                            trimAdapter(historyAdapter)
                            tvStatus.text = "Reading Data"
                            tvTime.text = time
                            tvType.text = detectTagType(tag)
                            tvUid.text = "${bytesToHex(tag.id)}\n${tag.techList.joinToString(", ")}"
                        }

                        // Close the connection after reading to allow for a fresh connect in the next interval.
                        // This can help with stability on older Android versions by preventing stale connections.
                        closeTagTechnology(currentTech)

                    } ?: run {
                        // No supported technology found to connect
                        throw IllegalStateException("No connectable technology found for tag.")
                    }
                } catch (e: Exception) {
                    Log.e("NFC_READ_ERROR", "Error during continuous read", e)
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    val errorSummary = "Cont. Read ERROR\n$time\n${e.message ?: e::class.java.simpleName}"
                    runOnUiThread {
                        readCounter += 1
                        tvReadCount.text = "Counter: $readCounter"
                        historyAdapter.insert(errorSummary, 0)
                        trimAdapter(historyAdapter)
                        tvStatus.text = "Cont. Read Error"
                        errorAdapter.insert(errorSummary, 0)
                        trimAdapter(errorAdapter)
                    }
                    // If there\'s an error during continuous read, stop the loop and re-enable reader mode
                    // to allow onTagDiscovered to be called again for a fresh start.
                    stopContinuousReading()
                    enableReaderMode() // Re-enable reader mode to await new tag discovery
                    return // Stop further processing for this run
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

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = "This device does not support NFC"
            Toast.makeText(this, "NFC not supported", Toast.LENGTH_LONG).show()
        } else {
            updateUiForNfcState()
        }
    }

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
        connectedTag = tag
        isReadingTag = true
        handler.removeCallbacks(readTagDataRunnable) // Remove any pending
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
        // Attempt to close the connection to the tag
        connectedTag?.let { tag ->
            // Iterate through common tag technologies to close the connection
            val techList = tag.techList
            if (techList.contains(Ndef::class.java.name)) closeTagTechnology(Ndef.get(tag))
            if (techList.contains(IsoDep::class.java.name)) closeTagTechnology(IsoDep.get(tag))
            if (techList.contains(NfcA::class.java.name)) closeTagTechnology(NfcA.get(tag))
            if (techList.contains(NfcB::class.java.name)) closeTagTechnology(NfcB.get(tag))
            if (techList.contains(NfcF::class.java.name)) closeTagTechnology(NfcF.get(tag))
            if (techList.contains(NfcV::class.java.name)) closeTagTechnology(NfcV.get(tag))
            if (techList.contains(MifareClassic::class.java.name)) closeTagTechnology(MifareClassic.get(tag))
            if (techList.contains(MifareUltralight::class.java.name)) closeTagTechnology(MifareUltralight.get(tag))
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

            stopContinuousReading() // Stop any previous continuous reading
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
                tvStatus.text = "Tag Discovered"
                tvTime.text = time
                tvType.text = type
                tvUid.text = "$uid\n$protocols"
            }
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
                tvStatus.text = "Discovery Error"
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
     * Recognized technologies include:
     * - `NfcA`
     * - `NfcB`
     * - `NfcF`
     * - `NfcV`
     * - `MifareClassic` ("MIFARE Classic")
     * - `MifareUltralight` ("MIFARE Ultralight")
     * - `IsoDep` ("ISO-DEP")
     * - `Ndef` ("NDEF")
     * - `NdefFormatable` ("NDEF Formatable")
     *
     * If no recognized technologies are found in the tag\'s tech list, the method
     * returns "Unknown".
     *
     * @param tag The NFC tag for which to detect the type.
     * @return A string representing the detected type(s) of the tag, or "Unknown".
     */
    private fun detectTagType(tag: Tag): String {
        val techs = tag.techList
        val types = mutableListOf<String>()
        if (techs.contains(NfcA::class.java.name)) types.add("NfcA")
        if (techs.contains(NfcB::class.java.name)) types.add("NfcB")
        if (techs.contains(NfcF::class.java.name)) types.add("NfcF")
        if (techs.contains(NfcV::class.java.name)) types.add("NfcV")
        if (techs.contains(MifareClassic::class.java.name)) types.add("MIFARE Classic")
        if (techs.contains(MifareUltralight::class.java.name)) types.add("MIFARE Ultralight")
        if (techs.contains(IsoDep::class.java.name)) types.add("ISO-DEP")
        if (techs.contains(Ndef::class.java.name)) types.add("NDEF")
        if (techs.contains(NdefFormatable::class.java.name)) types.add("NDEF Formatable")

        return if (types.isEmpty()) "Unknown" else types.joinToString(
            separator = ", ",
            prefix = "",
            postfix = ""
        )
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
            tvStatus.text = "This device does not support NFC"
            tvStatus.setOnClickListener(null)
            return
        }

        if (!adapter.isEnabled) {
            tvStatus.text = "NFC is disabled. Tap here to enable."
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
            tvStatus.text = "Tap an NFC card to read"
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
}
