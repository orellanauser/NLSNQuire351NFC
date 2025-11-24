### NLSNQuire351NFC — NFC stress read tester for NQuire351 Android 7.1.1

This app is a focused NFC stress‑reading tester targeting legacy NQuire351 Android 7.1.1 (API 25) devices. It continuously reads the same NFC tag/card and records a chronological history of reads and errors. The design intentionally uses the older Reader Mode APIs and includes workarounds known to help on some OEM stacks from that era.

Below are the common topics for this project, including detailed notes on the logic used and why it is implemented this way.

---

### 1) Project overview
- Purpose: stress/test NFC tag re‑reads on Android 7.1.1 where repeated reads of the same tag can stall under the stock stack.
- Core screen shows: Status, Counter, Time, Type, UID (+ supported techs), and two tabs (History, Errors).
- Main implementation: `app/src/main/java/com/example/nlsnquire351nfc/MainActivity.kt`.

### 2) Why special handling for Android 7.1.1
- On Android 7.x, rediscovery of a tag that remains continuously in the field can stop firing callbacks when using standard Reader Mode alone.
- Some OEM NFC stacks on that generation respond better when Reader Mode is periodically toggled (disable → enable) or when the adapter is reset.
- Newer Android versions (11+) improved behavior, but the goal here is 7.1.1 compatibility and stress conditions.

### 3) NFC reading strategy: what we do and why

3.1 Reader Mode flags
- We enable Reader Mode with a broad set of flags:
  - FLAG_READER_NFC_A | FLAG_READER_NFC_B | FLAG_READER_NFC_F | FLAG_READER_NFC_V: support major tech families.
  - FLAG_READER_SKIP_NDEF_CHECK: skip NDEF probing to reduce latency and overhead per detection.
  - FLAG_READER_NO_PLATFORM_SOUNDS: keep UI quiet during stress tests.
- Why: breadth + low latency are helpful for repeated rediscovery under stress.

3.2 Periodic re‑arming (toggle reader mode)
- A Handler + Runnable (`rearmReaderRunnable`) runs every `pollIntervalMs` (default 1000 ms).
- On each tick, if NFC is enabled and not in reset, we call `disableReaderMode()` then `enableReaderMode()`.
- Why: forces the stack to re‑discover a present tag, allowing repeated onTagDiscovered events even when the card never leaves the field. This counters stalls observed on some 7.1.1 builds.

3.3 Initial adapter reset with reflection (best‑effort)
- On `onResume()`, we call `resetNfcAndStartReaderMode()` which attempts `NfcAdapter.disable()` then `NfcAdapter.enable()` via reflection. We then enable Reader Mode.
- Why: some stacks benefit from a low‑level reset before starting. This is a best‑effort workaround; if the hidden APIs are not available or permitted, the app logs a warning ("Reflection-based NFC reset failed or not supported. Proceeding with standard enable.") and falls back to normal Reader Mode enabling.
- Safety: this is wrapped in try/catch and runs on a background thread; the UI enabling is posted back to main thread. If reflection fails, normal flow continues.

3.4 Guarding against interference between reset and re‑arm
- Field `isResetInProgress` prevents the periodic re‑arm from toggling while a reset/initial enable is running.
- We also delay the very first re‑arm posting (`pollIntervalMs + 300 ms`) after `onResume()` to avoid overlapping with the initial reset.
- Why: avoids rapid enable/disable collisions that could destabilize the stack, especially under stress conditions.

3.5 Lifecycle integration
- `onResume()`: update UI, perform best‑effort reset + enable Reader Mode, start periodic re‑arm loop.
- `onPause()`: stop re‑arm loop and disable Reader Mode to conserve power and avoid stray callbacks.

### 4) Logging model and UI decisions

4.1 Chronological history
- On every event (success or error), we insert the newest item at index 0 in the History list.
- Why: provides a clear, strict chronological log with latest at the top. Useful for long stress runs.

4.2 Error logging
- Errors are added both to History (for a full timeline) and to a separate Errors tab (focused view of issues only).
- The read counter increments for both successes and errors, consistently labeled as "Counter: X".

4.3 Memory control for long runs
- Both History and Errors adapters are trimmed to a maximum size (MAX_HISTORY = 200) by removing oldest items.
- Why: prevents unbounded memory growth during multi‑hour stress sessions while keeping recent context.

### 5) NFC state UX
- If NFC is unsupported: show a message and do nothing further.
- If NFC is disabled: status prompts the user; tapping opens system NFC settings. An optional dialog also invites enabling NFC.
- If NFC is enabled: status shows "Tap an NFC card to read".
- Why: simple, low‑friction UI tailored for lab/field testing.

### 6) Tuning knobs
- pollIntervalMs (default 1000 ms): increase to reduce churn, decrease to be more aggressive. For some OEMs, 500–1500 ms can make a difference.
- Periodic re‑arm: can be disabled (set pollingActive=false or stop posting the runnable) to test behavior with only the initial reset.
- Reflection reset: can be retained or commented out if an OEM build proves unstable; the app still works with standard Reader Mode only.

### 7) Safety, permissions, and compatibility
- Requires NFC permission and the NFC hardware feature (declared in AndroidManifest.xml).
- Uses hidden APIs via reflection in a guarded, best‑effort way. No hard dependency—falls back safely.
- Target/compile SDKs are modern for tooling; minSdk is 25 (Android 7.1.1), which is the focus of this tester.

### 8) Building and running
- Open the project in Android Studio (Giraffe+ recommended) or use Gradle.
- Build variants: standard debug/release; no special flavors needed.
- Install on a device running Android 7.1.1 with NFC hardware. Enable NFC in system settings.
- Launch the app, place a tag on the reader and keep it present. The counter should increment regularly; History will show the newest reads at the top.

### 9) Troubleshooting and tips (Android 7.1.1)
- If reads stall after a while:
  - Increase `pollIntervalMs` to 1500–2000 ms to reduce toggling churn.
  - Try disabling the reflection reset and rely only on periodic re‑arming.
  - Conversely, test disabling periodic re‑arm to isolate whether the reset alone is sufficient on your device.
- If the stack becomes noisy/unstable:
  - Ensure the `isResetInProgress` guard is in place (already implemented).
  - Avoid overlapping resets—keep the initial delay before the first re‑arm.
- If you see no callbacks:
  - Confirm NFC is enabled and the device genuinely supports the tag techs being used.
  - Check that Reader Mode flags include your tech (e.g., NfcV for ISO 15693).

### 10) Limitations
- Hidden API usage (disable/enable) is not guaranteed across OEMs and may require privileged permissions on some builds. The app degrades gracefully without it.
- This tool is not a production app pattern; it is optimized for stress/testing scenarios and lab diagnostics on legacy devices.

### 11) Code pointers
- Main logic: `MainActivity.kt`
  - `enableReaderMode()` / `disableReaderMode()`: toggling logic and flags.
  - `rearmReaderRunnable`: periodic re‑arm loop.
  - `resetNfcAndStartReaderMode()`: best‑effort adapter reset with `isResetInProgress` guard.
  - `onTagDiscovered(...)`: success/error logging and UI updates.
  - `trimAdapter(...)`: bounded history.
  - `readTagDataRunnable`: continuous reading logic and tag connection management.

### 12) Validation of common NFC observations (Android 7.x)

Below is a review of frequently cited pitfalls for NFC apps on Android 7.x and how this project addresses them.

1) Using intent-based foreground dispatch / ACTION_NDEF_DISCOVERED only — Prefer enableReaderMode on Android 7
- Valid: On Android 7, relying solely on intent dispatch (especially ACTION_NDEF_DISCOVERED) may not reliably rediscover the same tag while it stays in the field. Reader Mode is more reliable for repeated callbacks.
- What we do: We use NfcAdapter.enableReaderMode with broad flags and periodic re-arming. No foreground dispatch is used at runtime. An optional TAG_DISCOVERED intent filter is present in the manifest for legacy convenience only; Reader Mode takes precedence while active.

2) Not calling enableReaderMode in onResume / disableReaderMode in onPause
- Valid: Reader Mode should be bound to lifecycle to avoid stray callbacks and power drain.
- What we do: enableReaderMode is triggered from onResume (via resetNfcAndStartReaderMode), and disableReaderMode is called in onPause. We also suspend the re-arming loop in onPause.

3) Doing blocking transceive on the UI thread — must use a background thread
- Valid: Any tech.connect()/transceive() must be off the main thread to avoid ANRs and jank.
- What we do: The current tester does not perform transceive operations. All tag interactions, including connection and basic reads, are handled within the `readTagDataRunnable` which is posted to a handler. This helps prevent UI thread blocking.

4) Not handling TagLostException and reconnect logic
- Valid: Older stacks can drop connections unexpectedly. You should catch TagLostException, attempt a reconnect, and fail gracefully.
- What we do: In `readTagDataRunnable`, `TagLostException` (and other exceptions) are caught during continuous reading. Upon an error, the continuous reading loop is stopped, and NFC reader mode is re-enabled to allow for a new `onTagDiscovered` event, effectively re-initializing the tag interaction.

5) Not using FLAG_READER_SKIP_NDEF_CHECK when only raw tech access is needed
- Valid: Skipping the NDEF probe reduces overhead and may avoid interference on some stacks.
- What we do: We set FLAG_READER_SKIP_NDEF_CHECK in enableReaderMode.

6) Leaving IsoDep/NfcA connections open or leaking resources — always close() in finally
- Valid: Tech instances must be closed; leaks can destabilize the stack.
- What we do: In `readTagDataRunnable`, after each read operation, the connection to the tag technology (`currentTech`) is explicitly closed to ensure resources are released promptly. This helps with stability, especially on older Android versions.

7) Relying on cached NDEF results instead of reading raw APDUs — caching can hide updates
- Valid: For dynamic tags, cached NDEF can be stale; raw reads ensure fresh state.
- What we do: This tester primarily focuses on connecting and basic presence/length checks. If NDEF data is read, it's `ndef.cachedNdefMessage` which is typically sufficient for a stress test focusing on connectivity rather than dynamic content. For scenarios requiring fresh dynamic NDEF, one would need to implement explicit NDEF read commands.

8) Missing NFC permission / feature in AndroidManifest.xml
- Valid: You must declare both.
- What we do: AndroidManifest.xml includes `uses-permission android:name="android.permission.NFC"` and `uses-feature android:name="android.hardware.nfc" android:required="true"`.

9) Using foregroundDispatch and enableReaderMode at the same time — they conflict
- Valid: Foreground dispatch and Reader Mode are separate mechanisms; mixing them at runtime can conflict.
- What we do: We do not call `enableForegroundDispatch` at all. While Reader Mode is active, intent dispatch is bypassed by the framework. The manifest retains a `TAG_DISCOVERED` filter purely as a legacy convenience to launch the app on a scan when Reader Mode isn’t active; you may remove it if you want a strictly Reader-Mode-only configuration.

10) ACTION_NDEF_DISCOVERED specifics
- Note: This app does not declare `ACTION_NDEF_DISCOVERED` filters. For stress tests where NDEF content is irrelevant, Reader Mode with `SKIP_NDEF_CHECK` is preferred.

Summary: All relevant best practices are either already implemented (Reader Mode lifecycle, flags, no foreground dispatch, explicit connection closing in continuous reads) or not applicable to this tester’s scope (no complex I/O).

### License

MIT License

Copyright (c) 2025 Luis E. Orellana

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including, without limitation, the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OF OTHER DEALINGS IN THE
SOFTWARE.
