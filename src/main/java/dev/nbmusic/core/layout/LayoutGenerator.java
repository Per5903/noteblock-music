package dev.nbmusic.core.layout;

import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.Note;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Змейка из нотных блоков.
 *
 * <p>Песня — одна последовательность ячеек: повторители (паузы 1–4 тика) и носители
 * (блок, который активирует нотные блоки по бокам). Последовательность укладывается
 * рядами по X; между рядами разворот из пыли — у пыли нет задержки, поэтому ритм
 * не сбивается. Ряды идут по +Z с шагом {@value #ROW_PITCH}.
 *
 * <pre>
 * вид сверху, один этаж:
 *
 *  R0:  ─R─C─R─R─C─R─C─┐        R — повторитель, C — носитель
 *                      │        (нотные блоки N — по бокам от C)
 *  R1:  ┌─C─R─C─R─R─C─R┘        ─ │ — пыль поворота
 *       │
 *  R2:  └R─C─R─ ...
 * </pre>
 *
 * <p>Аккорд из K нот раскладывается на ⌈K/2⌉ <b>этажей</b>, каждый этаж — копия змейки
 * со своими нотными блоками; повороты у всех этажей одинаковые. Этажи запитываются от
 * одной кнопки через лестницу-стояк, задержки подобраны так, что все этажи стартуют в
 * один и тот же тик.
 *
 * <p>Слои этажа l (y = 4l + …): 0 — опора под песком, 1 — подложки и опоры, 2 — механизм, 3 — воздух.
 */
public final class LayoutGenerator {
	public static final int MAX_LEVELS = 9;
	public static final int LEVEL_HEIGHT = 4;
	public static final int ROW_PITCH = 4;
	public static final int DEFAULT_ROW_LENGTH = 48;
	public static final int MIN_ROW_LENGTH = 8;

	/** Z стояка с кнопкой и Z первого ряда. */
	static final int RISER_Z = 0;
	static final int FIRST_ROW_Z = 2;
	/** Стояк: повторитель + блок + 3 ступени + площадка следующего этажа. */
	static final int RISER_STEP = 5;
	private static final int MAX_DUST_RUN = 15;

	static final String SUPPORT = "minecraft:smooth_stone";
	static final String CARRIER = "minecraft:white_concrete";
	static final String DUST = "minecraft:redstone_wire";

	private LayoutGenerator() {
	}

	/**
	 * @param button     где стоит кнопка запуска
	 * @param startDelay через сколько редстоун-тиков после нажатия сигнал входит во все этажи;
	 *                   нота с тиком t звучит через startDelay + t + 1
	 */
	public record Layout(BlockGrid grid, int levels, int rows, int rowLength, GridPos button, int startDelay) {
	}

	public static int levelsFor(int maxChord) {
		return Math.max(1, (maxChord + 1) / 2);
	}

	public static Layout generate(Arrangement arrangement) {
		return generate(arrangement, DEFAULT_ROW_LENGTH);
	}

	public static Layout generate(Arrangement arrangement, int rowLength) {
		if (rowLength < MIN_ROW_LENGTH) {
			throw new IllegalArgumentException("Длина ряда меньше " + MIN_ROW_LENGTH);
		}
		int levels = levelsFor(arrangement.maxChord());
		if (levels > MAX_LEVELS) {
			throw new IllegalArgumentException("Слишком много одновременных нот: " + arrangement.maxChord()
					+ " (максимум " + MAX_LEVELS * 2 + ")");
		}

		List<Cell> cells = cells(arrangement.chords());
		List<int[]> rows = splitRows(cells, rowLength);
		Riser riser = Riser.plan(levels);

		BlockGrid grid = new BlockGrid();
		int left = riser.laneStartX;
		for (int level = 0; level < levels; level++) {
			int y = level * LEVEL_HEIGHT;
			for (int r = 0; r < rows.size(); r++) {
				buildRow(grid, cells, rows.get(r), r, r + 1 < rows.size(), left, rowLength, y, level);
			}
		}
		riser.build(grid, levels);
		shapeDust(grid);
		grid.ensureSize(grid.width(), LEVEL_HEIGHT * levels, grid.length());
		return new Layout(grid, levels, rows.size(), rowLength, riser.button, riser.delay);
	}

	/** Повторитель с задержкой {@code delay} либо носитель с аккордом. */
	private record Cell(int delay, List<Note> chord) {
		boolean isRepeater() {
			return chord == null;
		}
	}

	private static List<Cell> cells(Map<Integer, List<Note>> chords) {
		List<Cell> cells = new ArrayList<>();
		// сигнал входит в этаж на тике -1: перед первой нотой всегда есть повторитель
		int prev = -1;
		for (var e : chords.entrySet()) {
			for (int gap = e.getKey() - prev; gap > 0; gap -= 4) {
				cells.add(new Cell(Math.min(4, gap), null));
			}
			cells.add(new Cell(0, e.getValue()));
			prev = e.getKey();
		}
		return cells;
	}

	/** Делит ячейки на ряды так, чтобы каждый ряд после поворота начинался с повторителя. */
	private static List<int[]> splitRows(List<Cell> cells, int rowLength) {
		List<int[]> rows = new ArrayList<>();
		int start = 0;
		while (start < cells.size()) {
			int end = Math.min(cells.size(), start + rowLength);
			if (end < cells.size() && !cells.get(end).isRepeater()) {
				end--; // перед носителем всегда стоит повторитель
			}
			rows.add(new int[]{start, end});
			start = end;
		}
		return rows;
	}

	public static int rowZ(int row) {
		return FIRST_ROW_Z + ROW_PITCH * row;
	}

	/**
	 * Ряд занимает X от {@code left} до {@code left + rowLength - 1}. Повороты — на
	 * X = left + rowLength + 1 справа и X = left - 2 слева: столбец зазора отделяет пыль
	 * от крайних нотных блоков.
	 */
	private static void buildRow(BlockGrid grid, List<Cell> cells, int[] span, int row, boolean turn,
			int left, int rowLength, int y, int level) {
		int z = rowZ(row);
		int dir = row % 2 == 0 ? 1 : -1;
		int x = dir > 0 ? left : left + rowLength - 1;
		for (int i = span[0]; i < span[1]; i++, x += dir) {
			Cell cell = cells.get(i);
			grid.set(x, y + 1, z, SUPPORT);
			if (cell.isRepeater()) {
				grid.set(x, y + 2, z, repeater(cell.delay(), dir > 0 ? "west" : "east"));
				continue;
			}
			grid.set(x, y + 2, z, CARRIER);
			int first = level * 2;
			if (cell.chord().size() > first) {
				placeNote(grid, x, y, z - 1, cell.chord().get(first));
			}
			if (cell.chord().size() > first + 1) {
				placeNote(grid, x, y, z + 1, cell.chord().get(first + 1));
			}
		}
		if (!turn) {
			return;
		}
		int turnX = dir > 0 ? left + rowLength + 1 : left - 2;
		for (; x != turnX; x += dir) {
			dust(grid, x, y, z);
		}
		for (int dz = 0; dz <= ROW_PITCH; dz++) {
			dust(grid, turnX, y, z + dz);
		}
		// вход в первый повторитель следующего ряда через столбец зазора
		dust(grid, turnX - dir, y, z + ROW_PITCH);
	}

	private static void placeNote(BlockGrid grid, int x, int y, int z, Note note) {
		Instrument inst = note.instrument();
		if (inst.needsSupport()) {
			grid.set(x, y, z, SUPPORT);
		}
		grid.set(x, y + 1, z, inst.baseBlock);
		grid.set(x, y + 2, z, "minecraft:note_block[instrument=" + inst.id + ",note=" + note.key() + ",powered=false]");
	}

	private static void dust(BlockGrid grid, int x, int y, int z) {
		grid.set(x, y + 1, z, SUPPORT);
		grid.set(x, y + 2, z, DUST);
	}

	/** {@code facing} указывает на вход повторителя. */
	static String repeater(int delay, String facing) {
		return "minecraft:repeater[delay=" + delay + ",facing=" + facing + ",locked=false,powered=false]";
	}

	/**
	 * Стояк запуска в плоскости Z = {@link #RISER_Z}: кнопка, затем площадки этажей,
	 * между ними повторитель и лестница из пыли вверх (по −X). От площадки этажа l
	 * отвод: пыль на Z+1, угол на Z+2 = Z первого ряда, затем по +X к началу ряда.
	 * На отводах стоят повторители: они держат силу сигнала и выравнивают задержку
	 * этажей до общего значения {@link #delay}.
	 */
	private static final class Riser {
		int junction0;
		int laneStartX;
		int delay;
		GridPos button;
		List<List<Integer>> runDelays; // по этажам: задержка в каждой клетке отвода (0 — пыль)

		static Riser plan(int levels) {
			for (int c = 1; ; c++) {
				Riser r = tryPlan(levels, c);
				if (r != null) {
					return r;
				}
			}
		}

		private static Riser tryPlan(int levels, int run0) {
			int[] len = new int[levels];
			List<List<Integer>> forced = new ArrayList<>();
			int delay = 0;
			for (int l = 0; l < levels; l++) {
				len[l] = run0 + RISER_STEP * l;
				forced.add(forcedRepeaters(len[l], firstDustLimit(l)));
				delay = Math.max(delay, l + forced.get(l).size());
			}
			List<List<Integer>> runs = new ArrayList<>();
			for (int l = 0; l < levels; l++) {
				int need = delay - l;
				int count = Math.max(forced.get(l).size(), (need + 3) / 4);
				if (count > len[l]) {
					return null;
				}
				Set<Integer> at = new HashSet<>(forced.get(l));
				for (int i = 0; at.size() < count; i++) {
					at.add(i);
				}
				List<Integer> run = new ArrayList<>();
				int left = need;
				int remaining = count;
				for (int i = 0; i < len[l]; i++) {
					if (!at.contains(i)) {
						run.add(0);
						continue;
					}
					// оставшимся повторителям нужно хотя бы по 1 тику
					int d = Math.min(4, left - (remaining - 1));
					run.add(d);
					left -= d;
					remaining--;
				}
				runs.add(run);
			}
			Riser r = new Riser();
			r.junction0 = RISER_STEP * (levels - 1);
			r.laneStartX = r.junction0 + 1 + run0;
			r.delay = delay;
			r.button = new GridPos(r.junction0 + 1, 2, RISER_Z);
			r.runDelays = runs;
			return r;
		}

		/**
		 * Сила сигнала на площадке: от кнопки 15; на верхних этажах 15 на блоке после
		 * повторителя минус 3 ступени = 12. До отвода по X ещё 2 клетки пыли.
		 */
		private static int firstDustLimit(int level) {
			return (level == 0 ? 15 : 12) - 3;
		}

		/** Минимальный набор повторителей на отводе длиной {@code len}, жадно — как можно дальше. */
		static List<Integer> forcedRepeaters(int len, int firstLimit) {
			List<Integer> at = new ArrayList<>();
			int pos = -1;
			int limit = firstLimit;
			while (len - 1 - pos > limit) {
				pos += limit + 1;
				at.add(pos);
				limit = MAX_DUST_RUN;
			}
			return at;
		}

		void build(BlockGrid grid, int levels) {
			grid.set(button.x(), 1, RISER_Z, SUPPORT);
			grid.set(button.x(), 2, RISER_Z, "minecraft:stone_button[face=floor,facing=east,powered=false]");
			for (int l = 0; l < levels; l++) {
				int y = l * LEVEL_HEIGHT;
				int j = junction0 - RISER_STEP * l;
				dust(grid, j, y, RISER_Z);
				dust(grid, j, y, RISER_Z + 1);
				dust(grid, j, y, FIRST_ROW_Z);
				List<Integer> run = runDelays.get(l);
				for (int i = 0; i < run.size(); i++) {
					int x = j + 1 + i;
					if (run.get(i) == 0) {
						dust(grid, x, y, FIRST_ROW_Z);
					} else {
						grid.set(x, y + 1, FIRST_ROW_Z, SUPPORT);
						grid.set(x, y + 2, FIRST_ROW_Z, repeater(run.get(i), "west"));
					}
				}
				if (l + 1 < levels) {
					// повторитель → блок с пылью → 3 ступени вверх → площадка следующего этажа
					grid.set(j - 1, y + 1, RISER_Z, SUPPORT);
					grid.set(j - 1, y + 2, RISER_Z, repeater(1, "east"));
					for (int s = 0; s < 3; s++) {
						grid.set(j - 2 - s, y + 2 + s, RISER_Z, SUPPORT);
						grid.set(j - 2 - s, y + 3 + s, RISER_Z, DUST);
					}
				}
			}
		}
	}

	/** Расставляет пыли форму (стороны/подъёмы) — так её видит игра после обновления соседей. */
	private static void shapeDust(BlockGrid grid) {
		String[] dirs = {"north", "east", "south", "west"};
		int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					if (!grid.get(x, y, z).equals(DUST)) {
						continue;
					}
					String[] shape = new String[4];
					int connections = 0;
					for (int d = 0; d < 4; d++) {
						int nx = x + offsets[d][0];
						int nz = z + offsets[d][1];
						String side = grid.get(nx, y, nz);
						String value = "none";
						if (connectsTo(side, d)) {
							value = "side";
						} else if (isSolid(side) && !isSolid(grid.get(x, y + 1, z)) && isDust(grid.get(nx, y + 1, nz))) {
							value = "up";
						} else if (!isSolid(side) && y > 0 && isDust(grid.get(nx, y - 1, nz))) {
							value = "side";
						}
						shape[d] = value;
						if (!value.equals("none")) {
							connections++;
						}
					}
					if (connections == 1) {
						for (int d = 0; d < 4; d++) {
							if (!shape[d].equals("none") && shape[(d + 2) % 4].equals("none")) {
								shape[(d + 2) % 4] = "side";
								break;
							}
						}
					}
					grid.set(x, y, z, DUST + "[east=" + shape[1] + ",north=" + shape[0] + ",power=0,south=" + shape[2] + ",west=" + shape[3] + "]");
				}
			}
		}
	}

	private static boolean isDust(String state) {
		return BlockStates.name(state).equals(DUST);
	}

	private static boolean connectsTo(String state, int dir) {
		String name = BlockStates.name(state);
		if (name.equals(DUST) || name.equals("minecraft:stone_button")) {
			return true;
		}
		if (name.equals("minecraft:repeater")) {
			String facing = BlockStates.properties(state).get("facing");
			boolean alongX = facing.equals("east") || facing.equals("west");
			return alongX == (dir % 2 == 1);
		}
		return false;
	}

	private static boolean isSolid(String state) {
		String name = BlockStates.name(state);
		return !name.equals("minecraft:air") && !name.equals(DUST)
				&& !name.equals("minecraft:repeater") && !name.equals("minecraft:stone_button");
	}
}
