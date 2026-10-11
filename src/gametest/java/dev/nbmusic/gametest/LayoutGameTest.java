package dev.nbmusic.gametest;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;
import dev.nbmusic.world.SchematicPlacer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Настоящий редстоун: ставим схему, жмём кнопку и записываем, в какой игровой тик
 * каждый нотный блок получил сигнал. Редстоун-тик = 2 игровых, первый повторитель даёт +1.
 */
public class LayoutGameTest {
	private static final int PRESS_AT = 5;

	@GameTest(maxTicks = 400)
	public void songPlaysOnTime(GameTestHelper helper) {
		List<MidiNote> notes = new ArrayList<>();
		int[] ticks = {0, 1, 2, 4, 9, 10, 11, 25, 26, 40};
		for (int i = 0; i < ticks.length; i++) {
			int chord = 1 + i % 6; // аккорды до 6 нот → до 3 дорожек
			for (int v = 0; v < chord; v++) {
				notes.add(new MidiNote(ticks[i] / 10.0, 0, i % 3 == 0 ? 33 : 0, 50 + v * 4, 100));
			}
			if (i % 2 == 0) {
				notes.add(new MidiNote(ticks[i] / 10.0, MidiNote.DRUM_CHANNEL, 0, 38, 100)); // малый — песок
			}
		}
		Arrangement arrangement = Arranger.arrange(new Song("test", notes), new Arranger.Options(100, 8, 0));
		LayoutGenerator.Layout layout = LayoutGenerator.generate(arrangement);
		BlockGrid grid = layout.grid();

		BlockPos origin = new BlockPos(0, 1, 0);
		SchematicPlacer.place(helper.getLevel(), helper.absolutePos(origin), grid);

		List<BlockPos> noteBlocks = new ArrayList<>();
		for (int x = 0; x < grid.width(); x++) {
			for (int z = 0; z < grid.length(); z++) {
				if (grid.get(x, 2, z).startsWith("minecraft:note_block")) {
					noteBlocks.add(origin.offset(x, 2, z));
				}
			}
		}

		Map<BlockPos, Boolean> last = new HashMap<>();
		Map<Integer, List<String>> heard = new TreeMap<>();
		int endTick = PRESS_AT + 2 * (arrangement.lengthTicks() + 1) + 40;
		for (long t = 1; t <= endTick; t++) {
			long now = t;
			helper.runAtTickTime(t, () -> {
				if (now == PRESS_AT) {
					helper.pressButton(origin.offset(0, 2, layout.buttonZ()));
				}
				for (BlockPos pos : noteBlocks) {
					BlockState state = helper.getBlockState(pos);
					boolean powered = state.getValue(NoteBlock.POWERED);
					if (powered && !last.getOrDefault(pos, false)) {
						long delay = now - PRESS_AT;
						if (delay % 2 != 0) {
							helper.fail("Нота на нечётном игровом тике: " + delay + " в " + pos);
						}
						heard.computeIfAbsent((int) (delay / 2) - 1, k -> new ArrayList<>())
								.add(state.getValue(NoteBlock.INSTRUMENT).getSerializedName() + state.getValue(NoteBlock.NOTE));
					}
					last.put(pos, powered);
				}
				if (now == endTick) {
					Map<Integer, List<String>> expected = new TreeMap<>();
					arrangement.chords().forEach((tick, chord) -> expected.put(tick,
							chord.stream().map(n -> n.instrument().id + n.key()).sorted().toList()));
					heard.replaceAll((k, v) -> v.stream().sorted().toList());
					if (expected.equals(heard)) {
						helper.succeed();
					} else {
						helper.fail("Ожидалось " + expected + "\nУслышано " + heard);
					}
				}
			});
		}
	}
}
