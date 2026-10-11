package dev.nbmusic.core.io;

import fr.delthas.javamp3.Sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/** Декодирование WAV / MP3 / OGG Vorbis в моно float. */
public final class AudioDecoder {
	public static final Set<String> EXTENSIONS = Set.of("wav", "mp3", "ogg");
	/** Дольше не распознаём: память и время растут линейно. */
	public static final int MAX_SECONDS = 15 * 60;

	private AudioDecoder() {
	}

	/** Моно-сигнал примерно в [-1, 1]. */
	public record Audio(float[] samples, int sampleRate) {
		public double seconds() {
			return samples.length / (double) sampleRate;
		}
	}

	public static boolean isAudio(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 && EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	public static Audio decode(String extension, byte[] data) throws IOException {
		Audio audio = switch (extension.toLowerCase(Locale.ROOT)) {
			case "wav" -> wav(data);
			case "mp3" -> mp3(data);
			case "ogg" -> ogg(data);
			default -> throw new IOException("Неизвестный формат аудио: ." + extension);
		};
		if (audio.samples.length == 0) {
			throw new IOException("В файле нет звука");
		}
		if (audio.seconds() > MAX_SECONDS) {
			throw new IOException("Слишком длинный трек: " + (int) audio.seconds() / 60 + " мин (максимум " + MAX_SECONDS / 60 + ")");
		}
		return audio;
	}

	private static Audio wav(byte[] data) throws IOException {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data)))) {
			return pcm(in.readAllBytes(), in.getFormat());
		} catch (UnsupportedAudioFileException e) {
			throw new IOException("WAV не распознан: " + e.getMessage(), e);
		}
	}

	private static Audio mp3(byte[] data) throws IOException {
		try (Sound sound = new Sound(new BufferedInputStream(new ByteArrayInputStream(data)))) {
			// decodeFullyInto в javamp3 1.0.1 останавливается после первого буфера — читаем сами
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[1 << 16];
			long limit = (long) MAX_SECONDS * sound.getSamplingFrequency() * 4 + buf.length;
			for (int n; (n = sound.read(buf)) > 0; ) {
				out.write(buf, 0, n);
				if (out.size() > limit) {
					throw new IOException("Слишком длинный трек (максимум " + MAX_SECONDS / 60 + " мин)");
				}
			}
			return pcm(out.toByteArray(), sound.getAudioFormat());
		} catch (RuntimeException e) {
			throw new IOException("MP3 не распознан: " + e.getMessage(), e);
		}
	}

	/** OGG Vorbis через stb_vorbis из LWJGL — он есть в клиенте Minecraft, но не на выделенном сервере. */
	private static Audio ogg(byte[] data) throws IOException {
		try {
			Class.forName("org.lwjgl.stb.STBVorbis", false, AudioDecoder.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IOException("OGG распознаётся только в клиенте игры — используй окно мода или MP3/WAV", e);
		}
		return OggDecoder.decode(data);
	}

	/** Целочисленный или float PCM любой разрядности → моно float. */
	static Audio pcm(byte[] bytes, AudioFormat f) throws IOException {
		int bits = f.getSampleSizeInBits();
		int bytesPerSample = (bits + 7) / 8;
		int channels = Math.max(1, f.getChannels());
		AudioFormat.Encoding enc = f.getEncoding();
		boolean isFloat = enc == AudioFormat.Encoding.PCM_FLOAT;
		boolean signed = enc == AudioFormat.Encoding.PCM_SIGNED;
		if (!isFloat && !signed && enc != AudioFormat.Encoding.PCM_UNSIGNED) {
			throw new IOException("Кодировка " + enc + " не поддерживается, сохрани WAV как PCM");
		}
		if (bytesPerSample < 1 || bytesPerSample > 4 || (isFloat && bits != 32)) {
			throw new IOException(bits + "-битный звук не поддерживается");
		}
		int frame = bytesPerSample * channels;
		int frames = bytes.length / frame;
		float[] mono = new float[frames];
		double scale = 1.0 / (1L << (bits - 1));
		boolean big = f.isBigEndian();
		for (int i = 0; i < frames; i++) {
			double sum = 0;
			for (int c = 0; c < channels; c++) {
				int off = i * frame + c * bytesPerSample;
				int v = 0;
				for (int b = 0; b < bytesPerSample; b++) {
					int by = bytes[off + (big ? b : bytesPerSample - 1 - b)] & 0xFF;
					v = v << 8 | by;
				}
				if (isFloat) {
					sum += Float.intBitsToFloat(v);
				} else if (signed) {
					int shift = 32 - bits;
					sum += (v << shift >> shift) * scale;
				} else {
					sum += (v - (1L << (bits - 1))) * scale;
				}
			}
			mono[i] = (float) (sum / channels);
		}
		return new Audio(mono, Math.round(f.getSampleRate()));
	}
}
