package dev.nbmusic.core.export;

import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.BlockStates;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Схематика Litematica (.litematic, версия 6) с одним регионом.
 * Блоки: индекс = x + z·W + y·W·L, индексы палитры плотно упакованы в long[]
 * по {@code max(2, ⌈log2 |палитра|⌉)} бит, без выравнивания по границам long.
 */
public final class LitematicWriter {
	static final int VERSION = 6;

	private LitematicWriter() {
	}

	public static void write(BlockGrid grid, String name, String author, int dataVersion, OutputStream target) throws IOException {
		int w = grid.width();
		int h = grid.height();
		int l = grid.length();
		int volume = w * h * l;

		Map<String, Integer> palette = new LinkedHashMap<>();
		palette.put(BlockGrid.AIR, 0);
		int[] ids = new int[volume];
		int nonAir = 0;
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					String s = grid.get(x, y, z);
					ids[index(x, y, z, w, l)] = palette.computeIfAbsent(s, k -> palette.size());
					if (!s.equals(BlockGrid.AIR)) {
						nonAir++;
					}
				}
			}
		}
		long[] packed = pack(ids, bitsFor(palette.size()));
		long now = System.currentTimeMillis();

		try (NbtWriter nbt = new NbtWriter(new GZIPOutputStream(target))) {
			nbt.beginCompound("")
					.putInt("MinecraftDataVersion", dataVersion)
					.putInt("Version", VERSION);
			nbt.beginCompound("Metadata")
					.putString("Name", name)
					.putString("Author", author)
					.putString("Description", "NoteBlock Music")
					.putInt("RegionCount", 1)
					.putInt("TotalBlocks", nonAir)
					.putInt("TotalVolume", volume)
					.putLong("TimeCreated", now)
					.putLong("TimeModified", now);
			vec(nbt, "EnclosingSize", w, h, l);
			nbt.endCompound();

			nbt.beginCompound("Regions");
			nbt.beginCompound(name.isBlank() ? "song" : name);
			vec(nbt, "Position", 0, 0, 0);
			vec(nbt, "Size", w, h, l);
			nbt.beginList("BlockStatePalette", NbtWriter.COMPOUND, palette.size());
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
			nbt.putLongArray("BlockStates", packed);
			nbt.beginList("TileEntities", NbtWriter.TAG_END, 0);
			nbt.beginList("Entities", NbtWriter.TAG_END, 0);
			nbt.beginList("PendingBlockTicks", NbtWriter.TAG_END, 0);
			nbt.beginList("PendingFluidTicks", NbtWriter.TAG_END, 0);
			nbt.endCompound(); // регион
			nbt.endCompound(); // Regions
			nbt.endCompound(); // корень
		}
	}

	static int index(int x, int y, int z, int w, int l) {
		return x + z * w + y * w * l;
	}

	static int bitsFor(int paletteSize) {
		return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
	}

	/** Плотная упаковка: значение i занимает биты [i·bits, (i+1)·bits), может переходить через границу long. */
	static long[] pack(int[] values, int bits) {
		long[] out = new long[(int) (((long) values.length * bits + 63) / 64)];
		long mask = (1L << bits) - 1;
		for (int i = 0; i < values.length; i++) {
			long start = (long) i * bits;
			int word = (int) (start >>> 6);
			int offset = (int) (start & 63);
			long v = values[i] & mask;
			out[word] |= v << offset;
			if (offset + bits > 64) {
				out[word + 1] |= v >>> (64 - offset);
			}
		}
		return out;
	}

	private static void vec(NbtWriter nbt, String name, int x, int y, int z) throws IOException {
		nbt.beginCompound(name).putInt("x", x).putInt("y", y).putInt("z", z).endCompound();
	}

	/** Для тестов: обратная распаковка. */
	static List<Integer> unpack(long[] data, int bits, int count) {
		List<Integer> out = new ArrayList<>(count);
		long mask = (1L << bits) - 1;
		for (int i = 0; i < count; i++) {
			long start = (long) i * bits;
			int word = (int) (start >>> 6);
			int offset = (int) (start & 63);
			long v = data[word] >>> offset;
			if (offset + bits > 64) {
				v |= data[word + 1] << (64 - offset);
			}
			out.add((int) (v & mask));
		}
		return out;
	}
}
