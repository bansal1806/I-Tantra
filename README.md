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

## Status

**M0 (desktop spike) is done.** `desktop-spike/roundtrip_test.py` runs the whole loop —
text → TTS → wav → VAD → STT → text — for Hindi and English, fully offline, and both come back
100% correct.

**M1 (Android, single phone) is code-complete for Hindi, not yet verified on hardware.**
The app has a real push-to-talk loop (SherpaEngine.kt wires up sherpa-onnx's VAD + STT + TTS,
no more stubs) and `gradlew assembleDebug` builds a working APK, but no Android device has been
connected in this environment yet to confirm it actually runs correctly end-to-end on a phone.
English is the immediate next step once Hindi is confirmed working on real hardware.

### Try it on your phone

1. Enable Developer Options + USB debugging on the phone, connect it via USB.
2. From `android/`: `gradlew.bat installDebug` (or open the project in Android Studio and hit Run).
3. Grant the mic permission when prompted, wait for "Ready", hold the button and speak Hindi.

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
- `android/` — the Kotlin Android app (M1+).
- `tools/metrics/` — live measurement dashboard: WER, RTF, bytes-on-wire, end-to-end latency (M4).
- `docs/` — architecture and rubric-mapping notes.

## Milestones

| # | Milestone | Done when |
|---|-----------|-----------|
| M0 | Desktop spike | WAV→text and text→WAV work offline in Python, Hindi + English |
| M1 | Android skeleton, single phone | Push-to-talk shows correct text; typed text is spoken aloud |
| M2 | Two-phone transport | Speak on phone A, hear it on phone B, offline, ~1-2s |
| M3 | Innovation layer | Live bitrate/compression display; alert messages override volume/silent mode |
| M4 | Metrics + hardening | Real WER/RTF/latency/footprint numbers from physical phones |
| M5 | Language expansion | Remaining 8 languages added; weak TTS voices revisited |

Full plan: `C:\Users\Admin\.claude\plans\drifting-swinging-fairy.md`.

## Stack

- **STT**: [AI4Bharat IndicConformer](https://github.com/AI4Bharat/IndicConformerASR) (MIT), CTC branch, ONNX
- **VAD**: Silero VAD (ONNX)
- **TTS**: Piper voices (VITS, ONNX) — `hi_IN`, `en_US` for MVP
- **Runtime**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) — unifies VAD+STT+TTS on desktop (Python) and Android (Kotlin) over ONNX Runtime
- **Transport**: TCP over Wi-Fi hotspot → Wi-Fi Direct (BLE / LoRa as stretch)
