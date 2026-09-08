# Rubric mapping

How build decisions map to the stated evaluation weights, so effort stays pointed at what's
actually scored.

## Accuracy — 40%

- STT: AI4Bharat IndicConformer, chosen specifically for low WER on Indian languages (MIT
  license, purpose-built for this). WER measured per-language in M4 against a small labeled test
  set, on real phones.
- TTS: naturalness is the flagged risk — Piper/VITS quality varies a lot by language. Strategy:
  be excellent in Hindi + English (MVP), decent-to-acceptable elsewhere, and never demo a
  language whose voice sounds bad. A VITS fine-tune on IndicVoices-R is the M5 lever if a
  language needs it.

## Latency — 20%

- Endpointing: Silero VAD finalizes an utterance as soon as speech stops, instead of a fixed
  timeout — keeps STT-completion delay low.
- RTF (real-time factor) for both STT and TTS measured directly in M4; target < 1 (faster than
  real time) on real hardware, not just desktop.
- End-to-end delta (word spoken → same word heard on the other phone) is the single number that
  matters most for the "walkie-talkie" feel — stopwatched live in M2/M4 on the two physical
  phones.

## Efficiency — 20%

- INT8-quantized per-language models (~120M param STT, small Piper voices), loaded one language
  at a time rather than all ten in RAM.
- Idle-listening CPU kept low by using a dedicated lightweight VAD (Silero) as the always-on
  component, not the full STT model.
- APK size, RAM footprint, and idle CPU are measured and reported as real numbers in M4.

## Functional completeness / demo / UX — remaining ~20%

- A reliable, live, two-phone push-to-talk demo is the single highest-leverage thing to get
  right — it's worth as much as any individual technical metric. M2's "done when" (speak on
  phone A, hear it on phone B, offline) is treated as the load-bearing milestone.
- M3's live bitrate/compression-ratio display and the alert override behavior are cheap-to-build,
  high-visibility features that make the core mechanic and the PS's stated alert requirement
  legible to judges in seconds, without needing a slide.
