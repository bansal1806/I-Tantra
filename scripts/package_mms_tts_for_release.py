"""
Packages each MMS-TTS language's model (models/tts/<lang>/) into a tar.gz archive, laid out
the same way sherpa-onnx's own tts-models GitHub releases nest entries (one top-level folder)
-- so ModelManager.kt's existing download+extract code works unchanged.

Unlike the other 5 languages' TTS voices (Piper/Mimic3/Coqui, all served straight from
sherpa-onnx's own tts-models release), Marathi/Kannada/Telugu/Tamil/Odia's voices come from
willwade/mms-tts-multilingual-models-onnx on Hugging Face -- a community ONNX conversion of
Meta's MMS-TTS, self-hosted here as a GitHub Release for the same reason the STT models are
(one stable, versioned, ModelManager-shaped URL instead of depending on a third party's
layout). IMPORTANT: MMS-TTS is CC-BY-NC 4.0 (non-commercial) -- the one non-permissively-
licensed model in this project; see docs/metrics.md and download_models.ps1.

gzip, not bzip2: same reasoning as scripts/package_stt_for_release.py -- these are self-hosted
so the format is ours to choose, and gzip decodes far faster than bzip2 on a phone CPU for
about the same size here. bzip2 was the real cause behind "downloading a language is slow",
not just a bad progress readout.

Output goes to scripts/release-assets/, meant to be uploaded as GitHub Release assets (the
mms-tts-v1 release). One-time (well, once per model update) packaging step.
"""
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models" / "tts"
OUT_DIR = ROOT / "scripts" / "release-assets"

LANGUAGES = ["mr", "kn", "te", "ta", "or"]


def package(lang: str) -> None:
    src_dir = MODELS_DIR / lang
    model_file = src_dir / "model.onnx"
    tokens_file = src_dir / "tokens.txt"
    if not model_file.exists() or not tokens_file.exists():
        raise FileNotFoundError(f"{lang}: expected {model_file} and {tokens_file}")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / f"mms-tts-{lang}.tar.gz"
    folder_name = f"mms-tts-{lang}"
    with tarfile.open(out_path, "w:gz", compresslevel=6) as tar:
        tar.add(model_file, arcname=f"{folder_name}/model.onnx")
        tar.add(tokens_file, arcname=f"{folder_name}/tokens.txt")

    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"{lang}: {out_path.name} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    for lang in LANGUAGES:
        package(lang)
