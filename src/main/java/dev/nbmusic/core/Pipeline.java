package dev.nbmusic.core;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.io.MidiDecoder;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.layout.LayoutGenerator.Layout;
import dev.nbmusic.core.model.Song;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Файл → песня → аранжировка → раскладка блоков. Не зависит от Minecraft. */
public final class Pipeline {
	public static final Set<String> MIDI_EXTENSIONS = Set.of("mid", "midi");
	/** MIDI и аудио (аудио сначала распознаётся, см. {@link dev.nbmusic.core.transcribe.AudioTranscriber}). */
	public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mid", "midi", "wav", "mp3", "ogg");

	private Pipeline() {
	}

	public record Result(Song song, Arrangement arrangement, Layout layout) {
	}

	public static Result run(Path file, Arranger.Options options) throws IOException {
		return run(file, options, LayoutGenerator.DEFAULT_ROW_LENGTH);
	}

	/** @param rowLength длина ряда змейки в блоках */
	public static Result run(Path file, Arranger.Options options, int rowLength) throws IOException {
		String fileName = file.getFileName().toString();
		String ext = extension(fileName);
		if (!MIDI_EXTENSIONS.contains(ext)) {
			throw new IOException(SUPPORTED_EXTENSIONS.contains(ext)
					? "Аудио ." + ext + " сначала нужно распознать (AudioTranscriber)"
					: "Формат ." + ext + " не поддерживается: нужен MIDI, MP3, WAV или OGG");
		}
		try (InputStream in = Files.newInputStream(file)) {
			return arrange(load(baseName(fileName), in), options, rowLength);
		}
	}

	public static Song load(String title, InputStream in) throws IOException {
		Song song = MidiDecoder.decode(title, in);
		if (song.notes().isEmpty()) {
			throw new IOException("В файле нет нот");
		}
		return song;
	}

	public static Result arrange(Song song, Arranger.Options options, int rowLength) {
		Arrangement arrangement = Arranger.arrange(song, options);
		if (arrangement.chords().isEmpty()) {
			throw new IllegalArgumentException("Все дорожки выключены");
		}
		return new Result(song, arrangement, LayoutGenerator.generate(arrangement, rowLength));
	}

	public static String extension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	public static String baseName(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? fileName : fileName.substring(0, dot);
	}
}
