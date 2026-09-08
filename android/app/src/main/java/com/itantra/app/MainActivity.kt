package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itantra.app.databinding.ActivityMainBinding

/**
 * M1: real push-to-talk on one phone, one language (Hindi) -- speech in, recognized text
 * shown; typed text in, spoken aloud. No networking yet (that's M2): both directions run
 * fully on-device via [SherpaEngine], proving the STT/VAD/TTS pipeline works on real
 * hardware before two phones need to talk to each other.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SherpaEngine

    @Volatile
    private var engineReady = false

    private val lang = "hi" // only language bundled so far; en/others follow the same path

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
        setControlsEnabled(false)
        ensureMicPermission()

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
    }
}
