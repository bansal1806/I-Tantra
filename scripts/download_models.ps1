<#
.SYNOPSIS
  Downloads the MVP (Hindi + English) VAD/STT/TTS models for iTantra's desktop spike (M0).

.DESCRIPTION
  Fetches:
    - Silero VAD (ONNX)
    - English STT: sherpa-onnx NeMo-CTC conformer-small (int8), from csukuangfj/HF
    - Hindi STT: AI4Bharat IndicConformer, CTC, int8, converted by OpenVoiceOS (MIT) -- same
      org/format covers all 22 Indic languages under OpenVoiceOS/ai4bharat-indicconformer-<code>-onnx,
      useful again for M5 language expansion. NOTE: an earlier community repo
      (trysem/indicconformer-120m-onnx) was tried first and rejected -- every language folder in
      that repo turned out to contain identical (mislabeled) Assamese vocab/weights. Do not use it.
    - English TTS: Piper vits-piper-en_US-amy-medium
    - Hindi TTS: Piper vits-piper-hi_IN-priyamvada-medium

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

Write-Host "== STT: English (sherpa-onnx NeMo-CTC conformer-small, int8) =="
Get-IfMissing `
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-small/resolve/main/model.int8.onnx" `
    (Join-Path $modelsDir "stt\en\model.int8.onnx")
Get-IfMissing `
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-conformer-small/resolve/main/tokens.txt" `
    (Join-Path $modelsDir "stt\en\tokens.txt")

Write-Host "== STT: Hindi (AI4Bharat IndicConformer CTC, int8, via OpenVoiceOS) =="
Get-IfMissing `
    "https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-hi-onnx/resolve/main/model.int8.onnx" `
    (Join-Path $modelsDir "stt\hi\model.int8.onnx")
Get-IfMissing `
    "https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-hi-onnx/resolve/main/vocab.txt" `
    (Join-Path $modelsDir "stt\hi\tokens.txt")

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

Write-Host ""
Write-Host "Done. Now run: desktop-spike\.venv\Scripts\python.exe scripts\patch_stt_metadata.py"
Write-Host "(the Hindi/Indic STT models need a one-time ONNX metadata patch -- see that script's"
Write-Host "docstring for why)."
