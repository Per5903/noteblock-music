# Публикация на Modrinth

Всё для загрузки уже настроено в `build.gradle` (плагин Minotaur): jar, исходники, версия игры, загрузчик,
зависимости, changelog из `CHANGELOG.md` и описание страницы из `docs/modrinth.md`.

## Один раз

1. **Сделать репозиторий публичным** — Modrinth показывает ссылку на исходники, а у приватного репозитория она ведёт на 404,
   и картинки в описании (`raw.githubusercontent.com/.../docs/*.png`) не загрузятся:
   `gh repo edit Per5903/noteblock-music --visibility public --accept-visibility-change-consequences`
2. **Создать проект** на https://modrinth.com/dashboard/projects → *Create a project*:
   - Name: `NoteBlock Music`, URL (slug): `noteblock-music` (свободен), Visibility: Public
   - Summary: `Load a MIDI file and get a note block contraption that plays it.`
3. **Заполнить настройки проекта:**
   - Categories: `Utility`, `Decoration` (дополнительно: `Technology`)
   - Environment: Client — *Optional*, Server — *Optional*
   - License: `MIT`
   - Source: `https://github.com/Per5903/noteblock-music`, Issues: `.../issues`
   - Icon: `docs/icon-512.png`
   - Gallery: `docs/hologram.png`, `docs/gui.png`, `docs/snake.png`, `docs/built.png`
4. **Токен:** https://modrinth.com/settings/pats → *Create a PAT* с правами
   `Create versions`, `Write projects`. Хранить как секрет, в репозиторий не коммитить.

## Каждый релиз

1. Поднять `version` в `gradle.properties`, дописать раздел сверху в `CHANGELOG.md`.
2. Проверить: `./gradlew build runClientGameTest`.
3. Проверить, что уйдёт на Modrinth, без загрузки:
   `MODRINTH_DEBUG=1 ./gradlew modrinth`
4. Загрузить: `MODRINTH_TOKEN=<токен> ./gradlew modrinth`
   (заодно обновит описание страницы из `docs/modrinth.md`).

Проект проходит ручную модерацию Modrinth (обычно до нескольких дней) — до одобрения он виден только тебе.
