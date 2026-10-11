package dev.nbmusic.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UploadBufferTest {
	@Test
	void assemblesChunksInOrder() {
		byte[] file = new byte[UploadBuffer.CHUNK_BYTES * 2 + 123];
		new Random(1).nextBytes(file);
		int total = UploadBuffer.chunkCount(file.length);
		assertEquals(3, total);
		UploadBuffer buffer = new UploadBuffer();
		for (int i = 0; i < total; i++) {
			int from = i * UploadBuffer.CHUNK_BYTES;
			assertTrue(buffer.accept(i, total, Arrays.copyOfRange(file, from, Math.min(file.length, from + UploadBuffer.CHUNK_BYTES))));
			assertEquals(i == total - 1, buffer.isComplete());
		}
		assertArrayEquals(file, buffer.bytes());
	}

	@Test
	void rejectsOutOfOrderAndOversize() {
		UploadBuffer buffer = new UploadBuffer();
		assertTrue(buffer.accept(0, 3, new byte[10]));
		assertFalse(buffer.accept(2, 3, new byte[10]), "пропущенный кусок");
		assertFalse(buffer.isComplete());
		assertFalse(buffer.accept(0, 1000, new byte[10]), "заявлено больше 2 МБ");
		// новая загрузка после сброса работает
		assertTrue(buffer.accept(0, 1, new byte[]{1, 2, 3}));
		assertTrue(buffer.isComplete());
		assertArrayEquals(new byte[]{1, 2, 3}, buffer.bytes());
	}
}
