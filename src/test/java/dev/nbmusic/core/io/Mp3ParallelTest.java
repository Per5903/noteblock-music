package dev.nbmusic.core.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Параллельное декодирование MP3 кусками даёт тот же звук, что и целиком. */
class Mp3ParallelTest {
	@Test
	void parallelMatchesSequential() throws Exception {
		byte[] one;
		try (InputStream in = Mp3ParallelTest.class.getResourceAsStream("/audio/melody.mp3")) {
			one = in.readAllBytes();
		}
		ByteArrayOutputStream many = new ByteArrayOutputStream();
		for (int i = 0; i < 6; i++) {
			many.writeBytes(one); // ~36 с — больше порога деления на куски
		}
		byte[] data = many.toByteArray();
		int frames = Mp3Frames.frameOffsets(data, Mp3Frames.firstFrame(data, 0), data.length).length;
		assertTrue(frames > 1000, "кадров " + frames);

		long t0 = System.nanoTime();
		AudioDecoder.Audio parallel = AudioDecoder.decode("mp3", data);
		long t1 = System.nanoTime();
		AudioDecoder.Audio sequential = AudioDecoder.mp3Sequential(data);
		long t2 = System.nanoTime();
		System.out.printf("MP3 %d кадров: кусками %d мс, целиком %d мс%n", frames, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);

		assertEquals(sequential.sampleRate(), parallel.sampleRate());
		assertEquals(sequential.samples().length, parallel.samples().length);
		float maxDiff = 0;
		for (int i = 0; i < parallel.samples().length; i++) {
			maxDiff = Math.max(maxDiff, Math.abs(parallel.samples()[i] - sequential.samples()[i]));
		}
		assertTrue(maxDiff < 1e-3f, "наибольшее расхождение " + maxDiff);
	}
}
