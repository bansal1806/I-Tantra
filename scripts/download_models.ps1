<#
.SYNOPSIS
  Downloads the VAD/STT/TTS models for iTantra's desktop spike (M0), all 10 of the PS's
  required languages: English, Hindi, Malayalam, Gujarati, Bengali, Marathi, Kannada,
  Telugu, Tamil, Odia.

.DESCRIPTION
  Fetches:
    - Silero VAD (ONNX)
    - English STT: sherpa-onnx NeMo-CTC conformer-medium (int8), from csukuangfj/HF
    - Every other language's STT: AI4Bharat IndicConformer, CTC, int8, converted by
      OpenVoiceOS (MIT) -- same org/format covers all 22 Indic languages under
      OpenVoiceOS/ai4bharat-indicconformer-<code>-onnx. NOTE: an earlier community repo
      (trysem/indicconformer-120m-onnx) was tried first and rejected -- every language folder in
      that repo turned out to contain identical (mislabeled) Assamese vocab/weights. Do not use it.
    - English TTS: Piper vits-piper-en_US-amy-medium. Tried swapping to the "high" tier
      (lessac-high) for better pronunciation/naturalness -- it measured RTF 1.4 on a
      desktop CPU (slower than real time), and its int8-quantized build was worse still
      (RTF 4.1: VITS doesn't quantize well on CPU here). Reverted; "medium" is the ceiling
      for now without either a beefier target device or a proper fine-tune.
    - Hindi TTS: Piper vits-piper-hi_IN-priyamvada-medium (no "high" tier exists for Hindi
      at all; pratham/rohan are the other two voices in the same tier if priyamvada doesn't
      suit)
    - Malayalam TTS: Piper vits-piper-ml_IN-meera-medium (arjun-medium is the other voice)
    - Gujarati TTS: Mimic3 vits-mimic3-gu_IN-cmu-indic_low -- not Piper (Piper has no
      Gujarati voice at all), but the same OfflineTtsVitsModelConfig interface, verified
      working. Only "low" quality tier exists; audibly the weakest of the 5 languages.
    - Bengali TTS: Coqui vits-coqui-bn-custom_female -- also not Piper. Unlike every other
      voice here, ships no espeak-ng-data (Coqui tokenizes by character, not phonemes), so
      its dataDir is legitimately empty; see SherpaEngine.ttsModelFileFor.
    - Marathi/Kannada/Telugu/Tamil/Odia TTS: Meta MMS-TTS, ONNX-converted by the community
      (willwade/mms-tts-multilingual-models-onnx on Hugging Face) -- sherpa-onnx's own
      tts-models release has no Piper/Mimic3/Coqui voice for any of these 5 (checked
      exhaustively). Same character-tokenized shape as Bengali (no espeak-ng-data). IMPORTANT:
      MMS-TTS is CC-BY-NC 4.0 (non-commercial) -- unlike every other model in this project
      (Piper/Coqui/Mimic3/AI4Bharat are all MIT/Apache-style permissive). Fine for this
      hackathon submission; flag before any commercial use. See docs/metrics.md.

  Re-run any time -- already-downloaded files are skipped.

.NOTES
  Models land in models/{stt,tts,vad}/... which is gitignored; this script is what's committed.
#>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $root "models"

function Get-IfMissing($Url, $OutFile) {
    if (Test-Path $OutFile) {
        Write-Host "  [skip] $OutFile already exists"
        return
    }
    Write-Host "  [get]  $Url"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
    Invoke-WebRequest -Uri $Url -OutFile $OutFile
}

function Expand-TarBz2AndFlatten($TarBz2Path, $DestDir) {
    # tar (bundled with Windows 10/11) handles .tar.bz2 directly. sherpa-onnx tts-models
    # archives contain one top-level folder; flatten it into $DestDir for simpler paths.
    New-Item -ItemType Directory -Force -Path $DestDir | Out-Null
    $tmp = Join-Path $DestDir "_extract_tmp"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    tar -xjf $TarBz2Path -C $tmp
    $inner = Get-ChildItem $tmp | Select-Object -First 1
    Get-ChildItem $inner.FullName | Move-Item -Destination $DestDir -Force
    Remove-Item -Recurse -Force $tmp
}

Write-Host "== VAD =="
Get-IfMissing `
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" `
    (Join-Path $modelsDir "vad\silero_vad.onnx")

Write-Host "== STT: English (sherpa-onnx NeMo-CTC conformer-medium, int8) =="
# "small" was the original pick (fast, tiny) but WER wasn't good enough -- medium is a
# straight drop-in upgrade (same architecture/pipeline, better-trained weights) for
# meaningfully better accuracy at ~68MB vs ~46MB. "large" (~170MB) exists as a further step
# up if medium still isn't accurate enough: replace conformer-medium with conformer-large.
Get-IfMissing `
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-medium/resolve/main/model.int8.onnx" `
    (Join-Path $modelsDir "stt\en\model.int8.onnx")
Get-IfMissing `
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-medium/resolve/main/tokens.txt" `
    (Join-Path $modelsDir "stt\en\tokens.txt")

foreach ($lang in @("hi", "ml", "gu", "bn", "mr", "kn", "te", "ta", "or")) {
    Write-Host "== STT: $lang (AI4Bharat IndicConformer CTC, int8, via OpenVoiceOS) =="
    Get-IfMissing `
        "https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-$lang-onnx/resolve/main/model.int8.onnx" `
        (Join-Path $modelsDir "stt\$lang\model.int8.onnx")
    Get-IfMissing `
        "https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-$lang-onnx/resolve/main/vocab.txt" `
        (Join-Path $modelsDir "stt\$lang\tokens.txt")
}

Write-Host "== TTS: English (Piper vits-piper-en_US-amy-medium) =="
$enTtsArchive = Join-Path $modelsDir "tts\_en_US-amy-medium.tar.bz2"
$enTtsDir = Join-Path $modelsDir "tts\en"
Get-IfMissing "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2" $enTtsArchive
if (-not (Test-Path (Join-Path $enTtsDir "en_US-amy-medium.onnx"))) {
    Expand-TarBz2AndFlatten $enTtsArchive $enTtsDir
}

Write-Host "== TTS: Hindi (Piper vits-piper-hi_IN-priyamvada-medium) =="
$hiTtsArchive = Join-Path $modelsDir "tts\_hi_IN-priyamvada-medium.tar.bz2"
$hiTtsDir = Join-Path $modelsDir "tts\hi"
Get-IfMissing "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-priyamvada-medium.tar.bz2" $hiTtsArchive
if (-not (Test-Path (Join-Path $hiTtsDir "hi_IN-priyamvada-medium.onnx"))) {
    Expand-TarBz2AndFlatten $hiTtsArchive $hiTtsDir
}

Write-Host "== TTS: Malayalam (Piper vits-piper-ml_IN-meera-medium) =="
$mlTtsArchive = Join-Path $modelsDir "tts\_ml_IN-meera-medium.tar.bz2"
$mlTtsDir = Join-Path $modelsDir "tts\ml"
Get-IfMissing "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ml_IN-meera-medium.tar.bz2" $mlTtsArchive
if (-not (Test-Path (Join-Path $mlTtsDir "ml_IN-meera-medium.onnx"))) {
    Expand-TarBz2AndFlatten $mlTtsArchive $mlTtsDir
}

Write-Host "== TTS: Gujarati (Mimic3 vits-mimic3-gu_IN-cmu-indic_low) =="
$guTtsArchive = Join-Path $modelsDir "tts\_gu_IN-cmu-indic_low.tar.bz2"
$guTtsDir = Join-Path $modelsDir "tts\gu"
Get-IfMissing "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-gu_IN-cmu-indic_low.tar.bz2" $guTtsArchive
if (-not (Test-Path (Join-Path $guTtsDir "gu_IN-cmu-indic_low.onnx"))) {
    Expand-TarBz2AndFlatten $guTtsArchive $guTtsDir
}

Write-Host "== TTS: Bengali (Coqui vits-coqui-bn-custom_female) =="
$bnTtsArchive = Join-Path $modelsDir "tts\_bn-custom_female.tar.bz2"
$bnTtsDir = Join-Path $modelsDir "tts\bn"
Get-IfMissing "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-bn-custom_female.tar.bz2" $bnTtsArchive
if (-not (Test-Path (Join-Path $bnTtsDir "model.onnx"))) {
    Expand-TarBz2AndFlatten $bnTtsArchive $bnTtsDir
}

Write-Host "== TTS: Marathi/Kannada/Telugu/Tamil/Odia (Meta MMS-TTS, ONNX via willwade/HF -- CC-BY-NC 4.0) =="
$mmsLangs = @{ mr = "mar"; kn = "kan"; te = "tel"; ta = "tam"; or = "ory" }
foreach ($lang in $mmsLangs.Keys) {
    $mmsCode = $mmsLangs[$lang]
    $ttsDir = Join-Path $modelsDir "tts\$lang"
    Get-IfMissing `
        "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/$mmsCode/model.onnx" `
        (Join-Path $ttsDir "model.onnx")
    Get-IfMissing `
        "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/$mmsCode/tokens.txt" `
        (Join-Path $ttsDir "tokens.txt")
}

Write-Host ""
Write-Host "Done. Now run: desktop-spike\.venv\Scripts\python.exe scripts\patch_stt_metadata.py"
Write-Host "(the Hindi/Indic STT models need a one-time ONNX metadata patch -- see that script's"
Write-Host "docstring for why)."
