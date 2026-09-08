"""Fixup for models/stt/<lang>/model.int8.onnx -- adds the ONNX metadata sherpa-onnx's
NeMo-CTC loader requires but AI4Bharat/OpenVoiceOS's IndicConformer ONNX exports don't include.

Why this exists: OpenVoiceOS publishes AI4Bharat IndicConformer as ONNX per language
(OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx, MIT) for the `onnx-asr` Python library --
correctly converted, but missing a few metadata_props keys (vocab_size, normalize_type,
subsampling_factor, model_type) that sherpa-onnx's C++ loader requires. Without them,
sherpa-onnx doesn't raise a catchable Python exception -- it hard-crashes with:
    'vocab_size' does not exist in the metadata

This adds exactly the metadata sherpa-onnx's own maintainer adds for other NeMo conformer-CTC
exports (see add-model-metadata.py in e.g. csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-small):
normalize_type=per_feature and subsampling_factor=4 are standard for the conformer-CTC
architecture (vs. subsampling_factor=8 for citrinet, which IndicConformer is not) -- confirmed
against the subsampling_factor=4 OpenVoiceOS itself declares in each repo's config.json.

Runs over every models/stt/<lang>/model.int8.onnx it finds (skips ones already patched, and
skips models/stt/en -- that one is csukuangfj's own sherpa-onnx export and is already correct).
Safe to re-run any time, e.g. after M5 adds more languages via download_models.ps1.

Usage:
    python scripts/patch_stt_metadata.py
"""

from pathlib import Path

import onnx

ROOT = Path(__file__).resolve().parent.parent
STT_DIR = ROOT / "models" / "stt"


def patch_one(model_path: Path, tokens_path: Path) -> None:
    model = onnx.load(str(model_path))
    existing_keys = {m.key for m in model.metadata_props}
    if "vocab_size" in existing_keys:
        print(f"  [skip] {model_path} already patched")
        return

    vocab_size = sum(1 for _ in tokens_path.open(encoding="utf-8"))
    meta_data = {
        "vocab_size": str(vocab_size),
        "normalize_type": "per_feature",
        "subsampling_factor": "4",
        "model_type": "EncDecCTCModelBPE",
        "version": "1",
        "model_author": "ai4bharat (via OpenVoiceOS onnx export)",
        "comment": f"https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-{model_path.parent.name}-onnx",
    }
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = value

    onnx.save(model, str(model_path))
    print(f"  [patched] {model_path}")


def main() -> None:
    if not STT_DIR.exists():
        raise SystemExit(f"{STT_DIR} not found -- run download_models.ps1 first.")

    found = False
    for lang_dir in sorted(STT_DIR.iterdir()):
        if not lang_dir.is_dir() or lang_dir.name == "en":
            continue  # English is csukuangfj's own sherpa-onnx export; already correct.
        model_path = lang_dir / "model.int8.onnx"
        tokens_path = lang_dir / "tokens.txt"
        if model_path.exists() and tokens_path.exists():
            found = True
            patch_one(model_path, tokens_path)

    if not found:
        print("No IndicConformer models found under models/stt/ (besides en/).")


if __name__ == "__main__":
    main()
