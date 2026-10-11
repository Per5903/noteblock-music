package dev.nbmusic.core;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Сборщик MIDI-последовательностей для тестов. */
final class TestMidi {
	static final int PPQ = 480;

	final Sequence sequence;
	final Track track;

	TestMidi() throws InvalidMidiDataException {
		sequence = new Sequence(Sequence.PPQ, PPQ);
		track = sequence.createTrack();
	}

	TestMidi tempo(long tick, int bpm) throws InvalidMidiDataException {
		int us = 60_000_000 / bpm;
		byte[] data = {(byte) (us >> 16), (byte) (us >> 8), (byte) us};
		track.add(new MidiEvent(new MetaMessage(0x51, data, 3), tick));
		return this;
	}

	TestMidi program(long tick, int channel, int program) throws InvalidMidiDataException {
		track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0), tick));
		return this;
	}

	TestMidi note(long tick, int channel, int pitch, int velocity) throws InvalidMidiDataException {
		track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, velocity), tick));
		track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), tick + PPQ / 2));
		return this;
	}
}
