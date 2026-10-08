#!/usr/bin/env python3
"""
Builds a small music library with one of everything the audit looks for,
to see the app working without risking a real collection.

    python3 tools/demo_library.py OUT_DIR
    adb push OUT_DIR/. /sdcard/Music/

Every album is synthetic (see fixtures.py for how the "music" is made), so
the folder can be shared and published freely.
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(__file__))
from fixtures import music, write_wav  # noqa: E402


def ff(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def cover(path, colour, size=600, heavy=False):
    if heavy:
        # Noise does not compress: a 3000 px JPEG of it weighs megabytes, like a scan straight off a flatbed.
        ff("-f", "lavfi", "-i", f"nullsrc=s={size}x{size}", "-vf", "geq=random(1)*255:128:128", "-frames:v", "1", "-q:v", "2", path)
    else:
        ff("-f", "lavfi", "-i", f"color=c={colour}:s={size}x{size}", "-frames:v", "1", path)


def tags(title, artist, album, track, total=None, album_artist=None):
    t = ["-metadata", f"title={title}", "-metadata", f"artist={artist}", "-metadata", f"album={album}",
         "-metadata", f"track={track}" + (f"/{total}" if total else "")]
    if album_artist:
        t += ["-metadata", f"album_artist={album_artist}"]
    return t


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "demo-library"
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    tmp = tempfile.mkdtemp()

    # Fourteen source songs, 14 s each: long enough for a fingerprint, short enough to listen to quickly.
    names = ["Northern Line", "Paper Moon", "Second Floor", "Low Tide", "Glass House", "Broken Bow", "Arrow",
             "Night Drive", "Neon", "Quiet Rooms", "Stairwell", "Lantern", "Big Sky", "Harbour"]
    songs = []
    for seed in range(len(names)):
        path = os.path.join(tmp, f"s{seed}.wav")
        write_wav(path, music(10 + seed, 14, 44100), 44100)
        songs.append(path)
    red = os.path.join(tmp, "red.jpg")
    cover(red, "0xD62E1F")

    def album(folder, artist, title, tracks, fmt="flac", extra=(), art=red, af=None, total=True, album_artist=None,
              embed=False):
        d = os.path.join(out, folder)
        os.makedirs(d, exist_ok=True)
        n = len(tracks)
        for i, (song, name) in enumerate(tracks, 1):
            dst = os.path.join(d, f"{i:02d} {name}.{fmt}")
            args = ["-i", song]
            if embed and art:
                args += ["-i", art, "-map", "0:a", "-map", "1", "-c:v", "copy", "-disposition:v", "attached_pic"]
            if af:
                args += ["-af", af]
            artist_i = artist(i) if callable(artist) else artist
            ff(*args, *tags(name, artist_i, title, i, n if total else None, album_artist), *extra, dst)
        if art and not embed:
            shutil.copy(art, os.path.join(d, "cover.jpg"))
        return d

    three = list(zip(songs[:3], names[:3]))

    # A clean CD rip, and a stray copy of its first track.
    d = album("Halden/Northern Line (2019)", "Halden", "Northern Line", three)
    shutil.copy(os.path.join(d, "01 Northern Line.flac"), os.path.join(d, "01 Northern Line (1).flac"))

    # The same album "remastered in 24/96": an upsample.
    album("Halden/Northern Line (2019) [24-96]", "Halden", "Northern Line", three,
          af="aresample=96000", extra=("-sample_fmt", "s32", "-bits_per_raw_sample", "24"))

    # And again as MP3.
    album("Halden/Northern Line (2019) [MP3]", "Halden", "Northern Line", three, fmt="mp3", extra=("-b:a", "320k"))

    # A FLAC album made from 128 kbps MP3s.
    lossy = []
    for i, s in enumerate(songs[3:5]):
        mp3 = os.path.join(tmp, f"l{i}.mp3")
        ff("-i", s, "-b:a", "128k", mp3)
        wav = os.path.join(tmp, f"l{i}.wav")
        ff("-i", mp3, wav)
        lossy.append(wav)
    album("Morrow/Low Tide", "Morrow", "Low Tide", list(zip(lossy, names[3:5])))

    # 16-bit audio in 24-bit files.
    album("Morrow/Glass House (24-bit)", "Morrow", "Glass House", [(songs[4], names[4]), (songs[3], names[3])],
          extra=("-sample_fmt", "s32", "-bits_per_raw_sample", "24"))

    # Genuine hi-res.
    hires = os.path.join(tmp, "h.wav")
    write_wav(hires, music(42, 14, 96000), 96000, bits=24)
    album("Sela/Open Air [24-96]", "Sela", "Open Air", [(hires, "Open Air")],
          extra=("-sample_fmt", "s32", "-bits_per_raw_sample", "24"))

    # Damage: one flipped patch, one cut short.
    d = album("Kestrel/Broken Bow", "Kestrel", "Broken Bow", [(songs[5], names[5]), (songs[6], names[6])])
    for name, mode in (("01 Broken Bow.flac", "flip"), ("02 Arrow.flac", "cut")):
        p = os.path.join(d, name)
        data = bytearray(open(p, "rb").read())
        if mode == "flip":
            for i in range(len(data) // 2, len(data) // 2 + 64):
                data[i] ^= 0x5A
        else:
            data = data[: len(data) * 2 // 3]
        open(p, "wb").write(data)

    # A compilation: one song from the album above, every track a different artist, no album artist.
    album("Various/Night Drive", lambda i: ["Halden", "Iver", "Lune"][i - 1], "Night Drive",
          [(songs[0], "Northern Line"), (songs[7], names[7]), (songs[8], names[8])])

    # No art anywhere, and track 3 of 4 missing.
    d = album("Iver/Quiet Rooms", "Iver", "Quiet Rooms", [(songs[i], names[i]) for i in (9, 10, 11, 1)], art=None)
    os.remove(os.path.join(d, "03 Lantern.flac"))

    # A 3000 px scan embedded in every track.
    heavy = os.path.join(tmp, "heavy.jpg")
    cover(heavy, None, size=3000, heavy=True)
    album("Lune/Big Sky", "Lune", "Big Sky", [(songs[12], names[12]), (songs[13], names[13])], art=heavy, embed=True)

    # Ripped without a tag lookup: MP3s that only know their file names.
    d = os.path.join(out, "Sela", "Loose Ends")
    os.makedirs(d)
    for i, s in enumerate((songs[2], songs[6]), 1):
        ff("-i", s, "-b:a", "192k", "-map_metadata", "-1", "-id3v2_version", "3",
           os.path.join(d, f"{i:02d} - {['Second Floor', 'Arrow'][i - 1]}.mp3"))
    shutil.copy(red, os.path.join(d, "folder.jpg"))

    # Two discs in two folders, the cover one folder up where neither disc's player view sees it.
    root = os.path.join(out, "Halden", "Two Rivers")
    album("Halden/Two Rivers/CD1", "Halden", "Two Rivers", [(songs[0], names[0])], art=None)
    album("Halden/Two Rivers/CD2", "Halden", "Two Rivers", [(songs[1], names[1])], art=None)
    blue = os.path.join(tmp, "blue.jpg")
    cover(blue, "0x0F4CD1")
    shutil.copy(blue, os.path.join(root, "cover.jpg"))

    total = 0
    for root, _, files in os.walk(out):
        for f in files:
            total += os.path.getsize(os.path.join(root, f))
    print(f"{out}: {sum(len(f) for _, _, f in os.walk(out))} files, {total // 1_000_000} MB")


if __name__ == "__main__":
    main()
