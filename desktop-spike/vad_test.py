"""M0 spike: endpointing sanity check -- run Silero VAD over a wav file and print the
speech segments it finds (start/end timestamps), simulating what the Android app will use
to decide when an utterance is finished.

Usage:
    python vad_test.py samples/tts_en.wav
"""

import sys

import numpy as np
import sherpa_onnx

from common import VAD_MODEL, read_wave, require


def find_segments(wav_path: str):
    require(VAD_MODEL)

    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(VAD_MODEL)
    config.sample_rate = 16000

    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=30)

    samples, sample_rate = read_wave(wav_path)
    if sample_rate != 16000:
        # Silero VAD wants 16kHz; TTS output (Piper) is usually 22050Hz. Quick-and-dirty
        # resample is fine here -- this script is a spike, not the production audio path.
        n_out = int(len(samples) * 16000 / sample_rate)
        samples = np.interp(
            np.linspace(0, len(samples), n_out, endpoint=False),
            np.arange(len(samples)),
            samples,
        ).astype(np.float32)
        sample_rate = 16000

    window_size = config.silero_vad.window_size
    i = 0
    segments = []
    while i + window_size <= len(samples):
        vad.accept_waveform(samples[i : i + window_size])
        i += window_size
    vad.flush()

    while not vad.empty():
        seg = vad.front
        start_s = seg.start / sample_rate
        end_s = start_s + len(seg.samples) / sample_rate
        segments.append((start_s, end_s))
        vad.pop()

    print(f"{wav_path}: {len(segments)} speech segment(s)")
    for idx, (start_s, end_s) in enumerate(segments):
        print(f"  [{idx}] {start_s:.2f}s -> {end_s:.2f}s  ({end_s - start_s:.2f}s)")
    return segments


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    find_segments(sys.argv[1])
