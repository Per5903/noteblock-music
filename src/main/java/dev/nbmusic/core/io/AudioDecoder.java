package dev.nbmusic.core.io;

import dev.nbmusic.core.Msg;
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
			default -> throw new Msg.Failure(new Msg("nbmusic.error.audio_format", "Unknown audio format: .%s", extension));
		};
		if (audio.samples.length == 0) {
			throw new Msg.Failure(new Msg("nbmusic.error.no_sound", "The file has no sound"));
		}
		if (audio.seconds() > MAX_SECONDS) {
			throw new Msg.Failure(new Msg("nbmusic.error.too_long", "The track is too long (max %s min)", MAX_SECONDS / 60));
		}
		return audio;
	}

	private static Audio wav(byte[] data) throws IOException {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data)))) {
			return pcm(in.readAllBytes(), in.getFormat());
		} catch (UnsupportedAudioFileException e) {
			throw new Msg.Failure(new Msg("nbmusic.error.wav", "WAV not recognized: %s", String.valueOf(e.getMessage())), e);
		}
	}

	private static Audio mp3(byte[] data) throws IOException {
		// javamp3 не пропускает теги: в обложке из ID3 он находит ложный «кадр» и падает.
		// Поэтому отдаём ему только MPEG-кадры: от первого настоящего кадра Layer III до хвостовых тегов.
		int from = Mp3Frames.firstFrame(data, Mp3Frames.skipId3v2(data));
		if (from < 0) {
			throw new Msg.Failure(new Msg("nbmusic.error.mp3_no_frames", "MP3 not recognized: no MPEG Layer III frames found"));
		}
		int to = Mp3Frames.audioEnd(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AudioFormat format;
		try (Sound sound = new Sound(new BufferedInputStream(new ByteArrayInputStream(data, from, Math.max(0, to - from))))) {
			format = sound.getAudioFormat();
			// decodeFullyInto в javamp3 1.0.1 останавливается после первого буфера — читаем сами
			byte[] buf = new byte[1 << 16];
			long limit = (long) MAX_SECONDS * sound.getSamplingFrequency() * 4 + buf.length;
			long oneSecond = (long) sound.getSamplingFrequency() * 4;
			try {
				for (int n; (n = sound.read(buf)) > 0; ) {
					out.write(buf, 0, n);
					if (out.size() > limit) {
						throw new Msg.Failure(new Msg("nbmusic.error.too_long", "The track is too long (max %s min)", MAX_SECONDS / 60));
					}
				}
			} catch (RuntimeException e) {
				// повреждённый кадр в конце файла — оставляем то, что уже раскодировано
				if (out.size() < oneSecond) {
					throw e;
				}
			}
		} catch (RuntimeException e) {
			throw new Msg.Failure(new Msg("nbmusic.error.mp3", "MP3 not recognized: %s", e.toString()), e);
		}
		return pcm(out.toByteArray(), format);
	}

	/** OGG Vorbis через stb_vorbis из LWJGL — он есть в клиенте Minecraft, но не на выделенном сервере. */
	private static Audio ogg(byte[] data) throws IOException {
		try {
			Class.forName("org.lwjgl.stb.STBVorbis", false, AudioDecoder.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new Msg.Failure(new Msg("nbmusic.error.ogg_client_only", "OGG can only be decoded in the game client — use the mod window or MP3/WAV"), e);
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
			throw new Msg.Failure(new Msg("nbmusic.error.wav_encoding", "Encoding %s is not supported, save the WAV as PCM", enc.toString()));
		}
		if (bytesPerSample < 1 || bytesPerSample > 4 || (isFloat && bits != 32)) {
			throw new Msg.Failure(new Msg("nbmusic.error.bit_depth", "%s-bit audio is not supported", bits));
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
