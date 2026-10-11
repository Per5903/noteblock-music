package dev.nbmusic.core.io;

/** Поиск MPEG-кадров в MP3: пропуск тегов ID3v2 / ID3v1 / APE и ложных синхрослов. */
final class Mp3Frames {
	private static final int[][] BITRATES_KBPS = {
			// MPEG-1 Layer III
			{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
			// MPEG-2 / 2.5 Layer III
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
	private static final int[][] SAMPLE_RATES = {{44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}};

	private Mp3Frames() {
	}

	/** Позиция сразу после всех ID3v2-тегов в начале файла (их может быть несколько подряд). */
	static int skipId3v2(byte[] b) {
		int pos = 0;
		while (pos + 10 <= b.length && b[pos] == 'I' && b[pos + 1] == 'D' && b[pos + 2] == '3') {
			int flags = b[pos + 5] & 0xFF;
			int size = (b[pos + 6] & 0x7F) << 21 | (b[pos + 7] & 0x7F) << 14 | (b[pos + 8] & 0x7F) << 7 | (b[pos + 9] & 0x7F);
			pos += 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
		}
		return Math.min(pos, b.length);
	}

	/** Длина кадра Layer III по заголовку в позиции {@code i}, или -1, если это не заголовок. */
	static int frameLength(byte[] b, int i) {
		if (i + 4 > b.length || (b[i] & 0xFF) != 0xFF || (b[i + 1] & 0xE0) != 0xE0) {
			return -1;
		}
		int version = (b[i + 1] >> 3) & 3; // 0 — MPEG 2.5, 2 — MPEG 2, 3 — MPEG 1
		int layer = (b[i + 1] >> 1) & 3; // 1 — Layer III
		int bitrateIndex = (b[i + 2] >> 4) & 0xF;
		int rateIndex = (b[i + 2] >> 2) & 3;
		int padding = (b[i + 2] >> 1) & 1;
		if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
			return -1;
		}
		boolean mpeg1 = version == 3;
		int bitrate = BITRATES_KBPS[mpeg1 ? 0 : 1][bitrateIndex] * 1000;
		int sampleRate = SAMPLE_RATES[mpeg1 ? 0 : version == 2 ? 1 : 2][rateIndex];
		return (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding;
	}

	/** Первый кадр, за которым ровно на своём месте идут ещё два — так ложное синхрослово не пройдёт. */
	static int firstFrame(byte[] b, int from) {
		for (int i = from; i + 4 <= b.length; i++) {
			int len = frameLength(b, i);
			if (len <= 4) {
				continue;
			}
			int next = i + len;
			int len2 = frameLength(b, next);
			if (len2 > 4 && (next + len2 >= b.length || frameLength(b, next + len2) > 4)) {
				return i;
			}
		}
		return -1;
	}

	/** Конец аудиоданных: без ID3v1 («TAG», 128 байт) и APEv2 в хвосте. */
	static int audioEnd(byte[] b) {
		int end = b.length;
		if (end >= 128 && b[end - 128] == 'T' && b[end - 127] == 'A' && b[end - 126] == 'G') {
			end -= 128;
		}
		if (end >= 32 && new String(b, end - 32, 8, java.nio.charset.StandardCharsets.ISO_8859_1).equals("APETAGEX")) {
			int size = (b[end - 20] & 0xFF) | (b[end - 19] & 0xFF) << 8 | (b[end - 18] & 0xFF) << 16 | (b[end - 17] & 0xFF) << 24;
			int flags = b[end - 9] & 0xFF;
			end -= size + ((flags & 0x80) != 0 ? 32 : 0);
		}
		return Math.max(0, end);
	}
}
