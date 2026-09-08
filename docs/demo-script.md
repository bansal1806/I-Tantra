# Demo script

A rehearsal-ready script for the live two-phone demo, based on exactly what's been verified
working (see `docs/metrics.md` for where the numbers below came from). Practice this until
it's boring — that's the actual goal per the plan's 36-hour game plan.

## Before you're on stage

1. **Charge both phones.** Push-to-talk + two ONNX Runtime sessions loaded is real CPU work.
2. **Install fresh builds on both phones** (don't trust a build from last week):
   ```
   android\gradlew.bat installDebug   -- once per connected phone (adb -s <serial> if both plugged in)
   ```
3. **Turn on mobile hotspot on Phone A**, note its Wi-Fi/hotspot password.
4. **Connect Phone B to Phone A's hotspot** (Settings → Wi-Fi → join). Confirm both show a Wi-Fi
   icon, not just cellular.
5. Open iTantra on both. Wait for **"Ready — hold the button and speak"** on both screens
   before doing anything else — model loading takes a few seconds.
6. On Phone A: tap **Host**. It'll show "Waiting for the other phone to join at `<ip>`…" — note
   that IP.
7. On Phone B: tap **Join**, type Phone A's IP, tap **Connect**. Both should now say
   "Connected to `<peer ip>`".
8. Sanity-check silently before walking on stage: hold-to-talk a throwaway sentence on each
   phone, confirm it's heard on the other. This is also your fallback if setup runs late — the
   whole demo works with just this one connection established.

## The demo, in order

### 1. The hook (10s)
Hold up both phones side by side, screens visible. No slides yet — just say:

> "Two phones. No shared network, no internet, no SIM required between them — just Wi-Fi
> Direct-equivalent. Watch what happens when I say something."

### 2. Core loop, Hindi (30s)
- Hold Phone A's push-to-talk, say a real sentence — not a scripted-sounding one. Something
  like *"पुल के पास भूस्खलन हुआ है, मदद भेजो"* ("There's been a landslide near the bridge, send
  help") lands the disaster-relief framing without saying it out loud.
- Release. Point at "Recognized text" as it fills in.
- Point at Phone B as it **speaks the sentence back automatically** — don't narrate over it,
  let the audience actually hear it happen.

### 3. The number that's the whole pitch (20s)
Immediately after step 2, point at the line that appeared under "Recognized text" on Phone A:

> "Sent NN bytes — a voice note of the same length would be ~X KB. That's the entire idea:
> we don't send your voice, we send what you *said*, and rebuild the voice on the other end."

This line is live, not a slide — it's the actual byte count for the sentence you just said.
Trust it over a rehearsed number.

### 4. English + language switch (15s)
- Tap the **English** toggle on Phone A (both phones don't need to match — but for a clean
  demo, switch both). Wait for "Ready" again — narrate that model swap while it loads:
  > "Only one language's models are ever in memory — this reload is what a language switch
  > costs."
- One short English sentence, same loop.

### 5. The alert (20s) — the payoff moment
- Turn Phone B's ringer to **silent** in front of the audience, visibly.
- On Phone A, check **"🚨 Send as ALERT"**, hold-to-talk, say something urgent:
  *"तुरंत यहाँ से हटो"* ("Get out of here immediately") or an English equivalent.
- Phone B **plays at full volume anyway**, despite being on silent, with "🚨 ALERT:" on screen.
  This is the line that should land hardest — say nothing while it plays, let it speak over
  the silence.

### 6. Close (15s)
> "Two phones, ₹[X] hardware, zero connectivity between them beyond what they're carrying,
> and a landslide warning that gets through in [Y] milliseconds and 76 bytes instead of a
> multi-kilobyte voice note nobody has bandwidth for."

## If something goes wrong

- **A phone shows "Not connected" mid-demo**: check for the "Peer disconnected" toast — tap
  Host (A) or Join → re-enter IP → Connect (B) again. Rehearsed reconnect is faster than
  panic; know the IP by heart going in.
- **STT mishears**: don't repeat the same sentence a second time in a panic — pick a
  *different, shorter* one. A retry of the identical sentence reads as the system being
  broken; a new one reads as you moving on.
- **Total network failure** (venue Wi-Fi/hotspot interference): fall back to the single-phone
  loop — hold-to-talk into one phone, then use the **Speak** button to read the recognized
  text back on the same phone. Still demonstrates STT+TTS accuracy even with M2 unavailable.
- **A model fails to load** ("Couldn't load: ..." with a Retry button): tap Retry once. If it
  persists, it's almost certainly storage — clear some space on the phone before the slot,
  not during it.

## What NOT to do

- Don't read the bitrate/latency numbers off a slide when the app is showing live ones on
  screen — the live number is more convincing than any slide, use it.
- Don't over-explain the architecture before showing it working. Section 1-2 above should
  happen in the first 40 seconds, not after a 3-minute setup monologue.
- Don't demo a language or sentence you haven't personally tested in the last hour. STT/TTS
  quality varies enough by exact phrasing that "it worked yesterday" isn't a guarantee.
