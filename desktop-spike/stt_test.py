"""M0 spike: speech -> text, offline, via sherpa-onnx + IndicConformer/NeMo-CTC.

Usage:
    python stt_test.py samples/tts_en.wav en
    python stt_test.py samples/tts_hi.wav hi
"""

import sys
import time

import sherpa_onnx

from common import STT_MODELS, read_wave, require


def transcribe(wav_path: str, lang: str) -> str:
    if lang not in STT_MODELS:
        raise SystemExit(f"Unknown lang '{lang}'. Supported: {list(STT_MODELS)}")

    cfg = STT_MODELS[lang]
    require(cfg["model"], cfg["tokens"])

    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(cfg["model"]),
        tokens=str(cfg["tokens"]),
        num_threads=2,
        decoding_method="greedy_search",
        debug=False,
    )

    samples, sample_rate = read_wave(wav_path)
    duration = len(samples) / sample_rate

    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples)

    t0 = time.perf_counter()
    recognizer.decode_stream(stream)
    elapsed = time.perf_counter() - t0

    rtf = elapsed / duration if duration > 0 else float("inf")
    text = stream.result.text
    print(f"[{lang}] {wav_path} ({duration:.2f}s audio, {elapsed*1000:.0f}ms decode, RTF={rtf:.3f})")
    print(f"  -> {text!r}")
    return text


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    transcribe(sys.argv[1], sys.argv[2])
