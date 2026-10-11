package dev.nbmusic.core.io;

import java.util.stream.IntStream;

/**
 * Передискретизация оконным sinc-фильтром (окно Блэкмана, 16 нулей с каждой стороны).
 * При понижении частоты срез сдвигается ниже новой частоты Найквиста — без наложения спектров.
 * Ядро фильтра берётся из заранее посчитанной таблицы (без sin/cos на каждый отвод), выход
 * считается параллельно кусками.
 */
public final class Resampler {
	private static final int ZEROS = 16;
	/** Точек таблицы на один переход через ноль; между ними — линейная интерполяция. */
	private static final int TABLE_RES = 512;
	private static final float[] KERNEL = new float[ZEROS * TABLE_RES + 2];

	static {
		for (int i = 0; i < KERNEL.length; i++) {
			double x = i / (double) TABLE_RES;
			KERNEL[i] = x >= ZEROS ? 0f : (float) (sinc(x) * window(x));
		}
	}

	private Resampler() {
	}

	public static float[] resample(float[] in, int fromRate, int toRate) {
		if (fromRate == toRate) {
			return in;
		}
		double ratio = (double) fromRate / toRate;
		int outLen = (int) Math.floor(in.length / ratio);
		float[] out = new float[outLen];
		// полоса пропускания относительно входной частоты Найквиста
		double cutoff = Math.min(1.0, 1.0 / ratio) * 0.97;
		double halfWidth = ZEROS / cutoff;
		int chunk = 1 << 14;
		IntStream.range(0, (outLen + chunk - 1) / chunk).parallel().forEach(part -> {
			int end = Math.min(outLen, (part + 1) * chunk);
			for (int i = part * chunk; i < end; i++) {
				double t = i * ratio;
				int from = Math.max(0, (int) Math.ceil(t - halfWidth));
				int to = Math.min(in.length - 1, (int) Math.floor(t + halfWidth));
				double sum = 0;
				for (int k = from; k <= to; k++) {
					sum += in[k] * kernel(Math.abs((t - k) * cutoff));
				}
				out[i] = (float) (sum * cutoff);
			}
		});
		return out;
	}

	private static float kernel(double ax) {
		double pos = ax * TABLE_RES;
		int i = (int) pos;
		if (i >= KERNEL.length - 1) {
			return 0f;
		}
		float frac = (float) (pos - i);
		return KERNEL[i] + (KERNEL[i + 1] - KERNEL[i]) * frac;
	}

	private static double window(double x) {
		return 0.42 + 0.5 * Math.cos(Math.PI * x / ZEROS) + 0.08 * Math.cos(2 * Math.PI * x / ZEROS);
	}

	private static double sinc(double x) {
		if (Math.abs(x) < 1e-9) {
			return 1;
		}
		double px = Math.PI * x;
		return Math.sin(px) / px;
	}
}
