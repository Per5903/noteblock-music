package dev.nbmusic.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.BlockStates;
import dev.nbmusic.core.layout.GridPos;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Ставит {@link BlockGrid} в мир целиком, включая воздух. Ось X сетки направляется по
 * {@code forward}, ось Z — вправо от неё, состояния блоков поворачиваются вместе с ней.
 */
public final class SchematicPlacer {
	private SchematicPlacer() {
	}

	/** Мировая позиция клетки сетки при начале {@code anchor} и направлении {@code forward}. */
	public static BlockPos toWorld(BlockPos anchor, Direction forward, int x, int y, int z) {
		return anchor.relative(forward, x).relative(forward.getClockWise(), z).above(y);
	}

	public static BlockPos toWorld(BlockPos anchor, Direction forward, GridPos pos) {
		return toWorld(anchor, forward, pos.x(), pos.y(), pos.z());
	}

	/**
	 * Начало сетки так, чтобы схема начиналась в 2 блоках перед игроком, кнопка была
	 * напротив него, а уровень механизма первого этажа — на уровне ног.
	 */
	public static BlockPos anchorInFrontOf(BlockPos feet, Direction forward, GridPos button) {
		return feet.relative(forward, 2).relative(forward.getClockWise(), -button.z()).below(button.y());
	}

	public static void place(ServerLevel level, BlockPos anchor, Direction forward, BlockGrid grid) {
		// Сетка построена для взгляда на восток; поворот — четверти по часовой от востока
		int turns = Math.floorMod(forward.get2DDataValue() - Direction.EAST.get2DDataValue(), 4);
		Map<String, BlockState> cache = new HashMap<>();
		// Без обновления соседей: пыль сохраняет заданную форму, механизм не срабатывает при постройке
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					BlockState state = cache.computeIfAbsent(grid.get(x, y, z), s -> parse(BlockStates.rotate(s, turns)));
					level.setBlock(toWorld(anchor, forward, x, y, z), state, flags);
				}
			}
		}
	}

	/** Строка состояния вида {@code minecraft:repeater[delay=2,...]} → состояние блока. */
	public static BlockState parse(String state) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("Invalid block state: " + state, e);
		}
	}
}
