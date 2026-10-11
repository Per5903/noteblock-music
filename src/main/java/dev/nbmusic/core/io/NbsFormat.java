package dev.nbmusic.core.io;

import dev.nbmusic.core.Msg;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Note;
import dev.nbmusic.core.model.Song;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Note Block Studio (.nbs): чтение старого формата и версий 1–5, запись версии 5.
 * Номера инструментов NBS совпадают с порядком {@link Instrument}; ключ 33 — нижняя нота
 * нотного блока (ключ 0 у нас), 57 — верхняя (24). Все числа little-endian.
 */
public final class NbsFormat {
	static final int KEY_OFFSET = 33;
	private static final int WRITE_VERSION = 5;

	private NbsFormat() {
	}

	public static Song read(String title, byte[] data) throws IOException {
		try {
			return parse(title, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
		} catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
			throw new Msg.Failure(new Msg("nbmusic.error.nbs_corrupt", "Corrupted .nbs file"), e);
		}
	}

	private static Song parse(String fallbackTitle, ByteBuffer b) throws IOException {
		int first = b.getShort() & 0xFFFF;
		int version = 0;
		if (first == 0) {
			version = b.get() & 0xFF;
			b.get(); // число ванильных инструментов
			if (version >= 3) {
				b.getShort(); // длина песни
			}
		}
		if (version > WRITE_VERSION) {
			throw new Msg.Failure(new Msg("nbmusic.error.nbs_version", ".nbs version %s is newer than supported (%s)", version, WRITE_VERSION));
		}
		int layerCount = b.getShort() & 0xFFFF;
		String name = string(b);
		string(b); // автор
		string(b); // автор оригинала
		string(b); // описание
		double ticksPerSecond = (b.getShort() & 0xFFFF) / 100.0;
		if (ticksPerSecond <= 0) {
			ticksPerSecond = 10;
		}
		b.get(); // автосохранение
		b.get(); // период автосохранения
		b.get(); // размер такта
		b.getInt(); // минут в работе
		b.getInt(); // левых кликов
		b.getInt(); // правых кликов
		b.getInt(); // блоков добавлено
		b.getInt(); // блоков удалено
		string(b); // исходный MIDI/схематика
		if (version >= 4) {
			b.get(); // повтор вкл/выкл
			b.get(); // число повторов
			b.getShort(); // тик начала повтора
		}

		record Raw(int tick, int layer, int instrument, int key, int velocity) {
		}
		List<Raw> raw = new ArrayList<>();
		int tick = -1;
		for (int jump; (jump = b.getShort() & 0xFFFF) != 0; ) {
			tick += jump;
			int layer = -1;
			for (int lj; (lj = b.getShort() & 0xFFFF) != 0; ) {
				layer += lj;
				int instrument = b.get() & 0xFF;
				int key = b.get() & 0xFF;
				int velocity = 100;
				if (version >= 4) {
					velocity = b.get() & 0xFF;
					b.get(); // панорама
					b.getShort(); // тонкая подстройка, центы
				}
				raw.add(new Raw(tick, layer, instrument, key, velocity));
			}
		}

		// громкость слоёв (если секция есть)
		int[] layerVolume = new int[Math.max(layerCount, 1)];
		java.util.Arrays.fill(layerVolume, 100);
		try {
			for (int i = 0; i < layerCount; i++) {
				string(b);
				if (version >= 4) {
					b.get(); // замок
				}
				layerVolume[i] = b.get() & 0xFF;
				if (version >= 2) {
					b.get(); // стерео
				}
			}
		} catch (java.nio.BufferUnderflowException ignored) {
			// у части файлов секции слоёв нет — громкость 100
		}

		Instrument[] instruments = Instrument.values();
		List<MidiNote> notes = new ArrayList<>(raw.size());
		for (Raw r : raw) {
			// пользовательские инструменты (16+) играем арфой
			Instrument inst = r.instrument < instruments.length ? instruments[r.instrument] : Instrument.HARP;
			int key = r.key - KEY_OFFSET;
			int pitch = inst.isPercussion() ? MidiNote.PERCUSSION_BASE + key : inst.lowestMidi + key;
			int volume = r.layer < layerVolume.length ? layerVolume[r.layer] : 100;
			int velocity = (int) Math.clamp(Math.round(r.velocity * volume / 100.0 * 127 / 100), 1, 127);
			notes.add(new MidiNote(r.tick / ticksPerSecond, inst.ordinal(), 0, pitch, velocity, inst));
		}
		notes.sort(java.util.Comparator.comparingDouble(MidiNote::seconds));
		return new Song(name.isBlank() ? fallbackTitle : name, notes);
	}

	/** Аранжировка (уже под нотные блоки, 10 тиков в секунду) → .nbs версии 5. */
	public static byte[] write(Arrangement a, String title, String author) {
		Out o = new Out();
		int layers = Math.max(1, a.maxChord());
		o.shortLE(0);
		o.byteLE(WRITE_VERSION);
		o.byteLE(Instrument.values().length);
		o.shortLE(Math.min(0xFFFF, a.lengthTicks()));
		o.shortLE(layers);
		o.string(title);
		o.string(author);
		o.string("");
		o.string("NoteBlock Music");
		o.shortLE(Arranger.TICKS_PER_SECOND * 100);
		o.byteLE(0);
		o.byteLE(10);
		o.byteLE(4);
		o.intLE(0);
		o.intLE(0);
		o.intLE(0);
		o.intLE(a.placedNotes());
		o.intLE(0);
		o.string("");
		o.byteLE(0);
		o.byteLE(0);
		o.shortLE(0);

		int prevTick = -1;
		for (var e : a.chords().entrySet()) {
			o.shortLE(e.getKey() - prevTick);
			prevTick = e.getKey();
			int prevLayer = -1;
			List<Note> chord = e.getValue();
			for (int layer = 0; layer < chord.size(); layer++) {
				Note n = chord.get(layer);
				o.shortLE(layer - prevLayer);
				prevLayer = layer;
				o.byteLE(n.instrument().ordinal());
				o.byteLE(KEY_OFFSET + n.key());
				o.byteLE((int) Math.round(n.velocity() * 100 / 127.0));
				o.byteLE(100);
				o.shortLE(0);
			}
			o.shortLE(0);
		}
		o.shortLE(0);
		for (int i = 0; i < layers; i++) {
			o.string("");
			o.byteLE(0);
			o.byteLE(100);
			o.byteLE(100);
		}
		o.byteLE(0); // пользовательских инструментов нет
		return o.bytes();
	}

	private static String string(ByteBuffer b) {
		int len = b.getInt();
		if (len < 0 || len > b.remaining()) {
			throw new IllegalArgumentException("bad string length " + len);
		}
		byte[] s = new byte[len];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}

	private static final class Out {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		void byteLE(int v) {
			out.write(v);
		}

		void shortLE(int v) {
			out.write(v);
			out.write(v >>> 8);
		}

		void intLE(int v) {
			shortLE(v);
			shortLE(v >>> 16);
		}

		void string(String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			intLE(b.length);
			out.writeBytes(b);
		}

		byte[] bytes() {
			return out.toByteArray();
		}
	}
}
