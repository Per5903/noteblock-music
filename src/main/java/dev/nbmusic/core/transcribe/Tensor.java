package dev.nbmusic.core.transcribe;

import java.util.Arrays;

/**
 * Плотный тензор в построчном порядке (row-major). Вещественные и булевы значения лежат
 * во {@link #f}, целые — в {@link #l}.
 */
final class Tensor {
	enum Type { FLOAT, LONG, BOOL }

	final Type type;
	final int[] shape;
	final float[] f;
	final long[] l;

	private Tensor(Type type, int[] shape, float[] f, long[] l) {
		this.type = type;
		this.shape = shape;
		this.f = f;
		this.l = l;
		if (size(shape) != (f != null ? f.length : l.length)) {
			throw new IllegalArgumentException("shape " + Arrays.toString(shape) + " does not match data");
		}
	}

	static Tensor floats(int[] shape, float[] data) {
		return new Tensor(Type.FLOAT, shape, data, null);
	}

	static Tensor bools(int[] shape, float[] data) {
		return new Tensor(Type.BOOL, shape, data, null);
	}

	static Tensor longs(int[] shape, long[] data) {
		return new Tensor(Type.LONG, shape, null, data);
	}

	boolean isLong() {
		return type == Type.LONG;
	}

	int rank() {
		return shape.length;
	}

	int size() {
		return size(shape);
	}

	/** Та же память, другая форма. */
	Tensor reshape(int[] newShape) {
		return new Tensor(type, newShape, f, l);
	}

	double get(int i) {
		return isLong() ? l[i] : f[i];
	}

	long[] longValues() {
		if (isLong()) {
			return l;
		}
		long[] out = new long[f.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = (long) f[i];
		}
		return out;
	}

	static int size(int[] shape) {
		int n = 1;
		for (int d : shape) {
			n *= d;
		}
		return n;
	}

	static int[] strides(int[] shape) {
		int[] s = new int[shape.length];
		int acc = 1;
		for (int i = shape.length - 1; i >= 0; i--) {
			s[i] = acc;
			acc *= shape[i];
		}
		return s;
	}

	@Override
	public String toString() {
		return type + Arrays.toString(shape);
	}
}
