package dev.nbmusic.core.io;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * OGG Vorbis через stb_vorbis из LWJGL клиента. Загружается только когда LWJGL доступен
 * (см. {@link AudioDecoder}). Вся память, которую мы выделяем, принадлежит Java; память
 * декодера освобождает сам stb_vorbis в {@code stb_vorbis_close}.
 */
final class OggDecoder {
	private OggDecoder() {
	}

	static AudioDecoder.Audio decode(byte[] data) throws IOException {
		ByteBuffer encoded = ByteBuffer.allocateDirect(data.length);
		encoded.put(data).flip();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.mallocInt(1);
			long handle = STBVorbis.stb_vorbis_open_memory(encoded, error, null);
			if (handle == 0) {
				throw new IOException("OGG не распознан (нужен Vorbis), код " + error.get(0));
			}
			try {
				STBVorbisInfo info = STBVorbisInfo.malloc(stack);
				STBVorbis.stb_vorbis_get_info(handle, info);
				int channels = info.channels();
				int rate = info.sample_rate();
				int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
				if (channels <= 0 || rate <= 0 || frames <= 0) {
					throw new IOException("OGG без звука");
				}
				if ((long) frames > (long) AudioDecoder.MAX_SECONDS * rate) {
					throw new IOException("Слишком длинный трек (максимум " + AudioDecoder.MAX_SECONDS / 60 + " мин)");
				}
				ShortBuffer pcm = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
				int decoded = 0;
				while (pcm.hasRemaining()) {
					int n = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, pcm);
					if (n <= 0) {
						break;
					}
					decoded += n;
					pcm.position(decoded * channels);
				}
				float[] mono = new float[decoded];
				for (int i = 0; i < decoded; i++) {
					float sum = 0;
					for (int c = 0; c < channels; c++) {
						sum += pcm.get(i * channels + c);
					}
					mono[i] = sum / channels / 32768f;
				}
				return new AudioDecoder.Audio(mono, rate);
			} finally {
				STBVorbis.stb_vorbis_close(handle);
			}
		} finally {
			// stb_vorbis читает прямо из этого буфера — он должен жить до close
			Reference.reachabilityFence(encoded);
		}
	}
}
