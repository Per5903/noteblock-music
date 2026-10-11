# Сторонние компоненты

| Компонент | Где | Лицензия |
|---|---|---|
| [Basic Pitch](https://github.com/spotify/basic-pitch) — модель `nmp.onnx` (ICASSP 2022), © 2022 Spotify AB | `src/main/resources/nbmusic/basic-pitch/` (файл не изменён) | Apache License 2.0 — `LICENSE-basic-pitch.txt` там же |
| [javamp3](https://github.com/Delthas/JavaMP3) 1.0.1 — MP3-декодер, © delthas | вложен в jar мода (Jar-in-Jar) | MIT |

Интерпретатор ONNX, извлечение нот, декодеры WAV/OGG и передискретизация написаны для NoteBlock Music
(`dev.nbmusic.core.transcribe`, `dev.nbmusic.core.io`) и распространяются под MIT вместе с остальным кодом мода.
OGG декодируется библиотекой stb_vorbis из LWJGL, которая входит в Minecraft.
