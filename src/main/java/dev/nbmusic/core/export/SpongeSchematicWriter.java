package dev.nbmusic.core.export;

import dev.nbmusic.core.layout.BlockGrid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Sponge Schematic v3 (.schem) — формат WorldEdit / FAWE.
 * Порядок блоков: index = x + z * Width + y * Width * Length, индексы палитры — varint.
 */
public final class SpongeSchematicWriter {
	private SpongeSchematicWriter() {
	}

	public static void write(BlockGrid grid, String name, String author, int dataVersion, OutputStream target) throws IOException {
		int w = grid.width();
		int h = grid.height();
		int l = grid.length();
		if (w > 0xFFFF || h > 0xFFFF || l > 0xFFFF) {
			throw new IOException("Схематика больше 65535 блоков по одной оси: " + w + "×" + h + "×" + l);
		}

		Map<String, Integer> palette = new LinkedHashMap<>();
		palette.put(BlockGrid.AIR, 0);
		ByteArrayOutputStream data = new ByteArrayOutputStream(w * h * l);
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					int id = palette.computeIfAbsent(grid.get(x, y, z), s -> palette.size());
					writeVarInt(data, id);
				}
			}
		}

		try (NbtWriter nbt = new NbtWriter(new GZIPOutputStream(target))) {
			nbt.beginCompound("");
			nbt.beginCompound("Schematic")
					.putInt("Version", 3)
					.putInt("DataVersion", dataVersion)
					.putShort("Width", w)
					.putShort("Height", h)
					.putShort("Length", l)
					.putIntArray("Offset", 0, 0, 0);
			nbt.beginCompound("Metadata")
					.putString("Name", name)
					.putString("Author", author)
					.putLong("Date", System.currentTimeMillis())
					.endCompound();
			nbt.beginCompound("Blocks");
			nbt.beginCompound("Palette");
			for (var e : palette.entrySet()) {
				nbt.putInt(e.getKey(), e.getValue());
			}
			nbt.endCompound();
			nbt.putByteArray("Data", data.toByteArray());
			nbt.endCompound(); // Blocks
			nbt.endCompound(); // Schematic
			nbt.endCompound(); // root
		}
	}

	static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write(value & 0x7F | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}
}
