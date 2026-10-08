Findings can now be acted on.

- **Damaged and Not what it says**: set tracks aside in the quarantine, or keep them as they are and off the list. This works from the list, the album and the track.
- **Shrink to true size**: upsampled FLAC goes back to 24/48 or 24/44.1, and padded 24-bit goes back to 16. Each new file is checked against its own MD5 before it replaces the old one, and the originals stay in quarantine.
- **Fix tags and covers** in FLAC and MP3:
  - missing covers found nearby or chosen from the device;
  - heavy covers shrunk;
  - one album name and album artist per folder;
  - Various Artists for compilations;
  - missing titles and numbers taken from file names.
- **Undo**: every fix can be undone from the quarantine, byte for byte.

Installs over 0.1.0 and keeps its scan.

<img src="https://raw.githubusercontent.com/solidens/song-audit/main/docs/screenshots/05-fix.png" width="260">
