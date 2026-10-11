package dev.nbmusic.gametest;

import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;
import dev.nbmusic.world.SchematicPlacer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import dev.nbmusic.core.export.StructureWriter;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Настоящий редстоун: ставим змейку, жмём кнопку и записываем, в какой игровой тик
 * каждый нотный блок получил сигнал. Редстоун-тик = 2 игровых; нота с тиком t
 * звучит через startDelay + t + 1 редстоун-тиков после нажатия.
 */
public class LayoutGameTest {
	private static final int PRESS_AT = 5;
	private static final int ROW_LENGTH = 16;

	/** Плотные места (шаг 1 тик), паузы, аккорды до 6 нот (3 этажа), ударные с песком. */
	static Song testSong() {
		List<MidiNote> notes = new ArrayList<>();
		int t = 0;
		for (int i = 0; i < 70; i++) {
			int chord = 1 + (i * 7) % 6;
			for (int v = 0; v < chord; v++) {
				notes.add(new MidiNote(t / 10.0, 0, i % 4 == 0 ? 33 : 0, 50 + v * 4 + i % 3, 100));
			}
			if (i % 3 == 0) {
				notes.add(new MidiNote(t / 10.0, MidiNote.DRUM_CHANNEL, 0, i % 2 == 0 ? 38 : 36, 100));
			}
			t += i % 10 < 6 ? 1 : 1 + (i % 7);
		}
		return new Song("test", notes);
	}

	@GameTest(maxTicks = 1200, padding = 64)
	public void snakeFacingEast(GameTestHelper helper) {
		run(helper, Direction.EAST, new BlockPos(0, 1, 0));
	}

	@GameTest(maxTicks = 1200, padding = 64)
	public void snakeFacingNorth(GameTestHelper helper) {
		// по взгляду на север ряды идут к −Z, поэтому начало сдвинуто вглубь
		run(helper, Direction.NORTH, new BlockPos(0, 1, 60));
	}

	/** Максимум: аккорды по 18 нот → 9 этажей, самые длинные отводы стояка. */
	@GameTest(maxTicks = 1200, padding = 64)
	public void nineLevels(GameTestHelper helper) {
		List<MidiNote> notes = new ArrayList<>();
		for (int i = 0; i < 24; i++) {
			double sec = i * (1 + i % 3) / 10.0;
			for (int v = 0; v < 18; v++) {
				// 18 разных нот: бас, арфа и колокольчик на разных высотах
				notes.add(new MidiNote(sec, 0, 0, 34 + v * 4 + i % 2, 100));
			}
		}
		Arrangement arrangement = Arranger.arrange(new Song("max", notes), new Arranger.Options(100, 18, 0));
		run(helper, Direction.EAST, new BlockPos(0, 1, 0), arrangement, 9);
	}

	/**
	 * Схема, записанная в ванильный .nbt нашим StructureWriter, загружается кодом игры
	 * (StructureTemplate, как /place template) и должна играть так же точно.
	 */
	@GameTest(maxTicks = 1200, padding = 64)
	public void structureNbtPlays(GameTestHelper helper) {
		run(helper, Direction.EAST, new BlockPos(0, 1, 0), Arranger.arrange(testSong(), new Arranger.Options(100, 6, 0)), 3,
				LayoutGameTest::placeViaStructureNbt);
	}

	interface Placer {
		void place(ServerLevel level, BlockPos anchor, Direction forward, BlockGrid grid);
	}

	private static void placeViaStructureNbt(ServerLevel level, BlockPos anchor, Direction forward, BlockGrid grid) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			StructureWriter.write(grid, SharedConstants.getCurrentVersion().dataVersion().version(), bytes);
			CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap());
			StructureTemplate template = new StructureTemplate();
			template.load(BuiltInRegistries.BLOCK, tag);
			if (!template.getSize().equals(new Vec3i(grid.width(), grid.height(), grid.length()))) {
				throw new IllegalStateException("размер структуры " + template.getSize());
			}
			template.placeInWorld(level, anchor, anchor, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void run(GameTestHelper helper, Direction forward, BlockPos origin) {
		run(helper, forward, origin, Arranger.arrange(testSong(), new Arranger.Options(100, 6, 0)), 3);
	}

	private static void run(GameTestHelper helper, Direction forward, BlockPos origin, Arrangement arrangement, int levels) {
		run(helper, forward, origin, arrangement, levels, SchematicPlacer::place);
	}

	private static void run(GameTestHelper helper, Direction forward, BlockPos origin, Arrangement arrangement, int levels, Placer placer) {
		LayoutGenerator.Layout layout = LayoutGenerator.generate(arrangement, ROW_LENGTH);
		BlockGrid grid = layout.grid();
		if (layout.rows() < 3 || layout.levels() != levels) {
			helper.fail("Тестовая песня должна дать ≥3 рядов и " + levels + " этажей, а дала " + layout.rows() + " и " + layout.levels());
			return;
		}

		BlockPos anchor = helper.absolutePos(origin);
		// Змейка больше тестовой площадки: без принудительной загрузки дальние чанки не тикают и повторители замирают
		BlockPos far = SchematicPlacer.toWorld(anchor, forward, grid.width() - 1, 0, grid.length() - 1);
		List<long[]> forced = new ArrayList<>();
		for (int cx = Math.min(anchor.getX(), far.getX()) >> 4; cx <= Math.max(anchor.getX(), far.getX()) >> 4; cx++) {
			for (int cz = Math.min(anchor.getZ(), far.getZ()) >> 4; cz <= Math.max(anchor.getZ(), far.getZ()) >> 4; cz++) {
				helper.getLevel().setChunkForced(cx, cz, true);
				forced.add(new long[]{cx, cz});
			}
		}
		placer.place(helper.getLevel(), anchor, forward, grid);

		List<BlockPos> noteBlocks = new ArrayList<>();
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					if (grid.get(x, y, z).startsWith("minecraft:note_block")) {
						noteBlocks.add(SchematicPlacer.toWorld(anchor, forward, x, y, z));
					}
				}
			}
		}
		BlockPos button = SchematicPlacer.toWorld(anchor, forward, layout.button());

		Map<BlockPos, Boolean> last = new HashMap<>();
		Map<Integer, List<String>> heard = new TreeMap<>();
		int endTick = PRESS_AT + 2 * (layout.startDelay() + arrangement.lengthTicks() + 1) + 40;
		for (long t = 1; t <= endTick; t++) {
			long now = t;
			helper.runAtTickTime(t, () -> {
				if (now == PRESS_AT) {
					// мировая позиция: relativePos/absolutePos теста учитывают поворот площадки
					BlockState buttonState = helper.getLevel().getBlockState(button);
					if (!(buttonState.getBlock() instanceof ButtonBlock buttonBlock)) {
						helper.fail("Нет кнопки в " + button + ": " + buttonState);
						return;
					}
					buttonBlock.press(buttonState, helper.getLevel(), button, null);
				}
				for (BlockPos pos : noteBlocks) {
					BlockState state = helper.getLevel().getBlockState(pos);
					boolean powered = state.getValue(NoteBlock.POWERED);
					if (powered && !last.getOrDefault(pos, false)) {
						long delay = now - PRESS_AT;
						if (delay % 2 != 0) {
							helper.fail("Нота на нечётном игровом тике: " + delay + " в " + pos);
						}
						int tick = (int) (delay / 2) - layout.startDelay() - 1;
						heard.computeIfAbsent(tick, k -> new ArrayList<>())
								.add(state.getValue(NoteBlock.INSTRUMENT).getSerializedName() + state.getValue(NoteBlock.NOTE));
					}
					last.put(pos, powered);
				}
				if (now == endTick) {
					forced.forEach(c -> helper.getLevel().setChunkForced((int) c[0], (int) c[1], false));
					Map<Integer, List<String>> expected = new TreeMap<>();
					arrangement.chords().forEach((tick, chord) -> expected.put(tick,
							chord.stream().map(n -> n.instrument().id + n.key()).sorted().toList()));
					heard.replaceAll((k, v) -> v.stream().sorted().toList());
					if (expected.equals(heard)) {
						helper.succeed();
					} else {
						helper.fail(diff(expected, heard));
					}
				}
			});
		}
	}

	/** Первые расхождения: тик → ожидалось / услышано. */
	private static String diff(Map<Integer, List<String>> expected, Map<Integer, List<String>> heard) {
		TreeMap<Integer, String> lines = new TreeMap<>();
		for (int tick : expected.keySet()) {
			if (!expected.get(tick).equals(heard.get(tick))) {
				lines.put(tick, "t=" + tick + " ждали " + expected.get(tick) + " услышали " + heard.get(tick));
			}
		}
		for (int tick : heard.keySet()) {
			if (!expected.containsKey(tick)) {
				lines.put(tick, "t=" + tick + " лишнее " + heard.get(tick));
			}
		}
		int heardCount = heard.values().stream().mapToInt(List::size).sum();
		int expectedCount = expected.values().stream().mapToInt(List::size).sum();
		return "Нот услышано " + heardCount + " из " + expectedCount + ", расхождений " + lines.size() + ":\n"
				+ String.join("\n", lines.values().stream().limit(12).toList());
	}
}
