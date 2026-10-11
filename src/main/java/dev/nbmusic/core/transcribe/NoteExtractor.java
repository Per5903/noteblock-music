package dev.nbmusic.core.transcribe;

import dev.nbmusic.core.model.MidiNote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ноты из вероятностей Basic Pitch.
 *
 * <ol>
 *   <li>Онсеты: максимум из предсказанных онсетов и прироста «звучания» за 1–2 кадра.</li>
 *   <li>Каждый локальный пик онсета выше порога начинает ноту; она длится, пока звучание
 *       на этой клавише не упадёт ниже порога дольше {@code energyTolerance} кадров.
 *       Использованная энергия (и на соседних клавишах) обнуляется.</li>
 *   <li>Из оставшейся энергии жадно добираются ноты без явного онсета: от самого сильного
 *       кадра в обе стороны, пока звучание держится.</li>
 * </ol>
 *
 * <p>Отличие от Basic Pitch: слабый онсет на клавише, которая уже ровно звучит, отбрасывается
 * («перезапуск»). Модель ставит такие онсеты тянущейся ноте в моменты атак других нот,
 * а нотный блок сыграл бы лишний повтор. Настоящий повторный удар проходит: у него сильный
 * онсет или провал/рост звучания.
 */
final class NoteExtractor {
	/**
	 * @param retriggerOnset онсет слабее этого на уже звучащей клавише без роста звучания — перезапуск
	 */
	record Settings(float onsetThreshold, float frameThreshold, int minFrames, int energyTolerance, float retriggerOnset) {
		/** 0,5 / 0,3 / ≈128 мс / 11 кадров — значения по умолчанию у Basic Pitch; плюс фильтр перезапусков. */
		static final Settings DEFAULT = new Settings(0.5f, 0.3f, 11, 11, 0.75f);
	}

	private static final int RETRIGGER_LOOKBACK = 3;
	private static final float RETRIGGER_RISE = 0.1f;

	private NoteExtractor() {
	}

	static List<MidiNote> extract(BasicPitch.Posteriorgram p, Settings s) {
		int frames = p.frames();
		int keys = BasicPitch.KEYS;
		List<MidiNote> notes = new ArrayList<>();
		if (frames < 3) {
			return notes;
		}
		float[][] frame = p.note();
		float[][] onsets = inferOnsets(p.onset(), frame);
		float[][] energy = new float[frames][];
		for (int t = 0; t < frames; t++) {
			energy[t] = frame[t].clone();
		}

		// Пики онсетов (строгий локальный максимум по времени), от поздних к ранним
		List<int[]> peaks = new ArrayList<>();
		for (int t = 1; t < frames - 1; t++) {
			for (int k = 0; k < keys; k++) {
				float v = onsets[t][k];
				if (v >= s.onsetThreshold() && v > onsets[t - 1][k] && v > onsets[t + 1][k] && !isRetrigger(frame, onsets, t, k, s)) {
					peaks.add(new int[]{t, k});
				}
			}
		}
		peaks.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]).reversed());

		for (int[] peak : peaks) {
			int start = peak[0];
			int key = peak[1];
			if (start >= frames - 1) {
				continue;
			}
			int i = start + 1;
			int quiet = 0;
			while (i < frames - 1 && quiet < s.energyTolerance()) {
				quiet = energy[i][key] < s.frameThreshold() ? quiet + 1 : 0;
				i++;
			}
			i -= quiet;
			if (i - start <= s.minFrames()) {
				continue;
			}
			clear(energy, start, i, key);
			notes.add(note(frame, start, i, key));
		}

		// Ноты без явного онсета
		while (true) {
			int bestT = -1;
			int bestK = -1;
			float best = s.frameThreshold();
			for (int t = 0; t < frames; t++) {
				for (int k = 0; k < keys; k++) {
					if (energy[t][k] > best) {
						best = energy[t][k];
						bestT = t;
						bestK = k;
					}
				}
			}
			if (bestT < 0) {
				break;
			}
			energy[bestT][bestK] = 0;
			int i = bestT + 1;
			int quiet = 0;
			while (i < frames - 1 && quiet < s.energyTolerance()) {
				quiet = energy[i][bestK] < s.frameThreshold() ? quiet + 1 : 0;
				clear(energy, i, i + 1, bestK);
				i++;
			}
			int end = i - 1 - quiet;
			i = bestT - 1;
			quiet = 0;
			while (i > 0 && quiet < s.energyTolerance()) {
				quiet = energy[i][bestK] < s.frameThreshold() ? quiet + 1 : 0;
				clear(energy, i, i + 1, bestK);
				i--;
			}
			int start = i + 1 + quiet;
			if (end - start <= s.minFrames()) {
				continue;
			}
			notes.add(note(frame, start, end, bestK));
		}
		notes.sort(Comparator.comparingDouble(MidiNote::seconds).thenComparingInt(MidiNote::pitch));
		return notes;
	}

	/** Клавиша уже ровно звучит, звучание не выросло, онсет слабый — это не новая нота. */
	static boolean isRetrigger(float[][] frame, float[][] onsets, int t, int key, Settings s) {
		if (t < RETRIGGER_LOOKBACK || onsets[t][key] >= s.retriggerOnset()) {
			return false;
		}
		float before = Float.MAX_VALUE;
		for (int i = t - RETRIGGER_LOOKBACK; i < t; i++) {
			before = Math.min(before, frame[i][key]);
		}
		return before >= s.frameThreshold() && frame[t][key] - before < RETRIGGER_RISE;
	}

	private static void clear(float[][] energy, int from, int to, int key) {
		for (int t = from; t < to; t++) {
			energy[t][key] = 0;
			if (key + 1 < BasicPitch.KEYS) {
				energy[t][key + 1] = 0;
			}
			if (key > 0) {
				energy[t][key - 1] = 0;
			}
		}
	}

	private static MidiNote note(float[][] frame, int start, int end, int key) {
		double sum = 0;
		for (int t = start; t < end; t++) {
			sum += frame[t][key];
		}
		double amplitude = sum / Math.max(1, end - start);
		int velocity = (int) Math.clamp(Math.round(amplitude * 127), 1, 127);
		return new MidiNote(BasicPitch.frameTime(start), 0, 0, key + BasicPitch.MIDI_OFFSET, velocity);
	}

	/** Онсеты = max(предсказанные, прирост звучания за 1–2 кадра, приведённый к их масштабу). */
	static float[][] inferOnsets(float[][] onsets, float[][] frames) {
		int n = frames.length;
		int keys = BasicPitch.KEYS;
		float[][] diff = new float[n][keys];
		float maxDiff = 0;
		float maxOnset = 0;
		for (int t = 0; t < n; t++) {
			for (int k = 0; k < keys; k++) {
				float d1 = frames[t][k] - (t >= 1 ? frames[t - 1][k] : 0);
				float d2 = frames[t][k] - (t >= 2 ? frames[t - 2][k] : 0);
				float d = t < 2 ? 0 : Math.max(0, Math.min(d1, d2));
				diff[t][k] = d;
				maxDiff = Math.max(maxDiff, d);
				maxOnset = Math.max(maxOnset, onsets[t][k]);
			}
		}
		float[][] out = new float[n][keys];
		float scale = maxDiff > 0 ? maxOnset / maxDiff : 0;
		for (int t = 0; t < n; t++) {
			for (int k = 0; k < keys; k++) {
				out[t][k] = Math.max(onsets[t][k], diff[t][k] * scale);
			}
		}
		return out;
	}
}
