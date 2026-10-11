package dev.nbmusic.core.model;

/** Нота, как она пришла из файла: абсолютное время, канал, программа General MIDI. */
public record MidiNote(double seconds, int channel, int program, int pitch, int velocity) {
	public static final int DRUM_CHANNEL = 9;

	public boolean isDrum() {
		return channel == DRUM_CHANNEL;
	}
}
