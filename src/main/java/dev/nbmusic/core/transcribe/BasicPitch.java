package dev.nbmusic.core.transcribe;

import dev.nbmusic.core.model.MidiNote;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.DoubleConsumer;

/**
 * Распознавание нот из аудио моделью Basic Pitch (Spotify, Apache-2.0,
 * https://github.com/spotify/basic-pitch). Модель исполняется своим интерпретатором ONNX,
 * поэтому нативные библиотеки не нужны.
 *
 * <p>Аудио — моно 22050 Гц. Оно режется на окна по 43844 отсчёта с перекрытием 30 кадров;
 * от каждого окна берутся центральные 142 кадра (кадр = 256 отсчётов ≈ 11,6 мс).
 */
public final class BasicPitch {
	public static final int SAMPLE_RATE = 22050;
	static final int HOP = 256;
	static final int WINDOW = 43844;
	static final int FRAMES_PER_WINDOW = 172;
	static final int OVERLAP_FRAMES = 30;
	static final int OVERLAP = OVERLAP_FRAMES * HOP;
	static final int WINDOW_HOP = WINDOW - OVERLAP;
	static final int KEPT_FRAMES = FRAMES_PER_WINDOW - OVERLAP_FRAMES;
	static final int KEYS = 88;
	static final int MIDI_OFFSET = 21;

	private static final String MODEL = "/nbmusic/basic-pitch/nmp.onnx";
	private static final String OUT_NOTE = "StatefulPartitionedCall:1";
	private static final String OUT_ONSET = "StatefulPartitionedCall:2";

	private static volatile OnnxModel cachedModel;

	private final OnnxInterpreter interpreter;
	private final String input;

	private BasicPitch(OnnxModel model, ForkJoinPool pool) {
		this.interpreter = new OnnxInterpreter(model, pool);
		this.input = model.inputs.getFirst();
	}

	/** @param pool потоки для свёрток */
	public static BasicPitch load(ForkJoinPool pool) throws IOException {
		OnnxModel m = cachedModel;
		if (m == null) {
			try (InputStream in = BasicPitch.class.getResourceAsStream(MODEL)) {
				if (in == null) {
					throw new IOException("model resource missing: " + MODEL);
				}
				m = OnnxModel.parse(in.readAllBytes());
			}
			cachedModel = m;
		}
		return new BasicPitch(m, pool);
	}

	/** Вероятности по кадрам: [кадр][клавиша 0..87], клавиша 0 = A0 (MIDI 21). */
	public record Posteriorgram(float[][] note, float[][] onset) {
		public int frames() {
			return note.length;
		}
	}

	/** Время начала кадра склеенной posteriorgram-ы с учётом перекрытия окон, в секундах. */
	public static double frameTime(int frame) {
		int window = frame / KEPT_FRAMES;
		int inWindow = frame % KEPT_FRAMES + OVERLAP_FRAMES / 2;
		return (window * (double) WINDOW_HOP + inWindow * HOP - OVERLAP / 2.0) / SAMPLE_RATE;
	}

	/**
	 * @param audio    моно, 22050 Гц, примерно в [-1, 1]
	 * @param progress доля выполненного 0..1
	 */
	public Posteriorgram predict(float[] audio, DoubleConsumer progress) {
		int padded = audio.length + OVERLAP / 2;
		int windows = Math.max(1, (padded + WINDOW_HOP - 1) / WINDOW_HOP);
		int totalFrames = audio.length / HOP;
		float[][] note = new float[windows * KEPT_FRAMES][];
		float[][] onset = new float[windows * KEPT_FRAMES][];
		for (int w = 0; w < windows; w++) {
			float[] chunk = new float[WINDOW];
			int start = w * WINDOW_HOP - OVERLAP / 2; // позиция окна в исходном аудио
			for (int i = 0; i < WINDOW; i++) {
				int s = start + i;
				if (s >= 0 && s < audio.length) {
					chunk[i] = audio[s];
				}
			}
			Map<String, Tensor> out = interpreter.run(
					Map.of(input, Tensor.floats(new int[]{1, WINDOW, 1}, chunk)), Set.of(OUT_NOTE, OUT_ONSET));
			Tensor n = out.get(OUT_NOTE);
			Tensor o = out.get(OUT_ONSET);
			for (int j = 0; j < KEPT_FRAMES; j++) {
				int src = (j + OVERLAP_FRAMES / 2) * KEYS;
				note[w * KEPT_FRAMES + j] = java.util.Arrays.copyOfRange(n.f, src, src + KEYS);
				onset[w * KEPT_FRAMES + j] = java.util.Arrays.copyOfRange(o.f, src, src + KEYS);
			}
			progress.accept((w + 1) / (double) windows);
		}
		int frames = Math.min(totalFrames, note.length);
		return new Posteriorgram(java.util.Arrays.copyOf(note, frames), java.util.Arrays.copyOf(onset, frames));
	}

	public List<MidiNote> transcribe(float[] audio, DoubleConsumer progress) {
		return NoteExtractor.extract(predict(audio, progress), NoteExtractor.Settings.DEFAULT);
	}
}
