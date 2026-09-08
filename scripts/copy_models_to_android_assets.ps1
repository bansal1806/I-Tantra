<#
.SYNOPSIS
  Copies the models the Android app needs from models/ into android/app/src/main/assets/.

.DESCRIPTION
  The app loads VAD/STT/TTS models straight out of its assets at runtime (see
  SherpaEngine.kt), but the model binaries themselves aren't committed to git (same
  reasoning as models/** -- see .gitignore) since they're large and fully reproducible.
  Run this after scripts/download_models.ps1 and scripts/patch_stt_metadata.py, and again
  any time you add a language to $Languages.

.PARAMETER Languages
  Which languages to bundle into the app. Defaults to just Hindi (what SherpaEngine.kt
  currently wires up for M1) -- add "en" here once English is wired in too.

.NOTES
  Every language added here makes the APK bigger (each is roughly 130-200MB of STT+TTS
  weights) -- bundle only what MainActivity actually offers. Per-language download-on-
  demand instead of bundling everything is a good M4/M5 hardening target.
#>

param(
    [string[]]$Languages = @("hi")
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $root "models"
$assetsDir = Join-Path $root "android\app\src\main\assets"

function Copy-Fresh($Source, $Dest) {
    if (Test-Path $Dest) {
        Remove-Item -Recurse -Force $Dest
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Dest) | Out-Null
    Copy-Item -Recurse -Force $Source $Dest
}

Write-Host "== VAD =="
Copy-Fresh (Join-Path $modelsDir "vad") (Join-Path $assetsDir "vad")

foreach ($lang in $Languages) {
    Write-Host "== STT: $lang =="
    $sttSrc = Join-Path $modelsDir "stt\$lang"
    if (-not (Test-Path $sttSrc)) {
        throw "Missing $sttSrc -- run download_models.ps1 (and patch_stt_metadata.py for non-English) first."
    }
    Copy-Fresh $sttSrc (Join-Path $assetsDir "stt\$lang")

    Write-Host "== TTS: $lang =="
    $ttsSrc = Join-Path $modelsDir "tts\$lang"
    if (-not (Test-Path $ttsSrc)) {
        throw "Missing $ttsSrc -- run download_models.ps1 first."
    }
    Copy-Fresh $ttsSrc (Join-Path $assetsDir "tts\$lang")
}

Write-Host ""
Write-Host "Done. Bundled languages: $($Languages -join ', ')"
$size = (Get-ChildItem $assetsDir -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Total assets size: {0:N0} MB" -f $size)
