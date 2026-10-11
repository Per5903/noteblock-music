package dev.nbmusic.core.export;

import java.util.List;

/** Доступ тестов к упаковке битов Litematica. */
public final class LitematicTestAccess {
	private LitematicTestAccess() {
	}

	public static long[] pack(int[] values, int bits) {
		return LitematicWriter.pack(values, bits);
	}

	public static List<Integer> unpack(long[] data, int bits, int count) {
		return LitematicWriter.unpack(data, bits, count);
	}

	public static int bitsFor(int paletteSize) {
		return LitematicWriter.bitsFor(paletteSize);
	}
}
