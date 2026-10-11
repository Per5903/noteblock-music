package dev.nbmusic.core.io;

import dev.nbmusic.core.Msg;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Читает Standard MIDI File (форматы 0/1) в {@link Song}. Учитывает смену темпа. */
public final class MidiDecoder {
	private static final int META_TEMPO = 0x51;
	private static final int DEFAULT_US_PER_QUARTER = 500_000;

	private MidiDecoder() {
	}

	public static Song decode(String title, InputStream in) throws IOException {
		Sequence seq;
		try {
			seq = MidiSystem.getSequence(in);
		} catch (InvalidMidiDataException e) {
			throw new Msg.Failure(new Msg("nbmusic.error.not_midi", "Not a MIDI file: %s", String.valueOf(e.getMessage())), e);
		}
		return decode(title, seq);
	}

	public static Song decode(String title, Sequence seq) {
		List<MidiEvent> events = new ArrayList<>();
		for (Track track : seq.getTracks()) {
			for (int i = 0; i < track.size(); i++) {
				events.add(track.get(i));
			}
		}
		// Смена программы в тот же тик должна применяться раньше нот
		events.sort(Comparator.comparingLong(MidiEvent::getTick)
				.thenComparingInt(e -> isProgramChange(e) ? 0 : 1));

		TickClock clock = new TickClock(seq, events);
		int[] programs = new int[16];
		List<MidiNote> notes = new ArrayList<>();
		for (MidiEvent event : events) {
			if (!(event.getMessage() instanceof ShortMessage msg)) {
				continue;
			}
			int channel = msg.getChannel();
			switch (msg.getCommand()) {
				case ShortMessage.PROGRAM_CHANGE -> programs[channel] = msg.getData1();
				case ShortMessage.NOTE_ON -> {
					if (msg.getData2() > 0) {
						notes.add(new MidiNote(clock.seconds(event.getTick()), channel, programs[channel], msg.getData1(), msg.getData2()));
					}
				}
				default -> {
				}
			}
		}
		return new Song(title, notes);
	}

	private static boolean isProgramChange(MidiEvent e) {
		return e.getMessage() instanceof ShortMessage m && m.getCommand() == ShortMessage.PROGRAM_CHANGE;
	}

	/** Перевод MIDI-тиков в секунды по карте темпов. */
	private static final class TickClock {
		private final float divisionType;
		private final int resolution;
		/** тик смены темпа → {секунды на этом тике, микросекунд на четверть} */
		private final TreeMap<Long, double[]> tempo = new TreeMap<>();

		TickClock(Sequence seq, List<MidiEvent> sortedEvents) {
			this.divisionType = seq.getDivisionType();
			this.resolution = seq.getResolution();
			long lastTick = 0;
			double lastSeconds = 0;
			double usPerQuarter = DEFAULT_US_PER_QUARTER;
			tempo.put(0L, new double[]{0, usPerQuarter});
			if (divisionType != Sequence.PPQ) {
				return;
			}
			for (MidiEvent e : sortedEvents) {
				if (e.getMessage() instanceof MetaMessage meta && meta.getType() == META_TEMPO && meta.getData().length == 3) {
					byte[] d = meta.getData();
					int newTempo = (d[0] & 0xFF) << 16 | (d[1] & 0xFF) << 8 | (d[2] & 0xFF);
					lastSeconds += (e.getTick() - lastTick) * usPerQuarter / 1e6 / resolution;
					lastTick = e.getTick();
					usPerQuarter = newTempo;
					tempo.put(lastTick, new double[]{lastSeconds, usPerQuarter});
				}
			}
		}

		double seconds(long tick) {
			if (divisionType != Sequence.PPQ) {
				// SMPTE: divisionType = кадров в секунду, resolution = тиков на кадр
				return tick / (divisionType * resolution);
			}
			var seg = tempo.floorEntry(tick);
			return seg.getValue()[0] + (tick - seg.getKey()) * seg.getValue()[1] / 1e6 / resolution;
		}
	}
}
