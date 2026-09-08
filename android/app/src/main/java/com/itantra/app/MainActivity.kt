package com.itantra.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itantra.app.databinding.ActivityMainBinding

/**
 * M1+M2: push-to-talk on one phone (speech in -> recognized text; typed text in -> spoken
 * aloud, both via [SherpaEngine]), plus a two-phone link (M2's [Transport]) so what one
 * phone recognizes gets sent to and spoken by the other -- the actual walkie-talkie. Hindi
 * and English are both bundled; the toggle switches which one [SherpaEngine] has loaded
 * (only one language's models are held in RAM at a time).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SherpaEngine
    private lateinit var transport: Transport

    @Volatile
    private var engineReady = false

    private var lang = "hi"

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
            onStateChanged = { state -> runOnUiThread { renderConnectionState(state) } },
            onFrameReceived = { frame -> runOnUiThread { handleReceivedFrame(frame) } },
        )
        renderConnectionState(ConnectionState.DISCONNECTED)

        setControlsEnabled(false)
        ensureMicPermission()

        binding.langToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newLang = if (checkedId == binding.langEnglishButton.id) "en" else "hi"
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

        binding.pttButton.setOnTouchListener { _, event ->
            if (!engineReady) return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.status.text = getString(R.string.status_listening)
                    binding.recognizedText.text = getString(R.string.placeholder_stt_pending)
                    engine.startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.status.text = getString(R.string.status_transcribing)
                    Thread {
                        val text = engine.stopListeningAndTranscribe()
                        runOnUiThread {
                            binding.recognizedText.text =
                                text.ifBlank { getString(R.string.placeholder_stt_idle) }
                            binding.status.text = getString(R.string.status_ready)
                        }
                        if (text.isNotBlank()) sendToPeer(text)
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
            Thread {
                engine.speak(text)
                runOnUiThread { binding.status.text = getString(R.string.status_ready) }
            }.start()
        }
    }

    /** Sends [text] (in the currently active [lang]) to the connected peer, if any. */
    private fun sendToPeer(text: String) {
        val frame = Frame(lang = lang, text = text)
        val sent = transport.send(frame)
        runOnUiThread {
            val msgRes = if (sent) R.string.toast_sent else R.string.toast_send_failed
            val msg = if (sent) getString(msgRes, frame.byteSize) else getString(msgRes)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** A frame arrived from the peer: show it, and speak it -- this is the point of M2. */
    private fun handleReceivedFrame(frame: Frame) {
        binding.receivedText.text = frame.text
        if (engineReady) {
            binding.status.text = getString(R.string.status_speaking)
            Thread {
                engine.speak(frame.text)
                runOnUiThread { binding.status.text = getString(R.string.status_ready) }
            }.start()
        }
    }

    private fun renderConnectionState(state: ConnectionState) {
        binding.connectionStatus.text = when (state) {
            ConnectionState.DISCONNECTED -> getString(R.string.conn_not_connected)
            ConnectionState.LISTENING -> getString(R.string.conn_listening, localIpAddress() ?: "?")
            ConnectionState.CONNECTING -> getString(R.string.conn_connecting)
            ConnectionState.CONNECTED -> getString(R.string.conn_connected, localIpAddress() ?: "peer")
        }
    }

    private fun localIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ipInt == 0) return null
        return Formatter.formatIpAddress(ipInt)
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
        binding.status.text = getString(R.string.status_loading_models)
        Thread {
            engine.init(lang)
            engineReady = true
            runOnUiThread {
                binding.status.text = getString(R.string.status_ready)
                setControlsEnabled(true)
            }
        }.start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.pttButton.isEnabled = enabled
        binding.speakButton.isEnabled = enabled
        binding.langHindiButton.isEnabled = enabled
        binding.langEnglishButton.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        transport.stop()
        engine.release()
    }
}
