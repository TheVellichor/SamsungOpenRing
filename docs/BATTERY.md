# Galaxy Ring Battery — Diagnosis & Longevity Plan

Why the ring drains, what was actually measured, and a ranked plan to make it last
as long as possible.

## TL;DR

- **Gesture detection is cheap** — Samsung rates the ring ~7 days *including* gesture
  use. Do not over-optimise gestures.
- **The dominant drain is continuous biometric sensing** (heart rate, SpO2, skin
  temperature, stress), plus leaving the ring **out of its charging case**.
- The widespread "1 week → 1 day" complaint is frequently a **stuck-sensor hardware
  fault** that Samsung replaces under warranty, or a firmware regression.
- The `SamsungOpenRing` app's own contribution was real but small, and is now fixed.

## What we measured

During the live session the ring fell **63% → 52% over ~50 minutes** (≈10%/hr). That
number is **not** representative of normal wear — it was inflated by:

- our **second** BLE connection (alongside Samsung's), and
- constant active probing (reads/writes every few seconds), and
- gesture detection held enabled.

Normal wear has none of that. Samsung's ~6–7 day rating is ≈0.6%/hr average. So the
session's ~10%/hr reflects active debugging, not the user's real-world drain.

Other measured facts relevant to power:
- The ring measures **heart rate continuously** (live HR seen the whole session;
  HR → 0 only when removed). Continuous optical HR is the single biggest sensor draw.
- The Channel 23 heartbeat fires only every 600 s — negligible.
- Removing the ring tears down Samsung Health's wearable session and stops HR.

## Ranked optimisation plan

### Tier 1 — biggest levers
1. **Store the ring in its charging case whenever it is off your finger.** Out of the
   case the sensors keep running on nothing; in the case they idle and top up.
2. **Heart Rate → manual/periodic** (Samsung Health → Ring → Heart rate). Continuous
   optical HR is the #1 sensor drain.
3. **Blood Oxygen (SpO2) during sleep → off** if unused — it runs all night.

### Tier 2 — meaningful
4. Disable **skin temperature**, **continuous stress**, and **snore detection** if not
   needed.
5. If you own a **Galaxy Watch, wear both** — Samsung quotes ~+30% ring life because
   the watch takes over sensing and the ring duty-cycles down.

### Tier 3 — app / second-client (mostly already handled)
6. **Do not run a second BLE client** against the ring continuously (the debug bridge,
   for example) — measured to add real drain.
7. The production `SamsungOpenRing` app is fixed: no gesture-pinning, respects
   Samsung's power-saving, `CONNECTION_PRIORITY_LOW_POWER`, and an absolute 8 h session
   cap. Keep triggers to short edge-events.

### Tier 4 — rule out the hardware fault
8. If, after Tier 1–2 *and* with our app off, it still dies in ~a day, it is very
   likely the **known stuck-sensor fault** → Samsung warranty replacement. Keep
   firmware current (and note a recent firmware update is itself a known regression
   trigger).

## How to verify (the 24 h A/B)

Don't guess — measure:

1. Apply Tier 1 settings; make sure no second BLE client (bridge) is running.
2. Note the ring % in Samsung Health.
3. Wear it normally for ~24 h (or a full waking day).
4. Note the % again → that's your true baseline drain rate.
5. If it is still bleeding with everything minimised and our app gone → it is the
   hardware → warranty.

The debug bridge can log the ring's `RING BATTERY level=NN` over time (Channel 11
`0b 0b 03` sync) for a finer-grained attribution, but for an unattended day the
Samsung Health % at start/end is sufficient and reliable.

## App-side fixes shipped (for reference)

See `SamsungOpenRing/` and [SESSION-2026-06-19.md](SESSION-2026-06-19.md) §2:
tug-of-war removed; broken 60 s sliding watchdog replaced with an absolute persisted
session cap that honours the trigger window and renews only while a trigger is active;
low-power BLE; ring-battery telemetry. The earlier README claim of "no additional
battery drain" was corrected to reflect reality.
