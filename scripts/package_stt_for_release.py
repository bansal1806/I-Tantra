"""
Packages each non-Hindi language's already-patched STT model (models/stt/<lang>/) into a
tar.gz archive, laid out the same way sherpa-onnx's own tts-models GitHub releases nest
entries (one top-level folder) -- so ModelManager.kt's existing download+extract code (which
strips exactly one nesting level) works unchanged for STT too, no new archive-layout handling
needed.

gzip, not bzip2: these are self-hosted, so the packaging format is ours to pick, and gzip
decodes dramatically faster than bzip2 on a phone CPU for a similar compression ratio on
already-dense int8 ONNX weights -- bzip2 (the original choice, for consistency with
sherpa-onnx's own bz2-packaged releases) turned out to be the real cause of "downloading a
language is very slow" reports, not just something reported badly. See ModelManager.kt's
downloadAndExtract docstring for the other half of that fix (extraction now reports progress
too, instead of the bar freezing silently through the whole decompress step).

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
    out_path = OUT_DIR / f"stt-{lang}.tar.gz"
    folder_name = f"stt-{lang}"  # the one nesting level ModelManager.kt's flatten step strips
    with tarfile.open(out_path, "w:gz", compresslevel=6) as tar:
        tar.add(model_file, arcname=f"{folder_name}/model.int8.onnx")
        tar.add(tokens_file, arcname=f"{folder_name}/tokens.txt")

    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"{lang}: {out_path.name} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    for lang in LANGUAGES:
        package(lang)
