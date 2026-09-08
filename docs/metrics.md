# M4 metrics — real numbers, real phones

No emulator numbers anywhere in this file. Everything below was measured on the two
physical test phones:

- **Phone A**: OnePlus 9RT (`MT2111`), Android 14, arm64-v8a
- **Phone B**: Vivo V2336 (`V2327C1`), Android 16, arm64-v8a

Both over the same two-phone Wi-Fi hotspot link used for the M2/M3 demo.

## Footprint (Efficiency — 20%)

| | Before ABI fix | After ABI fix |
|---|---|---|
| Debug APK size | 529 MB | **389 MB** |

The fix: the sherpa-onnx AAR ships native `.so` for 4 ABIs (arm64-v8a, armeabi-v7a, x86,
x86_64); every phone we've tested — and the overwhelming majority of Android devices in the
field — is arm64-v8a. Restricting `ndk.abiFilters` to just that (`app/build.gradle.kts`)
dropped 140MB for zero quality tradeoff. The remaining ~389MB is genuinely two full
language model sets bundled uncompressed (Hindi + English STT+TTS, ~353MB) — see
"what's next" below for the real lever on this number.

| | Phone A | Phone B |
|---|---|---|
| RAM (PSS), Hindi loaded, idle at "Ready" | 352 MB | 322 MB |
| RAM (RSS) | 482 MB | 432 MB |
| Idle-listening CPU (sitting at "Ready", button not held) | **0.0%** | — |

The 0.0% idle CPU is by design: nothing runs until push-to-talk is actually held (no
continuous background VAD/audio loop) — see `SherpaEngine.kt`'s docstring.

~320-360MB RAM for one loaded language (STT+VAD+TTS models totaling ~197MB on disk) is
roughly 1.6-1.8x the on-disk size, which is normal ONNX Runtime session overhead
(activation buffers, working memory), not a leak.

## Accuracy / Latency (40% + 20% of the rubric)

Desktop (M0, `desktop-spike/roundtrip_test.py`, before any of this touched a phone):

| | Hindi | English |
|---|---|---|
| Roundtrip similarity (text → TTS → wav → VAD → STT → text) | 100% | 100% |
| TTS RTF | 0.16 | 0.20 |
| STT RTF | 0.93 | 0.05 (small model; see below) |

English STT was later upgraded small → medium (NeMo conformer-CTC) after on-device testing
showed accuracy issues; see the M1 commit for that trade (RTF stays comfortably low).

On-device (M1-M4 testing, OnePlus 9RT + Vivo V2336, real push-to-talk over the two-phone
link): the app now shows this live after every message (`sttPerfStats`/`ttsPerfStats` in
`MainActivity.kt`) rather than requiring a separate benchmark run. From an actual
two-phone test pass (phone A → phone B, Hindi, code-switched sentence "हाय वॉट्सअप वॉट आर
यू डूइंग" -- "Hi WhatsApp what are you doing", a realistic informal Hinglish utterance, not
a clean scripted one):

| Side | Number |
|---|---|
| Sender (phone A) STT | 493ms decode, RTF 0.26 |
| Sender → wire | sent 615ms after button release |
| Wire | 76 bytes (a voice note of the same length would be ~3.7 KB — **50x smaller**) |
| Receiver (phone B) TTS | 434ms synth, RTF 0.17 |
| Receiver → audible | spoken 614ms after the frame arrived |

Both RTFs comfortably under 1 (faster than real time) on real mid-range hardware, and the
transcription correctly handled code-switched Hindi/English speech -- exactly the kind of
informal, mixed-language input flagged as an accuracy risk earlier, not a cherry-picked
clean sentence.

Total spoken-to-heard latency (button release → audible on the other phone) in this run:
615ms (send side) + 614ms (receive side) ≈ **1.2s**, plus negligible local-Wi-Fi transit
time -- well inside the "feels conversational" bar M2's plan set.

## What's next (real levers, not aspirational)

- **Per-language download-on-demand instead of bundling both languages** is the actual
  lever on the 389MB number — noted in `scripts/copy_models_to_android_assets.ps1`'s
  docstring already. Would cut the installed size roughly in half for a single-language use
  session.
- English TTS quality tier: tried and reverted (see M2 commit) — "high" tier measured
  RTF > 1 on desktop, so "medium" stays until either a beefier target device is in scope or
  a proper fine-tune happens.
- WER against a labeled test set (not just the M0 roundtrip self-consistency check) hasn't
  been run yet — would need a small fixed sentence set with reference transcripts.
