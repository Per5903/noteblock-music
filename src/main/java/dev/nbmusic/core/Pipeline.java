package dev.nbmusic.core;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.io.MidiDecoder;
import dev.nbmusic.core.io.NbsFormat;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.layout.LayoutGenerator.Layout;
import dev.nbmusic.core.model.Song;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Файл → песня → аранжировка → раскладка блоков. Не зависит от Minecraft. */
public final class Pipeline {
	/** Файлы с готовыми нотами: читаются сразу. */
	public static final Set<String> NOTE_EXTENSIONS = Set.of("mid", "midi", "nbs");
	/** Ноты и аудио (аудио сначала распознаётся, см. {@link dev.nbmusic.core.transcribe.AudioTranscriber}). */
	public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mid", "midi", "nbs", "wav", "mp3", "ogg");

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
		if (!NOTE_EXTENSIONS.contains(ext)) {
			throw new IOException(SUPPORTED_EXTENSIONS.contains(ext)
					? "Audio ." + ext + " must be transcribed first (AudioTranscriber)"
					: new Msg("nbmusic.error.format", "Format .%s is not supported: use MIDI, NBS, MP3, WAV or OGG", ext).text());
		}
		return arrange(load(baseName(fileName), ext, Files.readAllBytes(file)), options, rowLength);
	}

	/** Файл с нотами по расширению: MIDI или Note Block Studio. */
	public static Song load(String title, String extension, byte[] data) throws IOException {
		Song song = switch (extension.toLowerCase(Locale.ROOT)) {
			case "mid", "midi" -> MidiDecoder.decode(title, new ByteArrayInputStream(data));
			case "nbs" -> NbsFormat.read(title, data);
			default -> throw new Msg.Failure(new Msg("nbmusic.error.format",
					"Format .%s is not supported: use MIDI, NBS, MP3, WAV or OGG", extension));
		};
		if (song.notes().isEmpty()) {
			throw new Msg.Failure(new Msg("nbmusic.error.no_notes", "The file has no notes"));
		}
		return song;
	}

	public static Result arrange(Song song, Arranger.Options options, int rowLength) {
		Arrangement arrangement = Arranger.arrange(song, options);
		if (arrangement.chords().isEmpty()) {
			throw new Msg.Invalid(new Msg("nbmusic.error.all_muted", "All tracks are muted"));
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
