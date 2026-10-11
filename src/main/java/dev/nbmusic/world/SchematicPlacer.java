package dev.nbmusic.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbmusic.core.layout.BlockGrid;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Ставит {@link BlockGrid} в мир целиком, включая воздух. */
public final class SchematicPlacer {
	private SchematicPlacer() {
	}

	public static void place(ServerLevel level, BlockPos origin, BlockGrid grid) {
		Map<String, BlockState> cache = new HashMap<>();
		// Без обновления соседей: пыль сохраняет заданную форму, механизм не срабатывает при постройке
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					BlockState state = cache.computeIfAbsent(grid.get(x, y, z), SchematicPlacer::parse);
					level.setBlock(origin.offset(x, y, z), state, flags);
				}
			}
		}
	}

	private static BlockState parse(String state) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("Некорректное состояние блока: " + state, e);
		}
	}
}
