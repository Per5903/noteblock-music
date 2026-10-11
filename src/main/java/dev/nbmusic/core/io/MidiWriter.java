package dev.nbmusic.core.io;

import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Песня → MIDI-файл (120 BPM, 480 тиков на четверть: 1 с = 960 тиков). */
public final class MidiWriter {
	private static final int PPQ = 480;
	private static final int TICKS_PER_SECOND = 960;

	private MidiWriter() {
	}

	public static byte[] write(Song song) throws IOException {
		try {
			Sequence seq = new Sequence(Sequence.PPQ, PPQ);
			Track track = seq.createTrack();
			int[] programs = new int[16];
			java.util.Arrays.fill(programs, -1);
			for (MidiNote n : song.notes()) {
				long tick = Math.round(n.seconds() * TICKS_PER_SECOND);
				if (programs[n.channel()] != n.program() && !n.isDrum()) {
					track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, n.channel(), n.program(), 0), tick));
					programs[n.channel()] = n.program();
				}
				track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, n.channel(), n.pitch(), n.velocity()), tick));
				track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, n.channel(), n.pitch(), 0), tick + TICKS_PER_SECOND / 4));
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			MidiSystem.write(seq, 0, out);
			return out.toByteArray();
		} catch (InvalidMidiDataException e) {
			throw new IOException(e);
		}
	}
}
