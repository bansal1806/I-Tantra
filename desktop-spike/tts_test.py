"""M0 spike: text -> speech, offline, via sherpa-onnx + Piper (VITS).

Usage:
    python tts_test.py "Landslide near the bridge, send help" en [out.wav]
    python tts_test.py "पुल के पास भूस्खलन, मदद भेजो" hi [out.wav]
"""

import sys
import time

import sherpa_onnx

from common import TTS_MODELS, require


def synth(text: str, lang: str, out_path: str) -> None:
    if lang not in TTS_MODELS:
        raise SystemExit(f"Unknown lang '{lang}'. Supported: {list(TTS_MODELS)}")

    cfg = TTS_MODELS[lang]
    # data_dir is None for a voice with no espeak-ng-data (e.g. bn, which is Coqui-trained
    # and tokenizes by character rather than phonemizing) -- sherpa-onnx wants "" for that,
    # not the literal string "None".
    require(cfg["model"], cfg["tokens"], *([cfg["data_dir"]] if cfg["data_dir"] else []))

    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(cfg["model"]),
                lexicon="",
                tokens=str(cfg["tokens"]),
                data_dir=str(cfg["data_dir"]) if cfg["data_dir"] else "",
            ),
            num_threads=2,
        ),
    )
    tts = sherpa_onnx.OfflineTts(config)

    t0 = time.perf_counter()
    audio = tts.generate(text=text, sid=0, speed=1.0)
    elapsed = time.perf_counter() - t0

    sherpa_onnx.write_wave(out_path, samples=audio.samples, sample_rate=audio.sample_rate)

    duration = len(audio.samples) / audio.sample_rate
    rtf = elapsed / duration if duration > 0 else float("inf")
    print(f"[{lang}] '{text}'")
    print(f"  -> {out_path}  ({duration:.2f}s audio, {elapsed*1000:.0f}ms synth, RTF={rtf:.3f})")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    text_arg = sys.argv[1]
    lang_arg = sys.argv[2]
    out_arg = sys.argv[3] if len(sys.argv) > 3 else f"samples/tts_{lang_arg}.wav"
    synth(text_arg, lang_arg, out_arg)
