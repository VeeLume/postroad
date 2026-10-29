# /// script
# dependencies = ["numpy", "soundfile"]
# ///
"""Synthesized sounds: the chime that announces mail in a mailbox.

Run from the repo root: uv run tools/gen_sounds.py
"""
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/postroad/sounds"
RATE = 44100


def bell(freq, seconds, loud=1.0):
    """A soft bell: a few slightly inharmonic partials, each decaying at its own rate, a quick attack."""
    t = np.arange(int(RATE * seconds)) / RATE
    partials = [(1.0, 1.0, 2.2), (2.0, 0.35, 3.5), (2.76, 0.18, 5.0), (4.07, 0.08, 7.0), (0.5, 0.15, 1.6)]
    out = np.zeros_like(t)
    for ratio, amp, decay in partials:
        out += amp * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-t * decay)
    return out * np.clip(t / 0.004, 0, 1) * loud


def mix(length, *notes):
    """Lays (start seconds, sound) notes over each other, fades the tail out, normalizes."""
    out = np.zeros(int(RATE * length))
    for start, sound in notes:
        i = int(RATE * start)
        out[i:i + len(sound)] += sound[: len(out) - i]
    out *= np.clip((length - np.arange(len(out)) / RATE) / 0.05, 0, 1)
    return out / (np.max(np.abs(out)) / 0.8)


def mail_arrived():
    # Three rising notes, G5 B5 D6: a major triad, like a message notification.
    tune = mix(1.3, (0.0, bell(784.0, 1.3, 0.8)), (0.13, bell(988.0, 1.2, 0.8)), (0.26, bell(1175.0, 1.0)))
    ROOT.mkdir(parents=True, exist_ok=True)
    sf.write(ROOT / "mail_arrived.ogg", tune.astype(np.float32), RATE, format="OGG", subtype="VORBIS")


if __name__ == "__main__":
    mail_arrived()
