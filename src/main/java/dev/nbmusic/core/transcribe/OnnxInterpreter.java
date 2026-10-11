package dev.nbmusic.core.transcribe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Маленький интерпретатор ONNX (opset 15) ровно под операции модели Basic Pitch:
 * Conv, Pad, Reshape, Unsqueeze, Transpose, Concat, Slice, поэлементная арифметика,
 * свёртки по осям и пара служебных. Не общего назначения — неизвестная операция даёт ошибку.
 */
final class OnnxInterpreter {
	private final OnnxModel model;
	private final ForkJoinPool pool;
	/** Индекс узла, после которого значение больше не нужно. */
	private final Map<String, Integer> lastUse = new HashMap<>();

	OnnxInterpreter(OnnxModel model, ForkJoinPool pool) {
		this.model = model;
		this.pool = pool;
		for (int i = 0; i < model.nodes.size(); i++) {
			for (String in : model.nodes.get(i).inputs()) {
				lastUse.put(in, i);
			}
		}
	}

	Map<String, Tensor> run(Map<String, Tensor> inputs, Set<String> wanted) {
		Map<String, Tensor> values = new HashMap<>(model.initializers);
		values.putAll(inputs);
		Set<String> keep = new HashSet<>(wanted);
		keep.addAll(model.initializers.keySet());
		for (int i = 0; i < model.nodes.size(); i++) {
			OnnxModel.Node node = model.nodes.get(i);
			List<Tensor> in = new ArrayList<>();
			for (String name : node.inputs()) {
				if (name.isEmpty()) {
					in.add(null);
					continue;
				}
				Tensor t = values.get(name);
				if (t == null) {
					throw new IllegalStateException("нет значения " + name + " для " + node.op());
				}
				in.add(t);
			}
			Tensor out;
			try {
				out = execute(node, in);
			} catch (RuntimeException e) {
				throw new IllegalStateException("ошибка в узле " + i + " " + node.op() + " " + in, e);
			}
			values.put(node.outputs().getFirst(), out);
			for (String name : node.inputs()) {
				if (!keep.contains(name) && lastUse.getOrDefault(name, -1) == i) {
					values.remove(name);
				}
			}
		}
		Map<String, Tensor> result = new HashMap<>();
		for (String w : wanted) {
			result.put(w, values.get(w));
		}
		return result;
	}

	private Tensor execute(OnnxModel.Node node, List<Tensor> in) {
		return switch (node.op()) {
			case "Reshape" -> reshape(in.get(0), in.get(1));
			case "Unsqueeze" -> unsqueeze(in.get(0), in.get(1));
			case "Transpose" -> transpose(in.get(0), node.ints("perm", null));
			case "Concat" -> concat(in, (int) node.intAttr("axis", 0));
			case "Slice" -> slice(in);
			case "Pad" -> pad(in, node.stringAttr("mode", "constant"));
			case "Conv" -> conv(in.get(0), in.get(1), in.size() > 2 ? in.get(2) : null, node);
			case "Neg" -> unary(in.get(0), x -> -x);
			case "Relu" -> unary(in.get(0), x -> x > 0 ? x : 0);
			case "Sigmoid" -> unary(in.get(0), x -> (float) (1 / (1 + Math.exp(-x))));
			case "Sqrt" -> unary(in.get(0), x -> (float) Math.sqrt(x));
			case "Log" -> unary(in.get(0), x -> (float) Math.log(x));
			case "Add" -> binary(in.get(0), in.get(1), Float::sum, false);
			case "Sub" -> binary(in.get(0), in.get(1), (a, b) -> a - b, false);
			case "Mul" -> binary(in.get(0), in.get(1), (a, b) -> a * b, false);
			case "Div" -> binary(in.get(0), in.get(1), (a, b) -> a / b, false);
			case "Equal" -> binary(in.get(0), in.get(1), (a, b) -> a == b ? 1 : 0, true);
			case "Where" -> where(in.get(0), in.get(1), in.get(2));
			case "Cast" -> cast(in.get(0), (int) node.intAttr("to", 1));
			case "Shape" -> shape(in.get(0));
			case "ReduceSum" -> reduce(in.get(0), axesInput(in), node.intAttr("keepdims", 1) != 0,
					node.intAttr("noop_with_empty_axes", 0) != 0, Reduction.SUM);
			case "ReduceMax" -> reduce(in.get(0), node.ints("axes", null), node.intAttr("keepdims", 1) != 0, false, Reduction.MAX);
			case "ReduceMin" -> reduce(in.get(0), node.ints("axes", null), node.intAttr("keepdims", 1) != 0, false, Reduction.MIN);
			default -> throw new UnsupportedOperationException("операция " + node.op() + " не поддерживается");
		};
	}

	// ---------- форма ----------

	private static Tensor reshape(Tensor x, Tensor shapeT) {
		long[] spec = shapeT.longValues();
		int[] shape = new int[spec.length];
		int infer = -1;
		int known = 1;
		for (int i = 0; i < spec.length; i++) {
			if (spec[i] == -1) {
				infer = i;
				continue;
			}
			shape[i] = spec[i] == 0 ? x.shape[i] : (int) spec[i];
			known *= shape[i];
		}
		if (infer >= 0) {
			shape[infer] = x.size() / known;
		}
		return x.reshape(shape);
	}

	private static Tensor unsqueeze(Tensor x, Tensor axesT) {
		long[] axes = axesT.longValues();
		int outRank = x.rank() + axes.length;
		boolean[] inserted = new boolean[outRank];
		for (long a : axes) {
			inserted[(int) (a < 0 ? a + outRank : a)] = true;
		}
		int[] shape = new int[outRank];
		for (int i = 0, j = 0; i < outRank; i++) {
			shape[i] = inserted[i] ? 1 : x.shape[j++];
		}
		return x.reshape(shape);
	}

	private static Tensor shape(Tensor x) {
		long[] s = new long[x.rank()];
		for (int i = 0; i < s.length; i++) {
			s[i] = x.shape[i];
		}
		return Tensor.longs(new int[]{s.length}, s);
	}

	private static Tensor cast(Tensor x, int to) {
		int n = x.size();
		if (to == 6 || to == 7) {
			long[] out = new long[n];
			for (int i = 0; i < n; i++) {
				out[i] = (long) x.get(i);
			}
			return Tensor.longs(x.shape, out);
		}
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = (float) x.get(i);
		}
		return to == 9 ? Tensor.bools(x.shape, out) : Tensor.floats(x.shape, out);
	}

	private static Tensor transpose(Tensor x, long[] permAttr) {
		int r = x.rank();
		int[] perm = new int[r];
		for (int i = 0; i < r; i++) {
			perm[i] = permAttr == null ? r - 1 - i : (int) permAttr[i];
		}
		int[] outShape = new int[r];
		for (int i = 0; i < r; i++) {
			outShape[i] = x.shape[perm[i]];
		}
		int[] inStrides = Tensor.strides(x.shape);
		int[] srcStride = new int[r];
		for (int i = 0; i < r; i++) {
			srcStride[i] = inStrides[perm[i]];
		}
		return gather(x, outShape, srcStride, 0);
	}

	/** Копия с произвольными шагами по исходнику: out[idx] = x[offset + Σ idx_i·stride_i]. */
	private static Tensor gather(Tensor x, int[] outShape, int[] srcStride, int offset) {
		int n = Tensor.size(outShape);
		int r = outShape.length;
		int[] idx = new int[r];
		float[] of = x.isLong() ? null : new float[n];
		long[] ol = x.isLong() ? new long[n] : null;
		int src = offset;
		for (int i = 0; i < n; i++) {
			if (of != null) {
				of[i] = x.f[src];
			} else {
				ol[i] = x.l[src];
			}
			for (int d = r - 1; d >= 0; d--) {
				idx[d]++;
				src += srcStride[d];
				if (idx[d] < outShape[d]) {
					break;
				}
				src -= srcStride[d] * idx[d];
				idx[d] = 0;
			}
		}
		return like(x, outShape, of, ol);
	}

	private static Tensor like(Tensor x, int[] shape, float[] f, long[] l) {
		return switch (x.type) {
			case LONG -> Tensor.longs(shape, l);
			case BOOL -> Tensor.bools(shape, f);
			case FLOAT -> Tensor.floats(shape, f);
		};
	}

	private static Tensor concat(List<Tensor> in, int axis) {
		Tensor first = in.getFirst();
		int r = first.rank();
		if (axis < 0) {
			axis += r;
		}
		int[] outShape = first.shape.clone();
		outShape[axis] = 0;
		for (Tensor t : in) {
			outShape[axis] += t.shape[axis];
		}
		int outer = 1;
		for (int i = 0; i < axis; i++) {
			outer *= outShape[i];
		}
		int inner = 1;
		for (int i = axis + 1; i < r; i++) {
			inner *= outShape[i];
		}
		int n = Tensor.size(outShape);
		float[] of = first.isLong() ? null : new float[n];
		long[] ol = first.isLong() ? new long[n] : null;
		int outRow = outShape[axis] * inner;
		int pos = 0;
		for (Tensor t : in) {
			int block = t.shape[axis] * inner;
			for (int o = 0; o < outer; o++) {
				if (of != null) {
					System.arraycopy(t.f, o * block, of, o * outRow + pos, block);
				} else {
					System.arraycopy(t.l, o * block, ol, o * outRow + pos, block);
				}
			}
			pos += block;
		}
		return like(first, outShape, of, ol);
	}

	private static Tensor slice(List<Tensor> in) {
		Tensor x = in.get(0);
		long[] starts = in.get(1).longValues();
		long[] ends = in.get(2).longValues();
		long[] axes = in.size() > 3 && in.get(3) != null ? in.get(3).longValues() : null;
		long[] steps = in.size() > 4 && in.get(4) != null ? in.get(4).longValues() : null;
		int r = x.rank();
		int[] start = new int[r];
		int[] step = new int[r];
		int[] outShape = x.shape.clone();
		Arrays.fill(step, 1);
		for (int k = 0; k < starts.length; k++) {
			int ax = axes == null ? k : (int) (axes[k] < 0 ? axes[k] + r : axes[k]);
			long dim = x.shape[ax];
			long st = steps == null ? 1 : steps[k];
			long s = starts[k] < 0 ? starts[k] + dim : starts[k];
			long e = ends[k] < 0 ? ends[k] + dim : ends[k];
			if (st > 0) {
				s = Math.clamp(s, 0, dim);
				e = Math.clamp(e, 0, dim);
				outShape[ax] = (int) Math.max(0, (e - s + st - 1) / st);
			} else {
				s = Math.clamp(s, 0, dim - 1);
				e = Math.clamp(e, -1, dim - 1);
				outShape[ax] = (int) Math.max(0, (s - e + (-st) - 1) / (-st));
			}
			start[ax] = (int) s;
			step[ax] = (int) st;
		}
		int[] strides = Tensor.strides(x.shape);
		int[] src = new int[r];
		int offset = 0;
		for (int i = 0; i < r; i++) {
			src[i] = strides[i] * step[i];
			offset += strides[i] * start[i];
		}
		if (Tensor.size(outShape) == 0) {
			return like(x, outShape, x.isLong() ? null : new float[0], x.isLong() ? new long[0] : null);
		}
		return gather(x, outShape, src, offset);
	}

	private static Tensor pad(List<Tensor> in, String mode) {
		Tensor x = in.get(0);
		long[] pads = in.get(1).longValues();
		float value = in.size() > 2 && in.get(2) != null ? (float) in.get(2).get(0) : 0f;
		int r = x.rank();
		int[] before = new int[r];
		int[] outShape = new int[r];
		for (int i = 0; i < r; i++) {
			before[i] = (int) pads[i];
			outShape[i] = x.shape[i] + (int) pads[i] + (int) pads[i + r];
		}
		boolean reflect = mode.equals("reflect");
		if (!reflect && !mode.equals("constant")) {
			throw new UnsupportedOperationException("Pad mode " + mode);
		}
		int n = Tensor.size(outShape);
		float[] out = new float[n];
		int[] inStrides = Tensor.strides(x.shape);
		int[] idx = new int[r];
		for (int i = 0; i < n; i++) {
			int src = 0;
			boolean inside = true;
			for (int d = 0; d < r; d++) {
				int j = idx[d] - before[d];
				int dim = x.shape[d];
				if (j < 0 || j >= dim) {
					if (!reflect) {
						inside = false;
						break;
					}
					j = j < 0 ? -j : 2 * (dim - 1) - j;
				}
				src += j * inStrides[d];
			}
			out[i] = inside ? x.f[src] : value;
			for (int d = r - 1; d >= 0; d--) {
				if (++idx[d] < outShape[d]) {
					break;
				}
				idx[d] = 0;
			}
		}
		return Tensor.floats(outShape, out);
	}

	// ---------- арифметика ----------

	private interface FloatOp {
		float apply(float x);
	}

	private interface FloatBinOp {
		float apply(float a, float b);
	}

	private static Tensor unary(Tensor x, FloatOp op) {
		float[] out = new float[x.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = op.apply(x.f[i]);
		}
		return Tensor.floats(x.shape, out);
	}

	private static int[] broadcastShape(int[]... shapes) {
		int r = 0;
		for (int[] s : shapes) {
			r = Math.max(r, s.length);
		}
		int[] out = new int[r];
		Arrays.fill(out, 1);
		for (int[] s : shapes) {
			for (int i = 0; i < s.length; i++) {
				int o = r - s.length + i;
				if (s[i] != 1) {
					if (out[o] != 1 && out[o] != s[i]) {
						throw new IllegalArgumentException("несовместимые формы для broadcast");
					}
					out[o] = s[i];
				}
			}
		}
		return out;
	}

	/** Шаги исходного тензора в координатах выхода (0 по растянутым осям). */
	private static int[] broadcastStrides(int[] shape, int[] outShape) {
		int r = outShape.length;
		int[] own = Tensor.strides(shape);
		int[] s = new int[r];
		for (int i = 0; i < shape.length; i++) {
			int o = r - shape.length + i;
			s[o] = shape[i] == 1 ? 0 : own[i];
		}
		return s;
	}

	private static Tensor binary(Tensor a, Tensor b, FloatBinOp op, boolean bool) {
		int[] outShape = broadcastShape(a.shape, b.shape);
		int n = Tensor.size(outShape);
		float[] out = new float[n];
		float[] af = asFloats(a);
		float[] bf = asFloats(b);
		if (a.size() == n && b.size() == n) {
			for (int i = 0; i < n; i++) {
				out[i] = op.apply(af[i], bf[i]);
			}
		} else if (b.size() == 1 && a.size() == n) {
			float v = bf[0];
			for (int i = 0; i < n; i++) {
				out[i] = op.apply(af[i], v);
			}
		} else if (a.size() == 1 && b.size() == n) {
			float v = af[0];
			for (int i = 0; i < n; i++) {
				out[i] = op.apply(v, bf[i]);
			}
		} else {
			int[] sa = broadcastStrides(a.shape, outShape);
			int[] sb = broadcastStrides(b.shape, outShape);
			int r = outShape.length;
			int[] idx = new int[r];
			int ia = 0;
			int ib = 0;
			for (int i = 0; i < n; i++) {
				out[i] = op.apply(af[ia], bf[ib]);
				for (int d = r - 1; d >= 0; d--) {
					idx[d]++;
					ia += sa[d];
					ib += sb[d];
					if (idx[d] < outShape[d]) {
						break;
					}
					ia -= sa[d] * idx[d];
					ib -= sb[d] * idx[d];
					idx[d] = 0;
				}
			}
		}
		return bool ? Tensor.bools(outShape, out) : Tensor.floats(outShape, out);
	}

	private static float[] asFloats(Tensor t) {
		if (!t.isLong()) {
			return t.f;
		}
		float[] f = new float[t.size()];
		for (int i = 0; i < f.length; i++) {
			f[i] = t.l[i];
		}
		return f;
	}

	private static Tensor where(Tensor cond, Tensor x, Tensor y) {
		int[] outShape = broadcastShape(cond.shape, x.shape, y.shape);
		int n = Tensor.size(outShape);
		int r = outShape.length;
		int[] sc = broadcastStrides(cond.shape, outShape);
		int[] sx = broadcastStrides(x.shape, outShape);
		int[] sy = broadcastStrides(y.shape, outShape);
		float[] cf = asFloats(cond);
		float[] xf = asFloats(x);
		float[] yf = asFloats(y);
		float[] out = new float[n];
		int[] idx = new int[r];
		int ic = 0;
		int ix = 0;
		int iy = 0;
		for (int i = 0; i < n; i++) {
			out[i] = cf[ic] != 0 ? xf[ix] : yf[iy];
			for (int d = r - 1; d >= 0; d--) {
				idx[d]++;
				ic += sc[d];
				ix += sx[d];
				iy += sy[d];
				if (idx[d] < outShape[d]) {
					break;
				}
				ic -= sc[d] * idx[d];
				ix -= sx[d] * idx[d];
				iy -= sy[d] * idx[d];
				idx[d] = 0;
			}
		}
		return Tensor.floats(outShape, out);
	}

	private enum Reduction { SUM, MAX, MIN }

	private static long[] axesInput(List<Tensor> in) {
		return in.size() > 1 && in.get(1) != null ? in.get(1).longValues() : null;
	}

	private static Tensor reduce(Tensor x, long[] axesArr, boolean keepDims, boolean noopWithEmptyAxes, Reduction kind) {
		int r = x.rank();
		boolean[] reduced = new boolean[r];
		if (axesArr == null || axesArr.length == 0) {
			if (noopWithEmptyAxes) {
				return x;
			}
			Arrays.fill(reduced, true);
		} else {
			for (long a : axesArr) {
				reduced[(int) (a < 0 ? a + r : a)] = true;
			}
		}
		int[] keptShape = new int[r];
		for (int i = 0; i < r; i++) {
			keptShape[i] = reduced[i] ? 1 : x.shape[i];
		}
		int n = Tensor.size(keptShape);
		float[] out = new float[n];
		Arrays.fill(out, kind == Reduction.SUM ? 0 : kind == Reduction.MAX ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY);
		int[] outStrides = Tensor.strides(keptShape);
		int[] idx = new int[r];
		int total = x.size();
		for (int i = 0; i < total; i++) {
			int o = 0;
			for (int d = 0; d < r; d++) {
				if (!reduced[d]) {
					o += idx[d] * outStrides[d];
				}
			}
			float v = x.f[i];
			out[o] = switch (kind) {
				case SUM -> out[o] + v;
				case MAX -> Math.max(out[o], v);
				case MIN -> Math.min(out[o], v);
			};
			for (int d = r - 1; d >= 0; d--) {
				if (++idx[d] < x.shape[d]) {
					break;
				}
				idx[d] = 0;
			}
		}
		if (keepDims) {
			return Tensor.floats(keptShape, out);
		}
		int[] shape = IntStream.range(0, r).filter(i -> !reduced[i]).map(i -> x.shape[i]).toArray();
		return Tensor.floats(shape, out);
	}

	// ---------- свёртка ----------

	/** Conv 2D, NCHW, group = 1. Параллельно по строкам выхода: (n, oc, oh). */
	private Tensor conv(Tensor x, Tensor w, Tensor bias, OnnxModel.Node node) {
		if (node.intAttr("group", 1) != 1) {
			throw new UnsupportedOperationException("Conv group != 1");
		}
		if (x.rank() != 4) {
			throw new UnsupportedOperationException("Conv поддерживается только 2D");
		}
		long[] strides = node.ints("strides", new long[]{1, 1});
		long[] dil = node.ints("dilations", new long[]{1, 1});
		long[] pads = node.ints("pads", new long[]{0, 0, 0, 0});
		int batch = x.shape[0];
		int ic = x.shape[1];
		int ih = x.shape[2];
		int iw = x.shape[3];
		int oc = w.shape[0];
		int kh = w.shape[2];
		int kw = w.shape[3];
		int sh = (int) strides[0];
		int sw = (int) strides[1];
		int dh = (int) dil[0];
		int dw = (int) dil[1];
		int pt = (int) pads[0];
		int pl = (int) pads[1];
		int oh = (ih + pt + (int) pads[2] - dh * (kh - 1) - 1) / sh + 1;
		int ow = (iw + pl + (int) pads[3] - dw * (kw - 1) - 1) / sw + 1;
		float[] out = new float[batch * oc * oh * ow];
		float[] in = x.f;
		float[] wt = w.f;
		float[] b = bias == null ? null : bias.f;
		Runnable task = () -> IntStream.range(0, batch * oc * oh).parallel().forEach(row -> {
			int y = row % oh;
			int c = (row / oh) % oc;
			int nIdx = row / (oh * oc);
			int outBase = row * ow;
			if (b != null) {
				Arrays.fill(out, outBase, outBase + ow, b[c]);
			}
			for (int ci = 0; ci < ic; ci++) {
				int inChannel = (nIdx * ic + ci) * ih * iw;
				int wChannel = (c * ic + ci) * kh * kw;
				for (int ky = 0; ky < kh; ky++) {
					int yy = y * sh - pt + ky * dh;
					if (yy < 0 || yy >= ih) {
						continue;
					}
					int inRow = inChannel + yy * iw;
					for (int kx = 0; kx < kw; kx++) {
						float wv = wt[wChannel + ky * kw + kx];
						if (wv == 0) {
							continue;
						}
						int shift = kx * dw - pl;
						// допустимые ox: 0 <= ox*sw + shift < iw
						int oxFrom = shift >= 0 ? 0 : (-shift + sw - 1) / sw;
						int oxTo = Math.min(ow, (iw - 1 - shift) / sw + 1);
						if (iw - 1 - shift < 0) {
							continue;
						}
						if (sw == 1) {
							int base = inRow + shift;
							for (int ox = oxFrom; ox < oxTo; ox++) {
								out[outBase + ox] += wv * in[base + ox];
							}
						} else {
							for (int ox = oxFrom; ox < oxTo; ox++) {
								out[outBase + ox] += wv * in[inRow + ox * sw + shift];
							}
						}
					}
				}
			}
		});
		pool.submit(task).join();
		return Tensor.floats(new int[]{batch, oc, oh, ow}, out);
	}
}
