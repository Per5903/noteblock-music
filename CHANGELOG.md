# Changelog

## 0.4.0+26.2

- **MP3, WAV and OGG support.** Notes are transcribed in-game by Spotify's Basic Pitch model (Apache-2.0),
  run by a built-in ONNX interpreter in pure Java — no native libraries, +270 KB.
- Background transcription with a progress bar in the window; commands transcribe in the background and reply in chat.
- Results are cached by file hash; the transcribed MIDI is also saved to `nbmusic/output/`.
- Retrigger filter: sustained notes are not repeated when other notes start.
- MP3 decoding via javamp3 (MIT); OGG via the game's stb_vorbis.

## 0.3.0+26.2

- Settings window (key **R**, Mod Menu): file picker, speed, polyphony, transpose, row width, per-channel mute.
- Preview playback with note block sounds and a progress bar.
- In-world hologram: bounding box, coloured note blocks by instrument, start button; can be pinned.
- *Build* button sends the file to the server in 30 KB chunks and builds exactly at the hologram (operator only).
- `.schem` export from the window.
- Mod icon, Mod Menu entry, English description.

## 0.2.0+26.2

- Snake layout with zero-delay dust turns, configurable row width.
- Chords stacked as levels (2 notes per level, up to 9 levels); start riser keeps all levels in sync.
- `/nbmusic build` places the contraption along the player's facing; `.schem` offset for WorldEdit.

## 0.1.0+26.2

- MIDI decoder with tempo map, arranger (0.1 s grid, GM instruments, range fitting, polyphony limit).
- Straight-lane layout, Sponge Schematic v3 export, `/nbmusic list|info|export|build`.
