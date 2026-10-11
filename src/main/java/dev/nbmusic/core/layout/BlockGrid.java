package dev.nbmusic.core.layout;

import java.util.HashMap;
import java.util.Map;

/** Разреженная сетка блоков; пустые клетки — воздух. Состояния в синтаксисе команд: {@code minecraft:repeater[delay=2,...]}. */
public final class BlockGrid {
	public static final String AIR = "minecraft:air";

	private final Map<Long, String> blocks = new HashMap<>();
	private int width;
	private int height;
	private int length;

	public void set(int x, int y, int z, String state) {
		if (x < 0 || y < 0 || z < 0 || x >= 1 << 20 || y >= 1 << 20 || z >= 1 << 20) {
			throw new IllegalArgumentException("position out of range: " + x + " " + y + " " + z);
		}
		blocks.put(key(x, y, z), state);
		width = Math.max(width, x + 1);
		height = Math.max(height, y + 1);
		length = Math.max(length, z + 1);
	}

	public String get(int x, int y, int z) {
		return blocks.getOrDefault(key(x, y, z), AIR);
	}

	/** Расширить габарит (например, чтобы над нотными блоками гарантированно был воздух). */
	public void ensureSize(int width, int height, int length) {
		this.width = Math.max(this.width, width);
		this.height = Math.max(this.height, height);
		this.length = Math.max(this.length, length);
	}

	/** Размер по X — вдоль песни. */
	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Размер по Z — поперёк дорожек. */
	public int length() {
		return length;
	}

	public int nonAirCount() {
		return blocks.size();
	}

	private static long key(int x, int y, int z) {
		return (long) x << 40 | (long) y << 20 | z;
	}
}
