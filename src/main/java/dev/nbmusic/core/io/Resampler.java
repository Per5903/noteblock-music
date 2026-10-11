package dev.nbmusic.core.io;

/**
 * Передискретизация оконным sinc-фильтром (окно Блэкмана, 16 нулей с каждой стороны).
 * При понижении частоты срез сдвигается ниже новой частоты Найквиста — без наложения спектров.
 */
public final class Resampler {
	private static final int ZEROS = 16;

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
		for (int i = 0; i < outLen; i++) {
			double t = i * ratio;
			int from = (int) Math.ceil(t - halfWidth);
			int to = (int) Math.floor(t + halfWidth);
			double sum = 0;
			for (int k = Math.max(0, from); k <= Math.min(in.length - 1, to); k++) {
				double x = (t - k) * cutoff;
				double w = 0.42 + 0.5 * Math.cos(Math.PI * x / ZEROS) + 0.08 * Math.cos(2 * Math.PI * x / ZEROS);
				sum += in[k] * sinc(x) * w;
			}
			out[i] = (float) (sum * cutoff);
		}
		return out;
	}

	private static double sinc(double x) {
		if (Math.abs(x) < 1e-9) {
			return 1;
		}
		double px = Math.PI * x;
		return Math.sin(px) / px;
	}
}
