# Architecture

## Data flow

```
 Phone A (sender)                                          Phone B (receiver)
 ┌─────────────┐   ┌────────┐   ┌─────────┐   ┌─────────┐  transport   ┌─────────┐   ┌─────────┐   ┌───────────┐
 │ Mic (PTT)   │──▶│  VAD   │──▶│   STT   │──▶│  Frame  │──────────────▶│  Frame  │──▶│   TTS   │──▶│ Audio out │
 │ hold-to-talk│   │(Silero)│   │(Indic-  │   │ encoder │  Wi-Fi Direct  │ decoder │   │ (Piper/ │   │ (alert =  │
 └─────────────┘   └────────┘   │Conformer│   └─────────┘  / hotspot /  └─────────┘   │  VITS)  │   │ max vol,  │
                                 │  CTC)   │                BLE / LoRa                 └─────────┘   │ no-silent)│
                                 └─────────┘                                                          └───────────┘
```

Both phones run the identical app; roles are symmetric (whoever holds the push-to-talk button is
the sender for that utterance).

## Components

- **VAD/endpointer** — Silero VAD (ONNX), always running while the button is held, to detect
  when the utterance has ended so the sentence can be finalized and sent without waiting for a
  fixed timeout. Chosen for near-zero idle CPU (protects the Efficiency score).
- **STT** — AI4Bharat IndicConformer, CTC branch, per-language ONNX models (~120M params, INT8
  quantized). Hindi + English for MVP, 8 more languages added in M5 by swapping model files (not
  loading all languages into RAM at once).
- **Frame encoder/decoder** — a compact wire format:
  `[version(1B) | lang_code(1B) | priority/intent(1B) | text_len(varint) | utf8_text]`.
  Priority/intent carries `normal` vs `alert` (and room for an emotion tag later) in 1 extra
  byte — nearly free, and it's what drives the receiver's alert behavior.
- **Transport** — starts as a plain TCP socket over a shared Wi-Fi hotspot (fastest path to a
  reliably working demo), upgraded to Wi-Fi Direct so no shared router/hotspot is needed. BLE and
  a LoRa/ESP32 bridge are stretch goals once the core loop is solid.
- **TTS** — Piper (VITS) ONNX voices via sherpa-onnx's OfflineTts, `hi_IN` / `en_US` for MVP.
  Alert-tagged messages render with forced max volume, bypass silent/DND, and can't be dismissed
  mid-playback — the PS's explicit alert requirement.
- **Runtime** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) wraps ONNX Runtime and
  provides one consistent API for VAD+STT+TTS on both desktop (Python, for M0 prototyping) and
  Android (Kotlin, for the real app), instead of three separate native integrations.

## Why STT+TTS instead of a voice codec

A normal voice call is ~64–256 kbps. Opus gets to ~6–24 kbps. A spoken sentence like "Landslide
near the bridge, send help" is ~40 bytes of text — three to four orders of magnitude smaller than
even an aggressively compressed voice codec. iTantra transmits only the meaning (recognized text)
and regenerates the waveform locally, trading a fixed, small amount of per-message STT/TTS
compute for a massive bandwidth reduction. This is what makes it viable over LoRa, HF/VHF radio,
or a congested rural network where voice codecs simply don't get through reliably.

## Non-goals for MVP

- Not building a general-purpose chat app — push-to-talk, short utterances only.
- Not targeting all 10 languages before the core loop (Hindi+English) is solid end-to-end.
- Not depending on any cloud API or proprietary SDK at any point — everything on-device.
