package dev.nbmusic.core.model;

/** Нота нотного блока: редстоун-тик (0,1 с), инструмент и ключ 0..24. */
public record Note(int tick, Instrument instrument, int key, int velocity) {
	public Note {
		if (key < 0 || key >= Instrument.KEYS) {
			throw new IllegalArgumentException("key out of range: " + key);
		}
	}
}
