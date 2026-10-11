package dev.nbmusic.core.export;

import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.BlockStates;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Ванильная структура (.nbt) — для {@code /place template} и структурных блоков.
 * Воздух тоже записывается, чтобы вставка очищала место (как и .schem).
 * Структурный блок загружает максимум 48×48×48; {@code /place template} — любой размер.
 */
public final class StructureWriter {
	public static final int STRUCTURE_BLOCK_LIMIT = 48;

	private StructureWriter() {
	}

	public static void write(BlockGrid grid, int dataVersion, OutputStream target) throws IOException {
		int w = grid.width();
		int h = grid.height();
		int l = grid.length();
		Map<String, Integer> palette = new LinkedHashMap<>();
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					palette.computeIfAbsent(grid.get(x, y, z), k -> palette.size());
				}
			}
		}
		try (NbtWriter nbt = new NbtWriter(new GZIPOutputStream(target))) {
			nbt.beginCompound("").putInt("DataVersion", dataVersion);
			nbt.putIntList("size", w, h, l);
			nbt.beginList("palette", NbtWriter.COMPOUND, palette.size());
			for (String state : palette.keySet()) {
				nbt.putString("Name", BlockStates.name(state));
				Map<String, String> props = BlockStates.properties(state);
				if (!props.isEmpty()) {
					nbt.beginCompound("Properties");
					for (var e : props.entrySet()) {
						nbt.putString(e.getKey(), e.getValue());
					}
					nbt.endCompound();
				}
				nbt.endCompound();
			}
			nbt.beginList("blocks", NbtWriter.COMPOUND, w * h * l);
			for (int y = 0; y < h; y++) {
				for (int z = 0; z < l; z++) {
					for (int x = 0; x < w; x++) {
						nbt.putIntList("pos", x, y, z);
						nbt.putInt("state", palette.get(grid.get(x, y, z)));
						nbt.endCompound();
					}
				}
			}
			nbt.beginList("entities", NbtWriter.TAG_END, 0);
			nbt.endCompound();
		}
	}

	public static boolean fitsStructureBlock(BlockGrid grid) {
		return grid.width() <= STRUCTURE_BLOCK_LIMIT && grid.height() <= STRUCTURE_BLOCK_LIMIT
				&& grid.length() <= STRUCTURE_BLOCK_LIMIT;
	}
}
