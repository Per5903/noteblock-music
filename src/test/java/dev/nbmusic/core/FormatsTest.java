package dev.nbmusic.core;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.export.LitematicTestAccess;
import dev.nbmusic.core.io.NbsFormat;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Note;
import dev.nbmusic.core.model.Song;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FormatsTest {
	static Arranger.Arrangement sampleArrangement() {
		List<MidiNote> notes = new ArrayList<>();
		Random rnd = new Random(3);
		for (int i = 0; i < 200; i++) {
			double t = i * 0.1 + (i % 3) * 0.05;
			notes.add(new MidiNote(t, 0, rnd.nextInt(128), 36 + rnd.nextInt(60), 30 + rnd.nextInt(97)));
			if (i % 4 == 0) {
				notes.add(new MidiNote(t, 9, 0, 35 + rnd.nextInt(20), 100));
			}
		}
		return Arranger.arrange(new Song("sample", notes), Arranger.Options.DEFAULT);
	}

	/** Экспорт в .nbs и обратный импорт дают те же аккорды: инструменты, ключи, тики. */
	@Test
	void nbsRoundTrip() throws Exception {
		Arranger.Arrangement a = sampleArrangement();
		byte[] nbs = NbsFormat.write(a, "Тест", "автор");
		Song back = NbsFormat.read("file", nbs);
		assertEquals("Тест", back.title());
		Arranger.Arrangement again = Arranger.arrange(back, Arranger.Options.DEFAULT);
		assertEquals(a.chords().keySet(), again.chords().keySet());
		a.chords().forEach((tick, chord) -> {
			List<String> x = chord.stream().map(n -> n.instrument() + ":" + n.key()).sorted().toList();
			List<String> y = again.chords().get(tick).stream().map(n -> n.instrument() + ":" + n.key()).sorted().toList();
			assertEquals(x, y, "тик " + tick);
		});
		// громкость переживает округление 127 → 100 → 127 с точностью ±1
		Note first = a.chords().firstEntry().getValue().getFirst();
		Note firstBack = again.chords().firstEntry().getValue().stream()
				.filter(n -> n.instrument() == first.instrument() && n.key() == first.key()).findFirst().orElseThrow();
		assertEquals(first.velocity(), firstBack.velocity(), 1.5);
	}

	/** Старый формат NBS (до v1): без версии, без громкости нот. */
	@Test
	void readsLegacyNbs() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) 20); // длина песни (≠ 0 → старый формат)
		b.putShort((short) 2); // слоёв
		for (String s : new String[]{"Old song", "", "", ""}) {
			b.putInt(s.length()).put(s.getBytes(StandardCharsets.UTF_8));
		}
		b.putShort((short) 500); // 5 тиков в секунду
		b.put((byte) 0).put((byte) 0).put((byte) 4);
		b.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
		b.putInt(0); // имя MIDI
		// тик 0: слой 0 — арфа 45 (A4), слой 1 — бас 33; тик 10: малый барабан
		b.putShort((short) 1).putShort((short) 1).put((byte) 0).put((byte) 45).putShort((short) 1).put((byte) 1).put((byte) 33).putShort((short) 0);
		b.putShort((short) 10).putShort((short) 1).put((byte) 3).put((byte) 40).putShort((short) 0);
		b.putShort((short) 0);
		out.write(b.array(), 0, b.position());
		Song s = NbsFormat.read("x", out.toByteArray());
		assertEquals("Old song", s.title());
		assertEquals(3, s.notes().size());
		MidiNote harp = s.notes().stream().filter(n -> n.instrument() == Instrument.HARP).findFirst().orElseThrow();
		assertEquals(0.0, harp.seconds(), 1e-9);
		assertEquals(Instrument.HARP.lowestMidi + 12, harp.pitch());
		MidiNote snare = s.notes().stream().filter(n -> n.instrument() == Instrument.SNARE).findFirst().orElseThrow();
		assertEquals(2.0, snare.seconds(), 1e-9, "тик 10 при 5 тиках/с");
		Arranger.Arrangement a = Arranger.arrange(s, Arranger.Options.DEFAULT);
		assertTrue(a.chords().get(0).stream().anyMatch(n -> n.instrument() == Instrument.BASS && n.key() == 0));
		assertTrue(a.chords().get(20).stream().anyMatch(n -> n.instrument() == Instrument.SNARE && n.key() == 7));
	}

	@Test
	void litematicBitPackingRoundTrip() {
		Random rnd = new Random(5);
		for (int bits = 2; bits <= 12; bits++) {
			int[] values = new int[1000 + bits];
			for (int i = 0; i < values.length; i++) {
				values[i] = rnd.nextInt(1 << bits);
			}
			long[] packed = LitematicTestAccess.pack(values, bits);
			assertEquals((values.length * (long) bits + 63) / 64, packed.length);
			List<Integer> back = LitematicTestAccess.unpack(packed, bits, values.length);
			for (int i = 0; i < values.length; i++) {
				assertEquals(values[i], back.get(i), bits + " бит, значение " + i);
			}
		}
		assertEquals(2, LitematicTestAccess.bitsFor(2));
		assertEquals(2, LitematicTestAccess.bitsFor(4));
		assertEquals(3, LitematicTestAccess.bitsFor(5));
		assertEquals(5, LitematicTestAccess.bitsFor(17));
	}
}
