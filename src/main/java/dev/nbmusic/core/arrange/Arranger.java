package dev.nbmusic.core.arrange;

import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Note;
import dev.nbmusic.core.model.Song;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Превращает песню в аккорды по редстоун-тикам: квантизация к 0,1 с,
 * выбор инструмента, перенос в диапазон и ограничение полифонии.
 */
public final class Arranger {
	public static final int TICKS_PER_SECOND = 10;

	private Arranger() {
	}

	/**
	 * @param speedPercent  100 — исходный темп, 50 — вдвое медленнее
	 * @param maxPolyphony  сколько нот максимум звучит в один тик
	 * @param transpose     сдвиг мелодических нот в полутонах
	 * @param mutedChannels битовая маска выключенных MIDI-каналов (бит 9 — ударные)
	 */
	public record Options(int speedPercent, int maxPolyphony, int transpose, int mutedChannels) {
		public static final Options DEFAULT = new Options(100, 8, 0, 0);

		public Options(int speedPercent, int maxPolyphony, int transpose) {
			this(speedPercent, maxPolyphony, transpose, 0);
		}

		public Options {
			if (speedPercent <= 0) {
				throw new IllegalArgumentException("speedPercent must be positive");
			}
			if (maxPolyphony <= 0) {
				throw new IllegalArgumentException("maxPolyphony must be positive");
			}
		}
	}

	public record Arrangement(SortedMap<Integer, List<Note>> chords, int inputNotes, int droppedNotes, int shiftedNotes) {
		public int placedNotes() {
			return chords.values().stream().mapToInt(List::size).sum();
		}

		public int maxChord() {
			return chords.values().stream().mapToInt(List::size).max().orElse(0);
		}

		public int lengthTicks() {
			return chords.isEmpty() ? 0 : chords.lastKey() + 1;
		}
	}

	public static Arrangement arrange(Song song, Options options) {
		double ticksPerSecond = TICKS_PER_SECOND * 100.0 / options.speedPercent();
		// тик → (инструмент, ключ) → нота; дубли сливаются по громкости
		TreeMap<Integer, Map<Long, Note>> byTick = new TreeMap<>();
		int shifted = 0;
		for (MidiNote m : song.notes()) {
			if ((options.mutedChannels() >> m.channel() & 1) != 0) {
				continue;
			}
			int tick = (int) Math.round(m.seconds() * ticksPerSecond);
			Note note;
			if (m.isDrum()) {
				InstrumentMapper.Drum d = InstrumentMapper.drum(m.pitch());
				note = new Note(tick, d.instrument(), d.key(), m.velocity());
			} else {
				Fit fit = fit(InstrumentMapper.family(m.program()), m.pitch() + options.transpose());
				if (fit.shifted) {
					shifted++;
				}
				note = new Note(tick, fit.instrument, fit.key, m.velocity());
			}
			long id = (long) note.instrument().ordinal() << 8 | note.key();
			byTick.computeIfAbsent(tick, t -> new LinkedHashMap<>())
					.merge(id, note, (a, b) -> a.velocity() >= b.velocity() ? a : b);
		}

		Comparator<Note> priority = Comparator.comparingInt(Note::velocity).reversed()
				.thenComparing(Comparator.comparingInt(Arranger::absolutePitch).reversed());
		Comparator<Note> order = Comparator.comparingInt((Note n) -> n.instrument().ordinal()).thenComparingInt(Note::key);

		TreeMap<Integer, List<Note>> chords = new TreeMap<>();
		int dropped = 0;
		for (var e : byTick.entrySet()) {
			List<Note> chord = new ArrayList<>(e.getValue().values());
			if (chord.size() > options.maxPolyphony()) {
				chord.sort(priority);
				dropped += chord.size() - options.maxPolyphony();
				chord = new ArrayList<>(chord.subList(0, options.maxPolyphony()));
			}
			chord.sort(order);
			chords.put(e.getKey(), List.copyOf(chord));
		}
		return new Arrangement(chords, song.notes().size(), dropped, shifted);
	}

	private record Fit(Instrument instrument, int key, boolean shifted) {
	}

	/** Первый инструмент семейства, который берёт ноту; иначе — ближайший по диапазону со сдвигом на октавы. */
	static Fit fit(List<Instrument> family, int pitch) {
		for (Instrument inst : family) {
			if (inst.contains(pitch)) {
				return new Fit(inst, pitch - inst.lowestMidi, false);
			}
		}
		Instrument best = family.getFirst();
		int bestDistance = Integer.MAX_VALUE;
		for (Instrument inst : family) {
			int d = pitch < inst.lowestMidi ? inst.lowestMidi - pitch : pitch - (inst.lowestMidi + Instrument.KEYS - 1);
			if (d < bestDistance) {
				bestDistance = d;
				best = inst;
			}
		}
		int p = pitch;
		while (p < best.lowestMidi) {
			p += 12;
		}
		while (p >= best.lowestMidi + Instrument.KEYS) {
			p -= 12;
		}
		return new Fit(best, p - best.lowestMidi, true);
	}

	private static int absolutePitch(Note n) {
		return n.instrument().isPercussion() ? -1 : n.instrument().lowestMidi + n.key();
	}
}
