# M4 metrics — real numbers, real phones

No emulator numbers anywhere in this file. Everything below was measured on the two
physical test phones:

- **Phone A**: OnePlus 9RT (`MT2111`), Android 14, arm64-v8a
- **Phone B**: Vivo V2336 (`V2327C1`), Android 16, arm64-v8a

Both over the same two-phone Wi-Fi hotspot link used for the M2/M3 demo.

## Footprint (Efficiency — 20%)

| | 2 languages, before ABI fix | 2 languages, after ABI fix | 5 languages, all bundled | 5 languages, TTS download-on-demand | 5 languages, STT+TTS download-on-demand |
|---|---|---|---|---|---|
| Debug APK size | 529 MB | 389 MB | 1.02 GB | 730 MB | **250 MB** |

The ABI fix: the sherpa-onnx AAR ships native `.so` for 4 ABIs (arm64-v8a, armeabi-v7a, x86,
x86_64); every phone we've tested — and the overwhelming majority of Android devices in the
field — is arm64-v8a. Restricting `ndk.abiFilters` to just that (`app/build.gradle.kts`)
dropped 140MB for zero quality tradeoff.

Going from 2 to 5 languages (M5) initially meant bundling every language's STT *and* TTS in
the APK — 1.02GB, genuinely too large for the PS's low/mid-range-phone target. Download-on-
demand (`ModelManager.kt`) fixed it in two steps: TTS voices first (730MB — sherpa-onnx's own
public model releases, no processing needed), then STT too (250MB — blocked until the
AI4Bharat IndicConformer ONNX metadata patch each one needs, `scripts/patch_stt_metadata.py`,
could be applied once and the already-patched models self-hosted as GitHub Release assets
instead of reproduced in-app; see `scripts/package_stt_for_release.py` and the
`stt-models-v1` release). Hindi's STT+TTS+VAD still ship in the APK — instant, zero-setup,
matching the flagship demo language; every other language fetches both models once, on
first selection, and caches them under app-external storage after that. 250MB is essentially
just Hindi's models (~212MB) plus the app/runtime itself (~38MB) — a single-language install
now costs about what it would if the app supported only that one language, regardless of how
many it actually offers. The remaining 5 languages (Marathi, Kannada, Telugu, Tamil, Odia,
added after this table's original measurement) use the identical downloaded path, so the
250MB base install size is unchanged by going from 5 to 10 languages.

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

All 10 languages, same roundtrip test, same "landslide near the bridge, send help" sentence
(translated per language — the 5 below are machine/best-effort translations, not
independently native-verified, unlike the 5 above which have had real usage; a low score
here could mean the translation, not the model):

| | ml | gu | bn | mr | kn | te | ta | or |
|---|---|---|---|---|---|---|---|---|
| Similarity | 98% | 100% | 100% | 94% | 100% | 98% | 92% | 91% |
| TTS RTF | 0.09 | 0.07 | 0.53 | 0.40 | 0.39 | 0.30 | 0.29 | 0.27 |
| STT RTF | 0.43 | 0.40 | 0.41 | 0.41 | 0.28 | 0.28 | 0.28 | 0.27 |

All 10 clear the 70% "OK" bar the test uses, all RTFs comfortably under 1 on a desktop CPU.
No repeat of the Gujarati "low"-tier mispronunciation caught earlier — the new 5 (Marathi/
Kannada/Telugu/Tamil/Odia, all Meta MMS-TTS) score in the same 91-100% band as the original
5, not visibly worse for being a different model family.

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

- **MMS-TTS license (Marathi/Kannada/Telugu/Tamil/Odia)**: CC-BY-NC 4.0, non-commercial only
  — the one model in this project that isn't MIT/Apache-style permissive (Piper, Coqui,
  Mimic3, and AI4Bharat's STT all are). Not a problem for this hackathon submission; would
  need revisiting (a licensed voice, or a from-scratch fine-tune) before any commercial use.
- **Cross-device language mismatch**: `handleReceivedFrame` speaks a received message in
  whatever language the *receiver* currently has loaded, not the sender's `frame.lang` — a
  message sent in a language the receiver hasn't switched to gets mispronounced (spoken with
  the wrong voice/phonemizer), not silently dropped. Not hit in the demo script (both phones
  stay on the same language), but a real gap if that changes.
- English TTS quality tier: tried and reverted (see M2 commit) — "high" tier measured
  RTF > 1 on desktop, so "medium" stays until either a beefier target device is in scope or
  a proper fine-tune happens.
- WER against a labeled test set (not just the M0 roundtrip self-consistency check) hasn't
  been run yet — would need a small fixed sentence set with reference transcripts.
