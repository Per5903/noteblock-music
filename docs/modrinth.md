# NoteBlock Music

**Load a MIDI, Note Block Studio or MP3 / WAV / OGG file — get a note block contraption that plays it.**
The mod arranges the song for note blocks, lays it out as a compact redstone snake and builds it in front of you (or exports a `.schem` for WorldEdit).

![Hologram preview](https://raw.githubusercontent.com/Per5903/noteblock-music/main/docs/hologram.png)

## Features

- **Audio → notes, offline.** MP3, WAV and OGG are transcribed in-game by Spotify's open-source
  [Basic Pitch](https://github.com/spotify/basic-pitch) model, executed by a small built-in ONNX interpreter in pure Java —
  no native libraries, no internet. A 3-minute track takes ~13 s on a 12-thread CPU (~40 s on 4 cores). Works best for solo instruments and piano;
  dense mixes with vocals and drums produce messy notes, so prefer MIDI for those.
- **MIDI → note blocks.** Tempo changes, General MIDI instruments (piano → harp, bass → bass, guitars → guitar, drums → bass drum / snare / hat…), automatic fitting into each instrument's two-octave range.
- **Compact snake layout.** Long songs fold into rows with zero-delay dust turns, so the rhythm stays exact. Chords become stacked levels (2 notes per level, up to 18 notes at once); all levels start from one button on the same tick.
- **Settings window** (default key **R**, also available from Mod Menu): pick a file, speed, polyphony, transpose, row width, mute individual MIDI channels.
- **Preview playback** with real note block sounds before you build anything.
- **Hologram**: see exactly where and how big the build will be, pin it, then press *Build*.
- **Builds facing your look direction.**
- **Export to every format at once:** `.schem` (WorldEdit / FAWE), `.litematic` (Litematica — appears right in its menu),
  `.nbt` (vanilla `/place template nbmusic:<name>`, no mods needed), `.nbs` (edit in Note Block Studio).
- **Note Block Studio import** — `.nbs` songs keep their exact instruments and pitches.
- Commands for servers and scripts: `/nbmusic list | info | export | build <file> [speed%] [polyphony] [width]`.

## How to use

1. Put `.mid`, `.nbs`, `.mp3`, `.wav` or `.ogg` files into `.minecraft/nbmusic/input/` (the *Folder* button opens it) or choose one with *Browse…*.
2. Press **R**, tweak settings, press **▶ Listen** to preview.
3. Look where the song should go, press *Pin here* (or let the hologram follow you) and press **Build**.
4. Press the red-marked button at the start of the contraption.

Building requires operator rights (in singleplayer: *Allow Cheats*). Export works without them.

> Redstone only runs in loaded chunks — stay near the contraption while it plays, or reduce the row width.

## Compatibility

- Minecraft **26.2**, Fabric Loader ≥ 0.19.5, **Fabric API** required.
- Client-only: window, preview, hologram and `.schem` export work on any server.
  To build from the window or use `/nbmusic` on a server, install the mod there too.
- Every release is tested with real redstone: an automated test builds the contraption, presses the button and checks that every note plays on its exact tick.

Languages: English and Russian (follows the game language).

Third-party: Basic Pitch model © 2022 Spotify AB (Apache-2.0), javamp3 (MIT).

---

# NoteBlock Music (RU)

**Загрузи MIDI или MP3 / WAV / OGG — получи механизм из нотных блоков, который это играет.** Ноты из записи распознаёт нейросеть Basic Pitch прямо в игре (лучше всего — сольные инструменты и фортепиано). Мод аранжирует песню под нотные блоки, укладывает её компактной змейкой и строит перед тобой (или сохраняет `.schem` для WorldEdit).

- Окно настроек на **R** (и через Mod Menu): файл, скорость, полифония, транспонирование, ширина ряда, выключение дорожек.
- Предпрослушивание звуками нотных блоков, голограмма будущей постройки, постройка по взгляду.
- Импорт `.nbs`; экспорт сразу в `.schem`, `.litematic`, `.nbt` (`/place template`) и `.nbs`. Интерфейс на русском и английском.
- Аккорды — этажами (до 18 нот одновременно), развороты змейки без задержки — ритм точный.
- Команды: `/nbmusic list | info | export | build <файл> [скорость%] [полифония] [ширина]`.

Файлы (`.mid`, `.nbs`, `.mp3`, `.wav`, `.ogg`) кладутся в `.minecraft/nbmusic/input/`. Для постройки нужны права оператора (в одиночной игре — «Разрешить читы»).
