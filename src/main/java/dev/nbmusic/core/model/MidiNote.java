package dev.nbmusic.core.model;

/**
 * Нота, как она пришла из файла: абсолютное время, канал, программа General MIDI.
 *
 * @param instrument если задан — инструмент нотного блока выбран заранее (импорт .nbs) и
 *                   аранжировщик его не меняет; {@code pitch} тогда = {@code lowestMidi + ключ},
 *                   для ударных — {@code 54 + ключ}
 */
public record MidiNote(double seconds, int channel, int program, int pitch, int velocity, Instrument instrument) {
	public static final int DRUM_CHANNEL = 9;
	/** Высота ключа 0 у ударных в {@link #pitch} (F#3, как у арфы). */
	public static final int PERCUSSION_BASE = 54;

	public MidiNote(double seconds, int channel, int program, int pitch, int velocity) {
		this(seconds, channel, program, pitch, velocity, null);
	}

	public boolean isDrum() {
		return instrument == null ? channel == DRUM_CHANNEL : instrument.isPercussion();
	}
}
