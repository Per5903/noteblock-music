package dev.nbmusic.core.transcribe;

import dev.nbmusic.core.Msg;
import dev.nbmusic.core.io.AudioDecoder;
import dev.nbmusic.core.io.MidiDecoder;
import dev.nbmusic.core.io.MidiWriter;
import dev.nbmusic.core.io.Resampler;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.DoubleConsumer;

/**
 * Аудиофайл → песня. Результат кэшируется MIDI-файлом по хэшу содержимого, поэтому
 * повторная загрузка того же трека мгновенная, а на сервер уходит уже готовый MIDI.
 */
public final class AudioTranscriber {
	/** Меняется при смене модели или алгоритма — старый кэш тогда не используется. */
	private static final String VERSION = "bp-icassp2022-v1";

	/** Потоки для свёрток: все ядра, кроме одного, чтобы игра не замирала. */
	private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
			pool -> {
				var t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
				t.setName("nbmusic-transcribe-" + t.getPoolIndex());
				t.setDaemon(true);
				return t;
			}, null, false);

	private AudioTranscriber() {
	}

	public static Path cacheFile(Path cacheDir, byte[] audio) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update(VERSION.getBytes());
			return cacheDir.resolve(HexFormat.of().formatHex(sha.digest(audio)).substring(0, 32) + ".mid");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static boolean isCached(Path file, Path cacheDir) {
		try {
			return Files.isRegularFile(cacheFile(cacheDir, Files.readAllBytes(file)));
		} catch (IOException e) {
			return false;
		}
	}

	/** Результат: песня и MIDI-файл в кэше, который можно отправить на сервер или открыть в редакторе. */
	public record Result(Song song, Path midi) {
	}

	/**
	 * Долгая операция (≈0,15 с на секунду звука на 8 ядрах) — вызывать не из игрового потока.
	 *
	 * @param progress 0..1
	 */
	public static Result transcribe(Path file, Path cacheDir, DoubleConsumer progress) throws IOException {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String title = dot < 0 ? name : name.substring(0, dot);
		byte[] bytes = Files.readAllBytes(file);
		Path cached = cacheFile(cacheDir, bytes);
		if (Files.isRegularFile(cached)) {
			progress.accept(1);
			try (var in = Files.newInputStream(cached)) {
				return new Result(MidiDecoder.decode(title, in), cached);
			}
		}
		progress.accept(0);
		AudioDecoder.Audio audio = AudioDecoder.decode(name.substring(dot + 1), bytes);
		float[] mono = Resampler.resample(audio.samples(), audio.sampleRate(), BasicPitch.SAMPLE_RATE);
		progress.accept(0.05);
		List<MidiNote> notes = BasicPitch.load(POOL).transcribe(mono, p -> progress.accept(0.05 + 0.93 * p));
		if (notes.isEmpty()) {
			throw new Msg.Failure(new Msg("nbmusic.error.no_notes_heard", "Could not hear a single note"));
		}
		Song song = new Song(title, notes);
		byte[] midi = MidiWriter.write(song);
		Files.createDirectories(cacheDir);
		Path tmp = Files.createTempFile(cacheDir, "transcribe", ".tmp");
		Files.write(tmp, midi);
		Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		progress.accept(1);
		// читаем обратно из MIDI: так песня точно совпадёт с той, что уйдёт на сервер
		return new Result(MidiDecoder.decode(title, new ByteArrayInputStream(midi)), cached);
	}
}
