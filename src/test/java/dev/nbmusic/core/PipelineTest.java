package dev.nbmusic.core;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.export.SpongeSchematicWriter;
import dev.nbmusic.core.io.MidiDecoder;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.BlockStates;
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

	/** Все ноты аранжировки стоят в схеме, у каждого нотного блока воздух сверху и верная подложка. */
	@Test
	void snakeContainsEveryNote() {
		List<MidiNote> notes = new ArrayList<>();
		for (int t = 0; t < 300; t += 1 + t % 3) {
			for (int v = 0; v < 1 + t % 5; v++) {
				notes.add(new MidiNote(t / 10.0, 0, 0, 56 + v * 3, 100));
			}
			if (t % 4 == 0) {
				notes.add(new MidiNote(t / 10.0, MidiNote.DRUM_CHANNEL, 0, 38, 100));
			}
		}
		Arrangement a = Arranger.arrange(new Song("t", notes), Arranger.Options.DEFAULT);
		int rowLength = 20;
		LayoutGenerator.Layout layout = LayoutGenerator.generate(a, rowLength);
		BlockGrid g = layout.grid();
		assertTrue(layout.rows() > 3, "песня должна сложиться змейкой");
		assertEquals(LayoutGenerator.levelsFor(a.maxChord()), layout.levels());
		assertEquals(LayoutGenerator.LEVEL_HEIGHT * layout.levels(), g.height());
		assertEquals("minecraft:stone_button", BlockStates.name(g.get(layout.button().x(), layout.button().y(), layout.button().z())));

		Pattern noteState = Pattern.compile("minecraft:note_block\\[instrument=(\\w+),note=(\\d+)");
		List<String> placed = new ArrayList<>();
		for (int y = 0; y < g.height(); y++) {
			for (int z = 0; z < g.length(); z++) {
				for (int x = 0; x < g.width(); x++) {
					Matcher m = noteState.matcher(g.get(x, y, z));
					if (!m.find()) {
						continue;
					}
					Instrument inst = Instrument.valueOf(m.group(1).toUpperCase(java.util.Locale.ROOT));
					assertEquals(BlockGrid.AIR, g.get(x, y + 1, z), "над нотным блоком нужен воздух");
					assertEquals(inst.baseBlock, g.get(x, y - 1, z), "подложка инструмента");
					if (inst.needsSupport()) {
						assertNotEquals(BlockGrid.AIR, g.get(x, y - 2, z), "песку нужна опора");
					}
					placed.add(m.group(1) + m.group(2));
				}
			}
		}
		List<String> expected = a.chords().values().stream().flatMap(List::stream)
				.map(n -> n.instrument().id + n.key()).sorted().toList();
		assertEquals(expected, placed.stream().sorted().toList());
		// ряды не вылезают за заявленную длину (плюс зазор и столбец поворота)
		assertTrue(g.width() <= layout.button().x() + 1 + rowLength + 64, "ширина " + g.width());
	}

	@Test
	void rotatesBlockStates() {
		assertEquals("minecraft:repeater[delay=2,facing=south,locked=false,powered=false]",
				BlockStates.rotate("minecraft:repeater[delay=2,facing=east,locked=false,powered=false]", 1));
		assertEquals("minecraft:redstone_wire[east=side,north=up,power=0,south=none,west=none]",
				BlockStates.rotate("minecraft:redstone_wire[east=none,north=side,power=0,south=none,west=up]", 1));
		assertEquals("minecraft:hay_block[axis=y]", BlockStates.rotate("minecraft:hay_block[axis=y]", 3));
		assertEquals("minecraft:dirt", BlockStates.rotate("minecraft:dirt", 2));
		String s = "minecraft:stone_button[face=floor,facing=east,powered=false]";
		assertEquals(s, BlockStates.rotate(BlockStates.rotate(s, 3), 1));
	}

	@Test
	void writesSpongeSchematic(@TempDir Path dir) throws Exception {
		TestMidi midi = new TestMidi().note(0, 0, 60, 100).note(480, 9, 38, 100);
		Path file = dir.resolve("song.mid");
		MidiSystem.write(midi.sequence, 1, file.toFile());

		Pipeline.Result r = Pipeline.run(file, Arranger.Options.DEFAULT);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SpongeSchematicWriter.write(r.layout().grid(), "song", "test", 4000, new int[]{0, 0, 0}, out);

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
