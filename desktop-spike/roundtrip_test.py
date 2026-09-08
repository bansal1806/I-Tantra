"""M0 'done when' check: text -> TTS -> wav -> VAD -> STT -> text, offline, no Android.

This is the whole iTantra loop compressed onto one machine: it proves speech can be
synthesized and then correctly recognized again, for both MVP languages, before any of it
touches an Android phone.

Usage:
    python roundtrip_test.py
"""

import os
import re
from difflib import SequenceMatcher

from stt_test import transcribe
from tts_test import synth
from vad_test import find_segments

CASES = [
    ("en", "Landslide near the bridge, send help"),
    ("hi", "पुल के पास भूस्खलन, मदद भेजो"),
]


def normalize(s: str) -> str:
    s = s.lower()
    s = re.sub(r"[^\w\s]", "", s, flags=re.UNICODE)  # strip punctuation
    s = re.sub(r"\s+", "", s, flags=re.UNICODE)  # CTC output word-splits sometimes
    #                                              differ from the source ("landslide" vs
    #                                              "land slide") without being wrong --
    #                                              compare characters, not word tokens.
    return s


def similarity(a: str, b: str) -> float:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return 0.0
    return SequenceMatcher(None, na, nb).ratio()


def main() -> None:
    os.makedirs("samples", exist_ok=True)
    print("=" * 70)
    for lang, text in CASES:
        wav_path = f"samples/roundtrip_{lang}.wav"
        print(f"\n-- {lang} --")
        synth(text, lang, wav_path)
        find_segments(wav_path)
        recognized = transcribe(wav_path, lang)
        sim = similarity(text, recognized)
        verdict = "OK" if sim >= 0.7 else "CHECK ME"
        print(f"  original:   {text!r}")
        print(f"  recognized: {recognized!r}")
        print(f"  similarity: {sim:.0%}  [{verdict}]")
    print("\n" + "=" * 70)
    print("If both cases say OK, the M0 desktop spike is done: STT+TTS both work offline.")


if __name__ == "__main__":
    main()
