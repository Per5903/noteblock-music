package dev.nbmusic.core.transcribe;

import dev.nbmusic.core.model.MidiNote;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

class BasicPitchTest {
	private static BasicPitch model;

	@BeforeAll
	static void load() throws Exception {
		model = BasicPitch.load(ForkJoinPool.commonPool());
	}

	/** Тон «как у фортепиано»: основной тон + гармоники, быстрая атака, экспоненциальное затухание. */
	static void addTone(float[] audio, double startSec, double lengthSec, int midi, double gain) {
		double f0 = 440 * Math.pow(2, (midi - 69) / 12.0);
		int from = (int) (startSec * BasicPitch.SAMPLE_RATE);
		int len = (int) (lengthSec * BasicPitch.SAMPLE_RATE);
		for (int i = 0; i < len && from + i < audio.length; i++) {
			double t = i / (double) BasicPitch.SAMPLE_RATE;
			double env = Math.min(1, t / 0.005) * Math.exp(-t * 2.5);
			double v = 0;
			for (int h = 1; h <= 6; h++) {
				if (f0 * h < BasicPitch.SAMPLE_RATE / 2.0) {
					v += Math.sin(2 * Math.PI * f0 * h * t) / (h * h);
				}
			}
			audio[from + i] += (float) (gain * env * v);
		}
	}

	record Expected(double time, int pitch) {
	}

	@Test
	void recognisesSyntheticPianoNotes() {
		float[] audio = new float[BasicPitch.SAMPLE_RATE * 6];
		List<Expected> expected = new ArrayList<>();
		int[][] melody = {{60, 50}, {64, 100}, {67, 150}, {72, 250}, {76, 250}, {45, 350}, {57, 450}};
		for (int[] m : melody) {
			addTone(audio, m[1] / 100.0, 1.0, m[0], 0.3);
			expected.add(new Expected(m[1] / 100.0, m[0]));
		}
		long t0 = System.nanoTime();
		List<MidiNote> notes = model.transcribe(audio, p -> {
		});
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("transcribed 6 s in " + ms + " ms: " + notes);
		for (Expected e : expected) {
			boolean found = notes.stream().anyMatch(n -> n.pitch() == e.pitch() && Math.abs(n.seconds() - e.time()) < 0.08);
			assertTrue(found, "не нашлась нота " + e + " среди " + notes);
		}
		// лишних нот (не из мелодии и не октавные гармоники) почти нет
		long stray = notes.stream().filter(n -> expected.stream().noneMatch(e ->
				Math.abs(n.seconds() - e.time()) < 0.15 && (n.pitch() - e.pitch()) % 12 == 0)).count();
		assertTrue(stray <= 2, "лишние ноты: " + stray + " в " + notes);
	}

	/** NBMUSIC_DUMP=<папка>: сохранить вход и выходы одного окна для сверки с эталоном на numpy. */
	@Test
	void dumpWindowForReference() throws Exception {
		String dir = System.getenv("NBMUSIC_DUMP");
		org.junit.jupiter.api.Assumptions.assumeTrue(dir != null);
		float[] chunk = new float[BasicPitch.WINDOW];
		float[] audio = new float[BasicPitch.WINDOW];
		addTone(audio, 0.3, 1.0, 60, 0.3);
		addTone(audio, 0.9, 0.8, 67, 0.2);
		addTone(audio, 1.2, 0.7, 43, 0.3);
		System.arraycopy(audio, 0, chunk, 0, chunk.length);
		OnnxModel m;
		try (var in = BasicPitch.class.getResourceAsStream("/nbmusic/basic-pitch/nmp.onnx")) {
			m = OnnxModel.parse(in.readAllBytes());
		}
		var out = new OnnxInterpreter(m, ForkJoinPool.commonPool()).run(
				java.util.Map.of(m.inputs.getFirst(), Tensor.floats(new int[]{1, BasicPitch.WINDOW, 1}, chunk)),
				java.util.Set.copyOf(m.outputs));
		write(java.nio.file.Path.of(dir, "input.f32"), chunk);
		for (int i = 0; i < m.outputs.size(); i++) {
			Tensor t = out.get(m.outputs.get(i));
			write(java.nio.file.Path.of(dir, m.outputs.get(i).replace(':', '_') + ".f32"), t.f);
			System.out.println(m.outputs.get(i) + " " + t);
		}
	}

	private static void write(java.nio.file.Path p, float[] data) throws Exception {
		var buf = java.nio.ByteBuffer.allocate(data.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		buf.asFloatBuffer().put(data);
		java.nio.file.Files.write(p, buf.array());
	}

	@Test
	void frameTimesAreContinuousAcrossWindows() {
		for (int f = 1; f < 2000; f++) {
			double dt = BasicPitch.frameTime(f) - BasicPitch.frameTime(f - 1);
			// на стыке окон шаг меньше: окно сдвигается на 141,27 кадра, а берём 142
			assertTrue(dt > 0.002 && dt < 0.0117, "кадр " + f + " шаг " + dt);
		}
		assertEquals(0.0, BasicPitch.frameTime(0), 1e-9);
	}
}
