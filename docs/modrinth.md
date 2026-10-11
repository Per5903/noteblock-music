# NoteBlock Music

**Load a MIDI file — get a note block contraption that plays it.**
The mod arranges the song for note blocks, lays it out as a compact redstone snake and builds it in front of you (or exports a `.schem` for WorldEdit).

![Hologram preview](https://raw.githubusercontent.com/Per5903/noteblock-music/main/docs/hologram.png)

## Features

- **MIDI → note blocks.** Tempo changes, General MIDI instruments (piano → harp, bass → bass, guitars → guitar, drums → bass drum / snare / hat…), automatic fitting into each instrument's two-octave range.
- **Compact snake layout.** Long songs fold into rows with zero-delay dust turns, so the rhythm stays exact. Chords become stacked levels (2 notes per level, up to 18 notes at once); all levels start from one button on the same tick.
- **Settings window** (default key **R**, also available from Mod Menu): pick a file, speed, polyphony, transpose, row width, mute individual MIDI channels.
- **Preview playback** with real note block sounds before you build anything.
- **Hologram**: see exactly where and how big the build will be, pin it, then press *Build*.
- **Builds facing your look direction**; `.schem` export for WorldEdit / FAWE.
- Commands for servers and scripts: `/nbmusic list | info | export | build <file> [speed%] [polyphony] [width]`.

## How to use

1. Put `.mid` files into `.minecraft/nbmusic/input/` (the *Folder* button opens it) or choose one with *Browse…*.
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

The interface is currently in Russian; an English translation is planned.

---

# NoteBlock Music (RU)

**Загрузи MIDI — получи механизм из нотных блоков, который его играет.** Мод аранжирует песню под нотные блоки, укладывает её компактной змейкой и строит перед тобой (или сохраняет `.schem` для WorldEdit).

- Окно настроек на **R** (и через Mod Menu): файл, скорость, полифония, транспонирование, ширина ряда, выключение дорожек.
- Предпрослушивание звуками нотных блоков, голограмма будущей постройки, постройка по взгляду.
- Аккорды — этажами (до 18 нот одновременно), развороты змейки без задержки — ритм точный.
- Команды: `/nbmusic list | info | export | build <файл> [скорость%] [полифония] [ширина]`.

MIDI-файлы кладутся в `.minecraft/nbmusic/input/`. Для постройки нужны права оператора (в одиночной игре — «Разрешить читы»).
