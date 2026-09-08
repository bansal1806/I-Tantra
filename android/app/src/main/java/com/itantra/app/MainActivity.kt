package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itantra.app.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Push-to-talk (or, mode switched off, a continuous phone-mode call) on one phone -- speech
 * in, recognized text shown; typed text in, spoken aloud, both via [SherpaEngine] -- plus a
 * two-phone link (M2's [Transport]) so what one phone recognizes gets sent to and spoken by
 * the other. [languages] lists what's selectable; only one language's models are held in
 * RAM at a time (see SherpaEngine.init).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SherpaEngine
    private lateinit var transport: Transport

    @Volatile
    private var engineReady = false

    private var lang = "hi"
    private var pttMode = true

    @Volatile
    private var callActive = false

    /** (code, display label) for every language with a bundled TTS voice. Not all 10 the PS
     *  asks for -- see docs/metrics.md for which 5 currently have one and why. */
    private val languages: List<Pair<String, String>> by lazy {
        listOf(
            "hi" to getString(R.string.lang_hindi),
            "en" to getString(R.string.lang_english),
            "ml" to getString(R.string.lang_malayalam),
            "gu" to getString(R.string.lang_gujarati),
            "bn" to getString(R.string.lang_bengali),
        )
    }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadEngine()
            } else {
                binding.status.text = getString(R.string.status_mic_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = SherpaEngine(applicationContext)
        transport = Transport(
            context = applicationContext,
            onStateChanged = { state -> runOnUiThread { renderConnectionState(state) } },
            onFrameReceived = { frame -> runOnUiThread { handleReceivedFrame(frame) } },
        )
        renderConnectionState(ConnectionState.DISCONNECTED)

        setControlsEnabled(false)
        ensureMicPermission()

        binding.retryButton.setOnClickListener { loadEngine() }

        val langAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, languages.map { it.second })
        binding.langDropdown.setAdapter(langAdapter)
        binding.langDropdown.setText(languages.first { it.first == lang }.second, false)
        binding.langDropdown.setOnItemClickListener { _, _, position, _ ->
            val newLang = languages[position].first
            if (newLang != lang) {
                lang = newLang
                loadEngine()
            }
        }

        binding.hostButton.setOnClickListener {
            binding.joinRow.visibility = View.GONE
            Thread { transport.startHost() }.start()
        }
        binding.joinButton.setOnClickListener {
            binding.joinRow.visibility = View.VISIBLE
        }
        binding.connectButton.setOnClickListener {
            val ip = binding.hostIpInput.text?.toString()?.trim().orEmpty()
            if (ip.isBlank()) {
                Toast.makeText(this, R.string.toast_enter_host_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread { transport.connectToHost(ip) }.start()
        }

        binding.pttModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (callActive) endCall() // defensive; the switch is disabled during a call
            pttMode = checked
            binding.pttButton.text =
                getString(if (pttMode) R.string.btn_hold_to_talk else R.string.btn_start_call)
        }

        binding.pttButton.setOnTouchListener { _, event ->
            if (!engineReady) return@setOnTouchListener true
            if (!pttMode) {
                // Phone mode: the button is tap-to-toggle-the-call, not hold-to-talk.
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (callActive) endCall() else startCall()
                }
                return@setOnTouchListener true
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.status.text = getString(R.string.status_listening)
                    binding.recognizedText.text = getString(R.string.placeholder_stt_pending)
                    engine.startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.status.text = getString(R.string.status_transcribing)
                    val releaseTime = System.nanoTime()
                    Thread {
                        val result = engine.stopListeningAndTranscribe()
                        runOnUiThread {
                            binding.recognizedText.text =
                                result.text.ifBlank { getString(R.string.placeholder_stt_idle) }
                            binding.status.text = getString(R.string.status_ready)
                        }
                        if (result.text.isNotBlank()) sendToPeer(result, releaseTime)
                    }.start()
                    true
                }
                else -> false
            }
        }

        binding.speakButton.setOnClickListener {
            if (!engineReady) return@setOnClickListener
            val text = binding.speakInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.status.text = getString(R.string.status_speaking)
            val clickTime = System.nanoTime()
            Thread {
                val result = engine.speak(text)
                val speakLatencyMs = (System.nanoTime() - clickTime) / 1_000_000
                runOnUiThread {
                    binding.status.text = getString(R.string.status_ready)
                    val ttsRtf = if (result.audioDurationSeconds > 0) {
                        result.synthMs / (result.audioDurationSeconds * 1000)
                    } else 0f
                    binding.ttsPerfStats.text =
                        getString(R.string.perf_tts, result.synthMs, ttsRtf, speakLatencyMs)
                    binding.ttsPerfStats.visibility = View.VISIBLE
                }
            }.start()
        }
    }

    /** Sends [result] (in the currently active [lang]) to the connected peer, if any --
     *  tagged as an alert if that toggle is on. [releaseTime] (System.nanoTime() at
     *  button-up) anchors the M4 "how long after you stopped talking did this actually go
     *  out" latency number. */
    private fun sendToPeer(result: SttResult, releaseTime: Long) {
        val priority = if (binding.alertToggle.isChecked) Frame.PRIORITY_ALERT else Frame.PRIORITY_NORMAL
        val frame = Frame(lang = lang, priority = priority, text = result.text)
        val sent = transport.send(frame)
        val sendLatencyMs = (System.nanoTime() - releaseTime) / 1_000_000
        runOnUiThread {
            if (sent) {
                val cmp = frame.bitrateComparison(result.durationSeconds)
                binding.bitrateStats.text = getString(
                    R.string.bitrate_stats,
                    cmp.frameBytes,
                    formatBytes(cmp.equivalentVoiceNoteBytes),
                    cmp.compressionRatio,
                )
                binding.bitrateStats.visibility = View.VISIBLE

                val sttRtf = if (result.durationSeconds > 0) {
                    result.decodeMs / (result.durationSeconds * 1000)
                } else 0f
                binding.sttPerfStats.text =
                    getString(R.string.perf_stt, result.decodeMs, sttRtf, sendLatencyMs)
                binding.sttPerfStats.visibility = View.VISIBLE
            } else {
                Toast.makeText(this, R.string.toast_send_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * "Phone mode" -- push-to-talk switched off. Starts continuous, hands-free listening
     * (SherpaEngine.startPhoneMode): each sentence VAD detects is transcribed and sent as
     * soon as it's ready, no button hold required, same as the rest of a call.
     */
    private fun startCall() {
        callActive = true
        binding.pttButton.text = getString(R.string.btn_end_call)
        binding.status.text = getString(R.string.status_call_listening)
        binding.langDropdown.isEnabled = false
        binding.pttModeSwitch.isEnabled = false
        Thread {
            engine.startPhoneMode { result ->
                runOnUiThread { binding.recognizedText.text = result.text }
                sendToPeer(result, System.nanoTime())
            }
        }.start()
    }

    private fun endCall() {
        callActive = false
        Thread { engine.stopPhoneMode() }.start()
        binding.pttButton.text = getString(R.string.btn_start_call)
        binding.status.text = getString(R.string.status_ready)
        binding.langDropdown.isEnabled = true
        binding.pttModeSwitch.isEnabled = true
    }

    private fun formatBytes(bytes: Int): String =
        if (bytes >= 1024) "%.1f KB".format(bytes / 1024f) else "$bytes B"

    /** A frame arrived from the peer: show it, and speak it -- this is the point of M2.
     *  An alert-tagged frame speaks through [SherpaEngine.speak]'s alert path (max volume,
     *  bypasses silent/DND) and gets a visible marker here too. Also the other half of M4's
     *  latency picture: how long after the frame arrived until it's actually audible.
     *
     *  Mute control frames (phone mode's echo-avoidance handshake, see Frame.kt) are handled
     *  and returned on immediately -- they're plumbing, never shown or spoken. */
    private fun handleReceivedFrame(frame: Frame) {
        when (frame.priority) {
            Frame.PRIORITY_MUTE_START -> { engine.setRemoteMuted(true); return }
            Frame.PRIORITY_MUTE_STOP -> { engine.setRemoteMuted(false); return }
        }

        val arrivalTime = System.nanoTime()
        val isAlert = frame.priority == Frame.PRIORITY_ALERT
        binding.receivedText.text =
            if (isAlert) getString(R.string.alert_received_prefix, frame.text) else frame.text
        if (engineReady) {
            binding.status.text = getString(R.string.status_speaking)
            Thread {
                // Tell the peer to pause its (phone mode) mic before we start playing --
                // otherwise, if the two phones are near each other, its mic hears our
                // speaker and transcribes/re-sends our own message back to us. Sent as its
                // own frame rather than folded into this one since the peer needs to know
                // *before* playback starts, not after this message is already speaking.
                transport.send(Frame(lang = lang, priority = Frame.PRIORITY_MUTE_START, text = ""))
                val result = engine.speak(frame.text, alert = isAlert, onPlaybackDone = {
                    transport.send(Frame(lang = lang, priority = Frame.PRIORITY_MUTE_STOP, text = ""))
                })
                val speakLatencyMs = (System.nanoTime() - arrivalTime) / 1_000_000
                runOnUiThread {
                    binding.status.text = getString(R.string.status_ready)
                    val ttsRtf = if (result.audioDurationSeconds > 0) {
                        result.synthMs / (result.audioDurationSeconds * 1000)
                    } else 0f
                    binding.ttsPerfStats.text =
                        getString(R.string.perf_tts, result.synthMs, ttsRtf, speakLatencyMs)
                    binding.ttsPerfStats.visibility = View.VISIBLE
                }
            }.start()
        }
    }

    @Volatile
    private var wasConnected = false

    private fun renderConnectionState(state: ConnectionState) {
        // A dropped connection used to look identical to "never connected" -- same "Not
        // connected" text -- which is confusing mid-demo (was that peer's app killed? did
        // Wi-Fi drop? did I just never connect?). Call it out once, specifically, and point
        // at the fix (both roles already support it: tap Host/Join again).
        if (state == ConnectionState.DISCONNECTED && wasConnected) {
            Toast.makeText(this, R.string.toast_peer_disconnected, Toast.LENGTH_LONG).show()
        }
        wasConnected = state == ConnectionState.CONNECTED

        binding.connectionStatus.text = when (state) {
            ConnectionState.DISCONNECTED -> getString(R.string.conn_not_connected)
            ConnectionState.LISTENING -> getString(R.string.conn_listening, localIpAddress() ?: "?")
            ConnectionState.CONNECTING -> getString(R.string.conn_connecting)
            ConnectionState.CONNECTED -> getString(R.string.conn_connected, transport.remoteAddress ?: "peer")
        }
    }

    /**
     * WifiManager.connectionInfo (the "obvious" API for this) only reflects Wi-Fi *client*
     * mode -- it returns nothing useful when this phone is itself the hotspot (the Host
     * role), which is exactly the case that needs this most: showing the IP the other phone
     * should type in. Scanning interfaces directly works for both roles.
     */
    private fun localIpAddress(): String? {
        return try {
            val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> Collections.list(iface.inetAddresses).map { iface.name to it } }
                .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }
            // Prefer a wlan*-named interface (Wi-Fi client or hotspot AP) over anything else
            // (e.g. rmnet* mobile data) -- that's the network the other phone can reach.
            val wifi = candidates.firstOrNull { (name, _) -> name.contains("wlan", ignoreCase = true) }
            (wifi ?: candidates.firstOrNull())?.second?.hostAddress
        } catch (ex: Exception) {
            null
        }
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            loadEngine()
        } else {
            binding.status.text = getString(R.string.status_mic_denied)
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadEngine() {
        engineReady = false
        setControlsEnabled(false)
        binding.retryButton.visibility = View.GONE
        binding.status.text = getString(R.string.status_loading_models)
        Thread {
            try {
                engine.init(lang)
                engineReady = true
                runOnUiThread {
                    binding.status.text = getString(R.string.status_ready)
                    setControlsEnabled(true)
                }
            } catch (ex: Exception) {
                // A native model-load failure (low storage, corrupt asset, OOM) used to mean
                // the app sat stuck with everything disabled and no explanation. Now it's a
                // visible error with a way to try again instead of a silent dead end.
                Log.e("MainActivity", "engine.init failed for lang=$lang", ex)
                runOnUiThread {
                    binding.status.text =
                        getString(R.string.status_engine_error, ex.message ?: ex.toString())
                    binding.retryButton.visibility = View.VISIBLE
                    binding.langDropdown.isEnabled = true
                    binding.pttModeSwitch.isEnabled = true
                }
            }
        }.start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.pttButton.isEnabled = enabled
        binding.speakButton.isEnabled = enabled
        binding.langDropdown.isEnabled = enabled
        binding.pttModeSwitch.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        transport.stop()
        engine.release()
    }
}
