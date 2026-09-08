# iTantra

Offline, two-phone, push-to-talk voice communication over low-bandwidth links — built for the
SIH problem statement of the same shape. Instead of transmitting audio, one phone recognizes
speech to text on-device, sends the tiny text frame over Wi-Fi/BLE (LoRa as a stretch goal), and
the other phone synthesizes it back to speech on-device. Fully offline, open-source models only,
targeting low/mid-range Android hardware.

```text
speech → VAD/endpoint → on-device STT → text + priority tag → transport → on-device TTS → speech
```

## Why

A spoken sentence carries only a few bits per second of actual information. A voice codec
(Opus, AMR) still needs kbps of bandwidth; text needs bytes. iTantra treats STT+TTS as an
extreme semantic codec: throw away the waveform, keep the meaning, resynthesize locally. See
[docs/architecture.md](docs/architecture.md) for the full design and
[docs/rubric-mapping.md](docs/rubric-mapping.md) for how this maps to the evaluation criteria.

**Pitch deck**: [claude.ai/code/artifact/d5479658-...](https://claude.ai/code/artifact/d5479658-1b2c-4063-9041-e7602abfd424) — built
from this repo's real measured numbers, not projections.
**Demo script**: [docs/demo-script.md](docs/demo-script.md) — the live two-phone walkthrough,
rehearsal-ready.

## Status

**M0 through M4 are done and verified on real two-phone hardware** (a OnePlus 9RT and a Vivo
V2336, connected over one phone's mobile hotspot). Push-to-talk works both directions, Hindi
and English, fully offline: speak into one phone, the other speaks it back — with a live
bitrate-savings readout, an alert mode that overrides silent/DND at max volume, and live
RTF/latency numbers baked into the UI itself. See [docs/metrics.md](docs/metrics.md) for real
captured numbers and [docs/demo-script.md](docs/demo-script.md) for how to run the demo.

Next up: M5 (the remaining 8 languages) and the stretch goals (speaker-timbre, a real LoRa
link, cross-lingual translation, store-and-forward/FEC) — see the milestones table below.

### Try it on your phone(s)

1. Enable Developer Options + USB debugging on each phone, connect via USB.
2. From `android/`: `gradlew.bat installDebug` per connected phone (`adb -s <serial>` if both
   are plugged in at once), or open the project in Android Studio and hit Run.
3. Grant the mic permission when prompted, wait for "Ready".
4. For the two-phone loop: put both phones on the same Wi-Fi (a hotspot from one of them is
   most reliable — see [docs/demo-script.md](docs/demo-script.md) for the exact steps and why
   a shared router can silently fail here). One taps **Host**, the other **Join**s its IP.

### Quickstart (M0)

```powershell
scripts/download_models.ps1                                    # fetch VAD/STT/TTS models
desktop-spike\.venv\Scripts\python.exe scripts\patch_stt_metadata.py   # one-time fixup, see script docstring
cd desktop-spike
.venv\Scripts\python.exe roundtrip_test.py
```

Two things worth knowing if you touch the STT model setup:
- The community repo `trysem/indicconformer-120m-onnx` looked right but every language folder
  in it contains identical (mislabeled) Assamese data — don't use it. We use
  `OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx` instead (correct, MIT-licensed, one repo
  per language, covers all 22 Indic languages for M5 later).
- That OpenVoiceOS export targets the `onnx-asr` Python library, not sherpa-onnx, so it's
  missing a few ONNX metadata keys sherpa-onnx's loader hard-requires (and hard-crashes without
  — not a catchable exception). `scripts/patch_stt_metadata.py` adds them; see its docstring.

## Repo layout

- `desktop-spike/` — Python scripts that prove STT/VAD/TTS work standalone, before Android (M0).
- `scripts/` — model download/setup scripts.
- `models/` — fetched ONNX models (gitignored; run `scripts/download_models.ps1`).
- `android/` — the Kotlin Android app: `SherpaEngine.kt` (VAD+STT+TTS), `Transport.kt` +
  `Frame.kt` (the two-phone link and wire protocol), `MainActivity.kt` (UI + wiring).
- `docs/` — architecture, rubric mapping, real measured metrics, and the demo script.

## Milestones

| # | Milestone | Done when | Status |
|---|-----------|-----------|--------|
| M0 | Desktop spike | WAV→text and text→WAV work offline in Python, Hindi + English | ✅ Done |
| M1 | Android skeleton, single phone | Push-to-talk shows correct text; typed text is spoken aloud | ✅ Done |
| M2 | Two-phone transport | Speak on phone A, hear it on phone B, offline, ~1-2s | ✅ Done |
| M3 | Innovation layer | Live bitrate/compression display; alert messages override volume/silent mode | ✅ Done |
| M4 | Metrics + hardening | Real WER/RTF/latency/footprint numbers from physical phones | ✅ Done |
| M5 | Language expansion | Remaining 8 languages added; weak TTS voices revisited | ⏳ Next |
| — | Stretch (pick one) | Speaker-timbre, real LoRa link, cross-lingual, store-and-forward+FEC | Not started |

Full plan: `C:\Users\Admin\.claude\plans\drifting-swinging-fairy.md`.

## Stack

- **STT**: [AI4Bharat IndicConformer](https://github.com/AI4Bharat/IndicConformerASR) (MIT), CTC branch, ONNX
- **VAD**: Silero VAD (ONNX)
- **TTS**: Piper voices (VITS, ONNX) — `hi_IN`, `en_US` for MVP
- **Runtime**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) — unifies VAD+STT+TTS on desktop (Python) and Android (Kotlin) over ONNX Runtime
- **Transport**: TCP over Wi-Fi hotspot → Wi-Fi Direct (BLE / LoRa as stretch)
