package dev.nbmusic.core.layout;

import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.Note;

import java.util.List;
import java.util.Map;

/**
 * Строит прямые параллельные дорожки, сигнал идёт по +X.
 *
 * <pre>
 * вид сверху (y=2), одна дорожка:
 *
 *  z=c-1        N     N
 *  z=c    B  D  R  C  R  R  C ...     B — кнопка, D — шина из пыли (вдоль Z)
 *  z=c+1        N     N               R — повторитель, C — блок-носитель, N — нотный блок
 * </pre>
 *
 * Повторитель запитывает носитель, носитель активирует нотные блоки по бокам и
 * следующий повторитель. Паузы набираются повторителями по 1–4 тика.
 * Аккорд из K нот раскладывается на ⌈K/2⌉ дорожек, все дорожки стартуют от общей
 * шины без задержки, поэтому идут синхронно.
 * Слои: y=0 опора под песком, y=1 подложки инструментов и опоры, y=2 механизм, y=3 воздух.
 */
public final class LayoutGenerator {
	/** Дальше пыль от кнопки не дотянется (сигнал 15 блоков). */
	public static final int MAX_LANES = 9;
	public static final int LANE_PITCH = 3;
	public static final int BUS_X = 1;
	public static final int FIRST_REPEATER_X = 2;

	static final String SUPPORT = "minecraft:smooth_stone";
	static final String CARRIER = "minecraft:white_concrete";

	private LayoutGenerator() {
	}

	public record Layout(BlockGrid grid, int lanes, int buttonZ) {
	}

	public static int lanesFor(int maxChord) {
		return Math.max(1, (maxChord + 1) / 2);
	}

	public static Layout generate(Arrangement arrangement) {
		int lanes = lanesFor(arrangement.maxChord());
		if (lanes > MAX_LANES) {
			throw new IllegalArgumentException("Слишком много одновременных нот: " + arrangement.maxChord()
					+ " (максимум " + MAX_LANES * 2 + ")");
		}
		BlockGrid grid = new BlockGrid();
		int buttonZ = laneZ(lanes / 2);
		buildBus(grid, lanes, buttonZ);
		for (int lane = 0; lane < lanes; lane++) {
			buildLane(grid, lane, arrangement.chords());
		}
		grid.ensureSize(grid.width(), 4, LANE_PITCH * lanes);
		return new Layout(grid, lanes, buttonZ);
	}

	public static int laneZ(int lane) {
		return LANE_PITCH * lane + 1;
	}

	private static void buildBus(BlockGrid grid, int lanes, int buttonZ) {
		int zMin = laneZ(0);
		int zMax = laneZ(lanes - 1);
		grid.set(0, 1, buttonZ, SUPPORT);
		grid.set(0, 2, buttonZ, "minecraft:stone_button[face=floor,facing=east,powered=false]");
		for (int z = zMin; z <= zMax; z++) {
			boolean laneStart = (z - 1) % LANE_PITCH == 0;
			grid.set(BUS_X, 1, z, SUPPORT);
			grid.set(BUS_X, 2, z, "minecraft:redstone_wire[east=" + side(laneStart)
					+ ",north=" + side(z > zMin)
					+ ",power=0,south=" + side(z < zMax)
					+ ",west=" + side(z == buttonZ) + "]");
		}
	}

	private static void buildLane(BlockGrid grid, int lane, Map<Integer, List<Note>> chords) {
		int z = laneZ(lane);
		int x = FIRST_REPEATER_X;
		// сигнал выходит с шины на тике -1: первый повторитель всегда есть
		int prevTick = -1;
		for (var e : chords.entrySet()) {
			List<Note> chord = e.getValue();
			int first = lane * 2;
			if (chord.size() <= first) {
				continue;
			}
			int gap = e.getKey() - prevTick;
			while (gap > 0) {
				int delay = Math.min(4, gap);
				grid.set(x, 1, z, SUPPORT);
				grid.set(x, 2, z, repeater(delay));
				gap -= delay;
				x++;
			}
			grid.set(x, 1, z, SUPPORT);
			grid.set(x, 2, z, CARRIER);
			placeNote(grid, x, z - 1, chord.get(first));
			if (chord.size() > first + 1) {
				placeNote(grid, x, z + 1, chord.get(first + 1));
			}
			x++;
			prevTick = e.getKey();
		}
	}

	private static void placeNote(BlockGrid grid, int x, int z, Note note) {
		Instrument inst = note.instrument();
		if (inst.needsSupport()) {
			grid.set(x, 0, z, SUPPORT);
		}
		grid.set(x, 1, z, inst.baseBlock);
		grid.set(x, 2, z, "minecraft:note_block[instrument=" + inst.id + ",note=" + note.key() + ",powered=false]");
	}

	/** Вход повторителя смотрит на -X (facing указывает на вход). */
	static String repeater(int delay) {
		return "minecraft:repeater[delay=" + delay + ",facing=west,locked=false,powered=false]";
	}

	private static String side(boolean connected) {
		return connected ? "side" : "none";
	}
}
