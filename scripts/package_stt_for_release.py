"""
Packages each non-Hindi language's already-patched STT model (models/stt/<lang>/) into a
tar.bz2 archive, laid out the same way sherpa-onnx's own tts-models GitHub releases are
(files nested one level under a top-level folder) -- so ModelManager.kt's existing
download+extract code (written for those releases) works unchanged for STT too, no new
archive-format handling needed.

Output goes to scripts/release-assets/, meant to be uploaded as GitHub Release assets (see
the stt-models-v1 release). Not run as part of any build -- a one-time (well, once per model
update) packaging step, same spirit as patch_stt_metadata.py.
"""
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models" / "stt"
OUT_DIR = ROOT / "scripts" / "release-assets"

# Hindi stays bundled in the APK -- only these need a downloadable archive.
LANGUAGES = ["en", "ml", "gu", "bn", "mr", "kn", "te", "ta", "or"]


def package(lang: str) -> None:
    src_dir = MODELS_DIR / lang
    model_file = src_dir / "model.int8.onnx"
    tokens_file = src_dir / "tokens.txt"
    if not model_file.exists() or not tokens_file.exists():
        raise FileNotFoundError(f"{lang}: expected {model_file} and {tokens_file}")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / f"stt-{lang}.tar.bz2"
    folder_name = f"stt-{lang}"  # the one nesting level ModelManager.kt's flatten step strips
    with tarfile.open(out_path, "w:bz2") as tar:
        tar.add(model_file, arcname=f"{folder_name}/model.int8.onnx")
        tar.add(tokens_file, arcname=f"{folder_name}/tokens.txt")

    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"{lang}: {out_path.name} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    for lang in LANGUAGES:
        package(lang)
