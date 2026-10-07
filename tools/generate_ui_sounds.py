#!/usr/bin/env python3
"""Synthesise the UI sound effects shipped in `app/src/main/res/raw`.

These sounds are written from scratch here rather than taken from a sound library, so
the project owns them outright and there is no licensing question about what plays on
a button tap. Re-run this script to regenerate them byte-for-byte:

    python tools/generate_ui_sounds.py

Every tone is a sine stack with an exponential decay envelope and a short fade at both
ends, which is what keeps them short and free of the click you get from starting or
stopping a waveform at a non-zero amplitude. They are deliberately quiet peaks (~0.68
full scale) so the player can still turn them down.
"""

import math
import os
import struct
import wave

SAMPLE_RATE = 22050
OUTPUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res", "raw",
)

# How much of the buffer each tone fades in and out over. Longer removes the click;
# shorter keeps the sound tight. 6ms is below what the ear reads as a separate event.
FADE_SECONDS = 0.006


def envelope(length, attack=0.004, decay=6.0):
    """Attack ramp, exponential decay, then a fade at both ends to zero."""
    fade = int(FADE_SECONDS * SAMPLE_RATE)
    out = []
    for i in range(length):
        t = i / SAMPLE_RATE
        ramp = min(1.0, t / attack) if attack > 0 else 1.0
        value = ramp * math.exp(-decay * max(0.0, t - attack))
        if i < fade:
            value *= i / fade
        if i > length - fade:
            value *= max(0.0, (length - i) / fade)
        out.append(value)
    return out


def render(notes, duration, attack=0.004, decay=6.0, gain=0.68):
    """Mix a list of (start_seconds, frequency, amplitude) notes into one buffer.

    A bell-ish timbre comes from a second harmonic an octave up at a lower amplitude,
    which decays with the fundamental rather than ringing separately - close enough for
    a 300ms notification and far simpler than modelling partials properly.
    """
    length = int(duration * SAMPLE_RATE)
    buffer = [0.0] * length
    for start, freq, amp in notes:
        offset = int(start * SAMPLE_RATE)
        env_len = length - offset
        if env_len <= 0:
            continue
        env = envelope(env_len, attack=attack, decay=decay)
        for i in range(env_len):
            t = i / SAMPLE_RATE
            fundamental = math.sin(2.0 * math.pi * freq * t)
            harmonic = math.sin(2.0 * math.pi * freq * 2.0 * t) * 0.32
            buffer[offset + i] += amp * env[i] * (fundamental + harmonic)

    peak = max(abs(v) for v in buffer) or 1.0
    scale = gain / peak
    return [v * scale for v in buffer]


def write_wav(name, samples):
    path = os.path.join(OUTPUT_DIR, name + ".wav")
    frames = b"".join(
        struct.pack("<h", max(-32767, min(32767, int(v * 32767)))) for v in samples
    )
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(frames)
    print(f"  {name}.wav  {len(frames) + 44} bytes  {len(samples) / SAMPLE_RATE:.3f}s")


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"Writing to {OUTPUT_DIR}")

    # A soft tick for ordinary taps: a low body so it reads as a physical press, plus
    # a quiet high blip so it is audible on a phone speaker without being loud.
    tick = int(0.070 * SAMPLE_RATE)
    tick_env = envelope(tick, attack=0.001, decay=42.0)
    tick_samples = [
        amp * (
            math.sin(2.0 * math.pi * 180.0 * (i / SAMPLE_RATE)) * 0.75
            + math.sin(2.0 * math.pi * 2400.0 * (i / SAMPLE_RATE)) * 0.25
        )
        for i, amp in enumerate(tick_env)
    ]
    peak = max(abs(v) for v in tick_samples)
    write_wav("ui_tap", [v * 0.60 / peak for v in tick_samples])

    # Starting a session: two notes a fifth apart, the second quieter, so it reads as
    # "here we go" rather than as an alarm.
    write_wav("ui_start", render(
        [(0.000, 587.33, 0.85), (0.085, 880.00, 0.70)],
        duration=0.34, attack=0.006, decay=9.0,
    ))

    # A correct pronunciation: a rising three-note figure. Bright, short, satisfying.
    write_wav("ui_success", render(
        [(0.000, 523.25, 0.80), (0.070, 659.25, 0.80), (0.140, 783.99, 0.85)],
        duration=0.52, attack=0.004, decay=8.0,
    ))

    # Finishing a set: settles downward then back up, so it closes an arc instead of
    # shouting. Used when a review sitting completes.
    write_wav("ui_complete", render(
        [(0.000, 783.99, 0.85), (0.130, 587.33, 0.80), (0.260, 659.26, 0.75)],
        duration=0.72, attack=0.005, decay=5.5,
    ))

    # A single bell for a word arriving on screen.
    write_wav("ui_newword", render(
        [(0.000, 987.77, 0.90)],
        duration=0.42, attack=0.003, decay=7.5,
    ))

    # An achievement: a four-note climb, which is the only genuinely celebratory sound
    # in the set and is therefore used only for unlocks.
    write_wav("ui_achievement", render(
        [(0.000, 523.25, 0.75), (0.065, 659.25, 0.75),
         (0.130, 783.99, 0.78), (0.195, 1046.50, 0.85)],
        duration=0.70, attack=0.004, decay=6.5,
    ))

    print("Done.")


if __name__ == "__main__":
    main()
