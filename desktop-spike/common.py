"""Shared paths/config for the M0 desktop spike scripts."""

import sys
from pathlib import Path

import numpy as np
import soundfile as sf

# Windows consoles often default stdout to cp1252, which can't print Devanagari (Hindi)
# text -- every script here imports common, so fix it in one place.
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"

# All five languages' STT models are AI4Bharat IndicConformer via the same
# OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx source and land as model.int8.onnx +
# tokens.txt every time -- see scripts/download_models.ps1.
STT_MODELS = {
    lang: {
        "model": MODELS / "stt" / lang / "model.int8.onnx",
        "tokens": MODELS / "stt" / lang / "tokens.txt",
    }
    for lang in ("en", "hi", "ml", "gu", "bn")
}

TTS_MODELS = {
    "en": {
        "model": MODELS / "tts" / "en" / "en_US-amy-medium.onnx",
        "tokens": MODELS / "tts" / "en" / "tokens.txt",
        "data_dir": MODELS / "tts" / "en" / "espeak-ng-data",
    },
    "hi": {
        "model": MODELS / "tts" / "hi" / "hi_IN-priyamvada-medium.onnx",
        "tokens": MODELS / "tts" / "hi" / "tokens.txt",
        "data_dir": MODELS / "tts" / "hi" / "espeak-ng-data",
    },
    "ml": {
        "model": MODELS / "tts" / "ml" / "ml_IN-meera-medium.onnx",
        "tokens": MODELS / "tts" / "ml" / "tokens.txt",
        "data_dir": MODELS / "tts" / "ml" / "espeak-ng-data",
    },
    "gu": {
        # Mimic3-trained (Piper's only Gujarati Coqui/Piper equivalent didn't exist); same
        # OfflineTtsVitsModelConfig interface, verified working, just a different origin.
        "model": MODELS / "tts" / "gu" / "gu_IN-cmu-indic_low.onnx",
        "tokens": MODELS / "tts" / "gu" / "tokens.txt",
        "data_dir": MODELS / "tts" / "gu" / "espeak-ng-data",
    },
    "bn": {
        # Coqui-trained, not Piper -- no espeak-ng-data (character-level tokens instead of
        # phonemized), so data_dir is empty. Confirmed working the same way regardless.
        "model": MODELS / "tts" / "bn" / "model.onnx",
        "tokens": MODELS / "tts" / "bn" / "tokens.txt",
        "data_dir": None,
    },
}

VAD_MODEL = MODELS / "vad" / "silero_vad.onnx"


def read_wave(path: str) -> tuple[np.ndarray, int]:
    """Read a wav file as (float32 mono samples in [-1, 1], sample_rate).

    sherpa-onnx's own `read_wave` helper isn't exposed in this package version, so this
    mirrors it with soundfile (already a dependency) instead.
    """
    samples, sample_rate = sf.read(path, dtype="float32", always_2d=False)
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    return samples.astype(np.float32), int(sample_rate)


def require(*paths: Path) -> None:
    missing = [p for p in paths if not p.exists()]
    if missing:
        joined = "\n  ".join(str(p) for p in missing)
        raise SystemExit(
            f"Missing model file(s):\n  {joined}\n\n"
            f"Run scripts/download_models.ps1 first."
        )
