package dev.nbmusic.core;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.export.SpongeSchematicWriter;
import dev.nbmusic.core.io.MidiDecoder;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Note;
import dev.nbmusic.core.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {
	@Test
	void decodesTempoChanges() throws Exception {
		// 120 BPM: четверть = 0,5 с; с тика 960 — 60 BPM: четверть = 1 с
		TestMidi midi = new TestMidi().tempo(0, 120).tempo(960, 60)
				.note(0, 0, 60, 100).note(480, 0, 62, 100).note(960, 0, 64, 100).note(1440, 0, 65, 100);
		Song song = MidiDecoder.decode("t", midi.sequence);
		List<Double> times = song.notes().stream().map(MidiNote::seconds).toList();
		assertEquals(List.of(0.0, 0.5, 1.0, 2.0), times);
	}

	@Test
	void appliesProgramChangeAtSameTick() throws Exception {
		TestMidi midi = new TestMidi().note(0, 0, 40, 100).program(0, 0, 33);
		Song song = MidiDecoder.decode("t", midi.sequence);
		assertEquals(33, song.notes().getFirst().program());
	}

	@Test
	void fitsPitchIntoInstrumentRanges() {
		Song song = new Song("t", List.of(
				new MidiNote(0, 0, 0, 66, 100),   // фортепиано, F#4 → harp key 12
				new MidiNote(0.1, 0, 0, 40, 100), // низкая → bass
				new MidiNote(0.2, 0, 0, 120, 100),// выше всех диапазонов → bell, сдвиг на октавы
				new MidiNote(0.3, 9, 0, 36, 100)  // бочка
		));
		Arrangement a = Arranger.arrange(song, Arranger.Options.DEFAULT);
		List<Note> notes = a.chords().values().stream().flatMap(List::stream).toList();
		assertEquals(new Note(0, Instrument.HARP, 12, 100), notes.get(0));
		assertEquals(Instrument.BASS, notes.get(1).instrument());
		assertEquals(40 - 30, notes.get(1).key());
		assertEquals(Instrument.BELL, notes.get(2).instrument());
		assertEquals(Instrument.BASEDRUM, notes.get(3).instrument());
		assertEquals(1, a.shiftedNotes());
	}

	@Test
	void limitsPolyphonyKeepingLoudest() {
		List<MidiNote> chord = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			chord.add(new MidiNote(1.0, 0, 0, 55 + i, 20 + i * 10));
		}
		Arrangement a = Arranger.arrange(new Song("t", chord), new Arranger.Options(100, 4, 0));
		List<Note> notes = a.chords().get(10);
		assertEquals(4, notes.size());
		assertEquals(2, a.droppedNotes());
		assertTrue(notes.stream().allMatch(n -> n.velocity() >= 40));
	}

	@Test
	void speedStretchesTicks() {
		Song song = new Song("t", List.of(new MidiNote(1.0, 0, 0, 60, 100)));
		Arrangement a = Arranger.arrange(song, new Arranger.Options(50, 8, 0));
		assertEquals(20, a.chords().firstKey());
	}

	/** Прогоняет сигнал по каждой дорожке и проверяет, что каждая нота звучит в свой тик. */
	@Test
	void layoutTimingMatchesArrangement() {
		List<MidiNote> notes = new ArrayList<>();
		int[] ticks = {0, 1, 2, 7, 15, 16, 40};
		for (int t : ticks) {
			for (int v = 0; v < 1 + t % 5; v++) {
				notes.add(new MidiNote(t / 10.0, 0, 0, 56 + v * 3, 100));
			}
		}
		Arrangement a = Arranger.arrange(new Song("t", notes), Arranger.Options.DEFAULT);
		LayoutGenerator.Layout layout = LayoutGenerator.generate(a);
		BlockGrid g = layout.grid();
		assertEquals(LayoutGenerator.lanesFor(a.maxChord()), layout.lanes());

		Pattern delay = Pattern.compile("minecraft:repeater\\[delay=(\\d)");
		Pattern noteState = Pattern.compile("minecraft:note_block\\[instrument=(\\w+),note=(\\d+)");
		Map<Integer, List<String>> heard = new TreeMap<>();
		for (int lane = 0; lane < layout.lanes(); lane++) {
			int z = LayoutGenerator.laneZ(lane);
			int time = -1; // сигнал покидает шину на тике -1
			for (int x = LayoutGenerator.FIRST_REPEATER_X; x < g.width(); x++) {
				String s = g.get(x, 2, z);
				Matcher m = delay.matcher(s);
				if (m.find()) {
					time += Integer.parseInt(m.group(1));
				} else if (s.equals("minecraft:white_concrete")) {
					for (int dz : new int[]{-1, 1}) {
						Matcher n = noteState.matcher(g.get(x, 2, z + dz));
						if (n.find()) {
							assertEquals(BlockGrid.AIR, g.get(x, 3, z + dz), "над нотным блоком нужен воздух");
							heard.computeIfAbsent(time, k -> new ArrayList<>()).add(n.group(1) + n.group(2));
						}
					}
				} else {
					assertEquals(BlockGrid.AIR, s, "дорожка не должна прерываться");
					break;
				}
			}
		}
		Map<Integer, List<String>> expected = new TreeMap<>();
		a.chords().forEach((tick, chord) -> expected.put(tick,
				chord.stream().map(n -> n.instrument().id + n.key()).toList()));
		heard.replaceAll((k, v) -> v.stream().sorted().toList());
		expected.replaceAll((k, v) -> v.stream().sorted().toList());
		assertEquals(expected, heard);
	}

	@Test
	void writesSpongeSchematic(@TempDir Path dir) throws Exception {
		TestMidi midi = new TestMidi().note(0, 0, 60, 100).note(480, 9, 38, 100);
		Path file = dir.resolve("song.mid");
		MidiSystem.write(midi.sequence, 1, file.toFile());

		Pipeline.Result r = Pipeline.run(file, Arranger.Options.DEFAULT);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SpongeSchematicWriter.write(r.layout().grid(), "song", "test", 4000, out);

		byte[] raw = new GZIPInputStream(new ByteArrayInputStream(out.toByteArray())).readAllBytes();
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
		assertEquals(10, in.readByte());       // корень — compound
		assertEquals("", in.readUTF());
		assertEquals(10, in.readByte());
		assertEquals("Schematic", in.readUTF());
		String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(text.contains("minecraft:note_block[instrument=harp,note=6,powered=false]"));
		assertTrue(text.contains("minecraft:note_block[instrument=snare"));
		assertTrue(text.contains(Instrument.SNARE.baseBlock));
		assertTrue(Files.size(file) > 0);
	}
}
