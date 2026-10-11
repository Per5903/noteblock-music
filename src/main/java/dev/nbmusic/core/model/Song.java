package dev.nbmusic.core.model;

import java.util.List;

/** Песня до аранжировки. Ноты отсортированы по времени. */
public record Song(String title, List<MidiNote> notes) {
	public double durationSeconds() {
		return notes.isEmpty() ? 0 : notes.getLast().seconds();
	}
}
