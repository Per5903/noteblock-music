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
	public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mid", "midi");

	private Pipeline() {
	}

	public record Result(Song song, Arrangement arrangement, Layout layout) {
	}

	public static Result run(Path file, Arranger.Options options) throws IOException {
		String fileName = file.getFileName().toString();
		String ext = extension(fileName);
		if (!SUPPORTED_EXTENSIONS.contains(ext)) {
			throw new IOException("Формат ." + ext + " пока не поддерживается, нужен MIDI (.mid)");
		}
		Song song;
		try (InputStream in = Files.newInputStream(file)) {
			song = MidiDecoder.decode(baseName(fileName), in);
		}
		if (song.notes().isEmpty()) {
			throw new IOException("В файле нет нот");
		}
		Arrangement arrangement = Arranger.arrange(song, options);
		return new Result(song, arrangement, LayoutGenerator.generate(arrangement));
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
