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
 * M1 skeleton: proves the app shell, permissions, and UI wiring work before any
 * STT/VAD/TTS model is plugged in. Push-to-talk and the "speak" button are stubbed --
 * they'll drive the real sherpa-onnx pipeline in the next pass of M1.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            binding.status.text = if (granted) {
                getString(R.string.status_ready)
            } else {
                getString(R.string.status_mic_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureMicPermission()

        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.status.text = getString(R.string.status_listening)
                    binding.recognizedText.text = getString(R.string.placeholder_stt_pending)
                    // TODO(M1 next pass): start VAD-gated recording -> sherpa-onnx STT stream
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.status.text = getString(R.string.status_ready)
                    // TODO(M1 next pass): finalize utterance, run STT, show result
                    true
                }
                else -> false
            }
        }

        binding.speakButton.setOnClickListener {
            val text = binding.speakInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show()
            } else {
                // TODO(M1 next pass): run sherpa-onnx TTS and play the result
                Toast.makeText(this, getString(R.string.toast_tts_stub, text), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        binding.status.text = if (granted) getString(R.string.status_ready) else getString(R.string.status_mic_denied)

        if (!granted) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
