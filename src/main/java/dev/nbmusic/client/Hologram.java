package dev.nbmusic.client;

import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.BlockStates;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.world.SchematicPlacer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Голограмма схемы: рамка, цветные нотные блоки (цвет — инструмент) и красная кнопка запуска.
 * Рисуется гизмо, которые живут один клиентский тик, поэтому её надо пересылать каждый тик.
 */
public final class Hologram {
	/** Больше нотных блоков не рисуем: голограмма должна оставаться лёгкой. */
	private static final int MAX_NOTE_BLOCKS = 4000;

	private static Pipeline.Result cachedResult;
	private static ClientSession.Placement cachedPlacement;
	private static List<BlockPos> notePositions = List.of();
	private static List<Integer> noteColors = List.of();
	private static AABB bounds;
	private static BlockPos button;

	private Hologram() {
	}

	public static void tick(Minecraft mc) {
		ClientSession s = ClientSession.INSTANCE;
		if (!s.hologram || s.result == null || mc.player == null || mc.level == null) {
			return;
		}
		ClientSession.Placement p = s.placement(mc.player);
		if (s.result != cachedResult || !p.equals(cachedPlacement)) {
			rebuild(s.result, p);
		}
		Gizmos.cuboid(bounds, GizmoStyle.stroke(0xFFFFFFFF, 2f));
		for (int i = 0; i < notePositions.size(); i++) {
			Gizmos.cuboid(notePositions.get(i), GizmoStyle.fill(noteColors.get(i)));
		}
		Gizmos.cuboid(button, GizmoStyle.strokeAndFill(0xFFFF2020, 3f, 0x80FF2020));
	}

	private static void rebuild(Pipeline.Result result, ClientSession.Placement p) {
		cachedResult = result;
		cachedPlacement = p;
		BlockGrid grid = result.layout().grid();
		List<BlockPos> positions = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		for (int y = 0; y < grid.height() && positions.size() < MAX_NOTE_BLOCKS; y++) {
			for (int z = 0; z < grid.length() && positions.size() < MAX_NOTE_BLOCKS; z++) {
				for (int x = 0; x < grid.width() && positions.size() < MAX_NOTE_BLOCKS; x++) {
					String state = grid.get(x, y, z);
					if (!BlockStates.name(state).equals("minecraft:note_block")) {
						continue;
					}
					positions.add(SchematicPlacer.toWorld(p.anchor(), p.forward(), x, y, z));
					String inst = BlockStates.properties(state).get("instrument");
					colors.add(color(Instrument.valueOf(inst.toUpperCase(Locale.ROOT))));
				}
			}
		}
		notePositions = positions;
		noteColors = colors;
		BlockPos a = SchematicPlacer.toWorld(p.anchor(), p.forward(), 0, 0, 0);
		BlockPos b = SchematicPlacer.toWorld(p.anchor(), p.forward(), grid.width() - 1, grid.height() - 1, grid.length() - 1);
		bounds = AABB.encapsulatingFullBlocks(a, b);
		button = SchematicPlacer.toWorld(p.anchor(), p.forward(), result.layout().button());
	}

	/** Полупрозрачный цвет инструмента (ARGB). */
	static int color(Instrument instrument) {
		int rgb = switch (instrument) {
			case HARP -> 0x4FC3F7;
			case BASS, DIDGERIDOO -> 0x8D6E63;
			case BASEDRUM, SNARE, HAT -> 0x9E9E9E;
			case GUITAR, BANJO -> 0xFFB74D;
			case FLUTE -> 0x81C784;
			case BELL, CHIME, XYLOPHONE, IRON_XYLOPHONE, COW_BELL -> 0xFFF176;
			case BIT, PLING -> 0xBA68C8;
		};
		return 0x90000000 | rgb;
	}
}
