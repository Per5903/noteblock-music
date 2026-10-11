package dev.nbmusic.core;

import java.io.ByteArrayOutputStream;

/**
 * Сборка файла из кусков, пришедших по сети по порядку (пакет от клиента ограничен 32 КБ).
 * Кусок с индексом 0 начинает новую загрузку.
 */
public final class UploadBuffer {
	public static final int MAX_BYTES = 2 * 1024 * 1024;
	public static final int CHUNK_BYTES = 30_000;

	private ByteArrayOutputStream data = new ByteArrayOutputStream();
	private int expectedIndex;
	private int total = -1;

	/** @return false, если кусок пришёл не по порядку или файл слишком большой — загрузка сброшена */
	public boolean accept(int index, int total, byte[] chunk) {
		if (index == 0) {
			data = new ByteArrayOutputStream();
			expectedIndex = 0;
			this.total = total;
		}
		if (index != expectedIndex || total != this.total || total <= 0
				|| (long) total * CHUNK_BYTES > MAX_BYTES + CHUNK_BYTES || data.size() + chunk.length > MAX_BYTES) {
			reset();
			return false;
		}
		data.writeBytes(chunk);
		expectedIndex++;
		return true;
	}

	public boolean isComplete() {
		return total > 0 && expectedIndex == total;
	}

	public byte[] bytes() {
		return data.toByteArray();
	}

	public void reset() {
		data = new ByteArrayOutputStream();
		expectedIndex = 0;
		total = -1;
	}

	/** Сколько кусков нужно для файла такого размера. */
	public static int chunkCount(int size) {
		return Math.max(1, (size + CHUNK_BYTES - 1) / CHUNK_BYTES);
	}
}
