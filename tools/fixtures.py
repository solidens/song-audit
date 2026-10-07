#!/usr/bin/env python3
"""
Builds the audio the unit tests read, into app/src/test/resources/fixtures.

Nothing here is a real recording. "Music" is a few seconds of synthetic
chords, plucked notes and pink noise whose spectrum runs all the way to
Nyquist the way a real master's does, so that a lowpass, an MP3 round trip or
a resample leaves exactly the mark it would leave on a record.

Needs numpy and ffmpeg (with libmp3lame).

    python3 tools/fixtures.py
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "fixtures")


def music(seed, seconds, rate):
    """Plucked notes over pad chords, plus pink noise for the top octave."""
    rng = np.random.default_rng(seed)
    n = int(seconds * rate)
    t = np.arange(n) / rate
    out = np.zeros((n, 2))
    # Pads: slow chords, each note with a stack of harmonics.
    for start in np.arange(0, seconds, 2.0):
        root = 110 * 2 ** (rng.integers(0, 12) / 12)
        for ratio in (1, 1.25, 1.5, 2):
            f = root * ratio
            seg = (t >= start) & (t < start + 2.2)
            env = np.clip((t - start) * 4, 0, 1) * np.clip((start + 2.2 - t) * 4, 0, 1)
            for h in range(1, 12):
                if f * h > rate / 2:
                    break
                pan = rng.uniform(0.3, 0.7)
                wave_ = np.sin(2 * np.pi * f * h * t + rng.uniform(0, 6.28)) * env * seg * (0.12 / h)
                out[:, 0] += wave_ * pan
                out[:, 1] += wave_ * (1 - pan)
    # Plucks: bright decaying notes, a few per second.
    for start in np.arange(0, seconds, 0.25):
        if rng.random() < 0.3:
            continue
        f = 220 * 2 ** (rng.integers(0, 24) / 12)
        seg = t >= start
        env = np.exp(-(t - start) * 6) * seg
        for h in range(1, 40):
            if f * h > rate / 2:
                break
            w = np.sin(2 * np.pi * f * h * (t - start)) * env * (0.15 / h ** 0.8)
            out[:, 0] += w * 0.6
            out[:, 1] += w * 0.4
    # Pink-ish noise: air and cymbals, so the spectrum is not empty up top.
    white = rng.standard_normal((n, 2))
    spec = np.fft.rfft(white, axis=0)
    freqs = np.fft.rfftfreq(n, 1 / rate)
    shape = 1 / np.sqrt(np.maximum(freqs, 20))
    noise = np.fft.irfft(spec * shape[:, None], n=n, axis=0)
    noise *= 0.02 / np.std(noise)
    beat = 0.5 + 0.5 * (np.sin(2 * np.pi * 2 * t) > 0)
    out += noise * beat[:, None]
    out *= 0.8 / np.max(np.abs(out))
    return out


def write_wav(path, x, rate, bits=16):
    scale = 2 ** (bits - 1) - 1
    q = np.round(x * scale).astype(np.int64)
    if bits == 16:
        data = q.astype("<i2").tobytes()
        width = 2
    else:
        data = q.astype("<i4").view(np.uint8).reshape(-1, 4)[:, :3].tobytes()
        width = 3
    with wave.open(path, "wb") as w:
        w.setnchannels(x.shape[1])
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(data)


def ff(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    tmp = tempfile.mkdtemp()
    out = lambda name: os.path.join(OUT, name)

    # The source: 8 seconds of 16/44.1 "music". DR blocks are 3 s, so 8 s gives two full ones.
    a = music(1, 8, 44100)
    src = os.path.join(tmp, "a.wav")
    write_wav(src, a, 44100)

    meta = ["-metadata", "title=First", "-metadata", "artist=Synth", "-metadata", "album=Fixtures",
            "-metadata", "track=1/3", "-metadata", "album_artist=Synth"]

    # Genuine CD quality, at a few compression levels to cover the decoder's paths.
    ff("-i", src, *meta, "-compression_level", "8", out("cd.flac"))
    ff("-i", src, "-compression_level", "0", out("cd-fast.flac"))
    ff("-i", src, "-compression_level", "12", "-lpc_type", "cholesky", out("cd-max.flac"))
    ff("-i", src, "-ac", "1", out("mono.flac"))

    # The same audio through MP3 and back: a lossy source in a lossless box.
    ff("-i", src, "-b:a", "128k", out("cd-128.mp3"))
    ff("-i", out("cd-128.mp3"), "-sample_fmt", "s16", out("transcoded-128.flac"))
    ff("-i", src, "-b:a", "320k", out("cd-320.mp3"))
    ff("-i", out("cd-320.mp3"), "-sample_fmt", "s16", out("transcoded-320.flac"))

    # Upsampled to 96k/24: a CD in hi-res clothing.
    ff("-i", src, "-af", "aresample=96000", "-sample_fmt", "s32", "-bits_per_raw_sample", "24",
       out("upsampled-96.flac"))
    # Padded: 16-bit samples in a 24-bit file at the same rate.
    ff("-i", src, "-sample_fmt", "s32", "-bits_per_raw_sample", "24", out("padded-24.flac"))

    # Genuine hi-res: generated at 96k with content past 22 kHz, written at 24 bits.
    h = music(2, 6, 96000)
    hsrc = os.path.join(tmp, "h.wav")
    write_wav(hsrc, h, 96000, bits=24)
    ff("-i", hsrc, "-sample_fmt", "s32", "-bits_per_raw_sample", "24", out("hires-96.flac"))

    # A different song entirely, for "no match".
    b = music(3, 8, 44100)
    bsrc = os.path.join(tmp, "b.wav")
    write_wav(bsrc, b, 44100)
    ff("-i", bsrc, out("other.flac"))

    # Same song at 48k, and with half a second of silence in front: still a match.
    ff("-i", src, "-af", "aresample=48000", out("cd-48k.flac"))
    ff("-i", src, "-af", "adelay=500|500", out("delayed.flac"))

    # Damage: flip bytes in the middle of a copy.
    with open(out("cd.flac"), "rb") as f:
        data = bytearray(f.read())
    for i in range(len(data) // 2, len(data) // 2 + 40):
        data[i] ^= 0x5A
    with open(out("damaged.flac"), "wb") as f:
        f.write(data)
    # Truncated: the last third gone, as from an interrupted copy.
    with open(out("cd.flac"), "rb") as f:
        clean = f.read()
    with open(out("truncated.flac"), "wb") as f:
        f.write(clean[: len(clean) * 2 // 3])

    # Plain PCM for the WAV reader.
    ff("-i", src, "-t", "2", out("short.wav"))

    for name in sorted(os.listdir(OUT)):
        print(f"{os.path.getsize(os.path.join(OUT, name)) // 1024:6d} KB  {name}")


if __name__ == "__main__":
    sys.exit(main())
