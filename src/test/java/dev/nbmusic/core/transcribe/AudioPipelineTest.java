package dev.nbmusic.core.transcribe;

import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.io.AudioDecoder;
import dev.nbmusic.core.io.MidiDecoder;
import dev.nbmusic.core.io.MidiWriter;
import dev.nbmusic.core.io.Resampler;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioPipelineTest {
	/** Мелодия из src/test/resources/audio/melody.mp3 (синтетическая, 44,1 кГц стерео): время, MIDI. */
	static final double[][] MELODY = {{0.40, 60}, {0.80, 64}, {1.20, 67}, {1.60, 72}, {2.40, 65}, {2.40, 69}, {2.40, 72},
			{3.40, 48}, {4.00, 71}, {4.40, 74}, {4.80, 79}};

	static byte[] resource(String name) throws Exception {
		try (InputStream in = AudioPipelineTest.class.getResourceAsStream(name)) {
			return in.readAllBytes();
		}
	}

	@Test
	void decodesMp3() throws Exception {
		AudioDecoder.Audio a = AudioDecoder.decode("mp3", resource("/audio/melody.mp3"));
		assertEquals(44100, a.sampleRate());
		assertEquals(6.0, a.seconds(), 0.1);
		float peak = 0;
		for (float v : a.samples()) {
			peak = Math.max(peak, Math.abs(v));
		}
		assertTrue(peak > 0.2 && peak < 1.0, "пик " + peak);
	}

	@Test
	void decodesWavOfDifferentDepths() throws Exception {
		for (int bits : new int[]{8, 16, 24}) {
			int rate = 32000;
			float[] tone = new float[rate];
			for (int i = 0; i < tone.length; i++) {
				tone[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / rate));
			}
			AudioFormat f = new AudioFormat(bits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED,
					rate, bits, 2, bits / 8 * 2, rate, false);
			int bps = bits / 8;
			byte[] pcm = new byte[tone.length * bps * 2];
			for (int i = 0; i < tone.length; i++) {
				long v = Math.round(tone[i] * ((1L << (bits - 1)) - 1)) + (bits == 8 ? 128 : 0);
				for (int ch = 0; ch < 2; ch++) {
					for (int b = 0; b < bps; b++) {
						pcm[(i * 2 + ch) * bps + b] = (byte) (v >> (8 * b));
					}
				}
			}
			ByteArrayOutputStream wav = new ByteArrayOutputStream();
			AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), f, tone.length), AudioFileFormat.Type.WAVE, wav);
			AudioDecoder.Audio a = AudioDecoder.decode("wav", wav.toByteArray());
			assertEquals(rate, a.sampleRate());
			assertEquals(tone.length, a.samples().length);
			for (int i = 0; i < tone.length; i += 997) {
				assertEquals(tone[i], a.samples()[i], bits == 8 ? 0.02 : 0.001, bits + " бит, отсчёт " + i);
			}
		}
	}

	@Test
	void resamplerKeepsFrequencyAndLevel() {
		int from = 44100;
		float[] in = new float[from * 2];
		for (int i = 0; i < in.length; i++) {
			in[i] = (float) Math.sin(2 * Math.PI * 1000 * i / from);
		}
		float[] out = Resampler.resample(in, from, 22050);
		assertEquals(22050 * 2, out.length, 1);
		int crossings = 0;
		double sq = 0;
		for (int i = 1000; i < out.length - 1000; i++) {
			if (out[i - 1] < 0 && out[i] >= 0) {
				crossings++;
			}
			sq += out[i] * out[i];
		}
		double seconds = (out.length - 2000) / 22050.0;
		assertEquals(1000, crossings / seconds, 5, "частота");
		assertEquals(Math.sqrt(0.5), Math.sqrt(sq / (out.length - 2000)), 0.01, "уровень");
	}

	@Test
	void midiWriterRoundTrip() throws Exception {
		Song song = new Song("t", List.of(new MidiNote(0.5, 0, 0, 60, 90), new MidiNote(1.25, 0, 0, 64, 70),
				new MidiNote(1.25, 9, 0, 38, 100)));
		Song back = MidiDecoder.decode("t", new ByteArrayInputStream(MidiWriter.write(song)));
		assertEquals(song.notes(), back.notes());
	}

	/** Всё вместе: MP3 → моно 22050 Гц → Basic Pitch → ноты; второй раз — из кэша. */
	@Test
	void transcribesMp3Melody(@TempDir Path dir) throws Exception {
		Path mp3 = dir.resolve("melody.mp3");
		Files.write(mp3, resource("/audio/melody.mp3"));
		Path cache = dir.resolve("cache");
		long t0 = System.nanoTime();
		AudioTranscriber.Result r = AudioTranscriber.transcribe(mp3, cache, p -> {
		});
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("MP3 6 s → " + r.song().notes().size() + " нот за " + ms + " мс: " + r.song().notes());
		for (double[] m : MELODY) {
			boolean found = r.song().notes().stream().anyMatch(n -> n.pitch() == (int) m[1] && Math.abs(n.seconds() - m[0]) < 0.08);
			assertTrue(found, "не нашлась нота " + (int) m[1] + " на " + m[0] + " с среди " + r.song().notes());
		}
		// тянущаяся нота не «перезапускается» на атаках других нот
		long repeats = r.song().notes().stream().filter(n -> java.util.Arrays.stream(MELODY).noneMatch(m ->
				(int) m[1] == n.pitch() && Math.abs(n.seconds() - m[0]) < 0.08)).filter(n -> n.velocity() > 50).count();
		assertEquals(0, repeats, "лишние громкие ноты: " + r.song().notes());
		assertTrue(Files.isRegularFile(r.midi()));
		assertTrue(AudioTranscriber.isCached(mp3, cache));

		long t1 = System.nanoTime();
		AudioTranscriber.Result again = AudioTranscriber.transcribe(mp3, cache, p -> {
		});
		assertTrue((System.nanoTime() - t1) / 1_000_000 < 500, "повтор должен браться из кэша");
		assertEquals(r.song().notes(), again.song().notes());

		// и дальше обычный путь до схемы
		Pipeline.Result built = Pipeline.arrange(again.song(), Arranger.Options.DEFAULT, 32);
		assertTrue(built.arrangement().placedNotes() >= MELODY.length);
	}
}
