package dev.nbmusic.core.transcribe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор файла ONNX (protobuf ModelProto) без сторонних библиотек: узлы графа,
 * их атрибуты и константы. Поддерживается ровно то, что встречается в модели Basic Pitch.
 */
final class OnnxModel {
	private static final int TYPE_FLOAT = 1;
	private static final int TYPE_INT32 = 6;
	private static final int TYPE_INT64 = 7;
	private static final int TYPE_BOOL = 9;

	record Node(String op, List<String> inputs, List<String> outputs, Map<String, Attribute> attributes) {
		Attribute attr(String name) {
			return attributes.get(name);
		}

		long[] ints(String name, long[] fallback) {
			Attribute a = attributes.get(name);
			return a == null ? fallback : a.ints();
		}

		long intAttr(String name, long fallback) {
			Attribute a = attributes.get(name);
			return a == null ? fallback : a.i();
		}

		String stringAttr(String name, String fallback) {
			Attribute a = attributes.get(name);
			return a == null || a.s() == null ? fallback : a.s();
		}
	}

	record Attribute(long i, float f, String s, long[] ints, Tensor t) {
	}

	final List<Node> nodes = new ArrayList<>();
	final Map<String, Tensor> initializers = new HashMap<>();
	final List<String> inputs = new ArrayList<>();
	final List<String> outputs = new ArrayList<>();

	static OnnxModel parse(byte[] bytes) throws IOException {
		OnnxModel m = new OnnxModel();
		Reader model = new Reader(bytes, 0, bytes.length);
		while (model.more()) {
			int key = (int) model.varint();
			if (key >>> 3 == 7 && (key & 7) == 2) {
				m.readGraph(model.sub());
			} else {
				model.skip(key & 7);
			}
		}
		if (m.nodes.isEmpty()) {
			throw new IOException("в ONNX-файле нет графа");
		}
		return m;
	}

	private void readGraph(Reader r) throws IOException {
		Map<String, Boolean> initialized = new HashMap<>();
		List<String> declaredInputs = new ArrayList<>();
		while (r.more()) {
			int key = (int) r.varint();
			int field = key >>> 3;
			if ((key & 7) != 2) {
				r.skip(key & 7);
				continue;
			}
			Reader sub = r.sub();
			switch (field) {
				case 1 -> nodes.add(readNode(sub));
				case 5 -> {
					NamedTensor t = readTensor(sub);
					initializers.put(t.name, t.tensor);
					initialized.put(t.name, true);
				}
				case 11 -> declaredInputs.add(readValueInfoName(sub));
				case 12 -> outputs.add(readValueInfoName(sub));
				default -> {
				}
			}
		}
		for (String in : declaredInputs) {
			if (!initialized.containsKey(in)) {
				inputs.add(in);
			}
		}
	}

	private static Node readNode(Reader r) throws IOException {
		List<String> in = new ArrayList<>();
		List<String> out = new ArrayList<>();
		String op = "";
		Map<String, Attribute> attrs = new HashMap<>();
		while (r.more()) {
			int key = (int) r.varint();
			if ((key & 7) != 2) {
				r.skip(key & 7);
				continue;
			}
			switch (key >>> 3) {
				case 1 -> in.add(r.string());
				case 2 -> out.add(r.string());
				case 4 -> op = r.string();
				case 5 -> {
					Reader a = r.sub();
					String[] name = new String[1];
					Attribute attr = readAttribute(a, name);
					attrs.put(name[0], attr);
				}
				default -> r.skipBytes();
			}
		}
		return new Node(op, in, out, attrs);
	}

	private static Attribute readAttribute(Reader r, String[] nameOut) throws IOException {
		long i = 0;
		float f = 0;
		String s = null;
		List<Long> ints = new ArrayList<>();
		Tensor t = null;
		while (r.more()) {
			int key = (int) r.varint();
			int field = key >>> 3;
			int wire = key & 7;
			switch (field) {
				case 1 -> nameOut[0] = r.string();
				case 2 -> f = r.fixed32Float();
				case 3 -> i = r.varint();
				case 4 -> s = r.string();
				case 5 -> t = readTensor(r.sub()).tensor;
				case 8 -> {
					if (wire == 2) {
						Reader p = r.sub();
						while (p.more()) {
							ints.add(p.varint());
						}
					} else {
						ints.add(r.varint());
					}
				}
				default -> r.skip(wire);
			}
		}
		long[] arr = ints.stream().mapToLong(Long::longValue).toArray();
		return new Attribute(i, f, s, arr, t);
	}

	private record NamedTensor(String name, Tensor tensor) {
	}

	private static NamedTensor readTensor(Reader r) throws IOException {
		List<Long> dims = new ArrayList<>();
		int type = 0;
		String name = "";
		byte[] raw = null;
		List<Float> floats = new ArrayList<>();
		List<Long> longs = new ArrayList<>();
		while (r.more()) {
			int key = (int) r.varint();
			int field = key >>> 3;
			int wire = key & 7;
			switch (field) {
				case 1 -> {
					if (wire == 2) {
						Reader p = r.sub();
						while (p.more()) {
							dims.add(p.varint());
						}
					} else {
						dims.add(r.varint());
					}
				}
				case 2 -> type = (int) r.varint();
				case 4 -> {
					if (wire == 2) {
						Reader p = r.sub();
						while (p.more()) {
							floats.add(p.fixed32Float());
						}
					} else {
						floats.add(r.fixed32Float());
					}
				}
				case 5, 7 -> {
					if (wire == 2) {
						Reader p = r.sub();
						while (p.more()) {
							longs.add(p.varint());
						}
					} else {
						longs.add(r.varint());
					}
				}
				case 8 -> name = r.string();
				case 9 -> raw = r.bytes();
				default -> r.skip(wire);
			}
		}
		int[] shape = dims.stream().mapToInt(Long::intValue).toArray();
		int n = Tensor.size(shape);
		Tensor t;
		if (type == TYPE_FLOAT) {
			float[] data = new float[n];
			if (raw != null) {
				ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data);
			} else {
				for (int k = 0; k < n; k++) {
					data[k] = floats.get(k);
				}
			}
			t = Tensor.floats(shape, data);
		} else if (type == TYPE_INT64 || type == TYPE_INT32) {
			long[] data = new long[n];
			if (raw != null) {
				ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
				for (int k = 0; k < n; k++) {
					data[k] = type == TYPE_INT64 ? b.getLong() : b.getInt();
				}
			} else {
				for (int k = 0; k < n; k++) {
					data[k] = longs.get(k);
				}
			}
			t = Tensor.longs(shape, data);
		} else if (type == TYPE_BOOL) {
			float[] data = new float[n];
			for (int k = 0; k < n; k++) {
				data[k] = raw != null ? raw[k] : longs.get(k);
			}
			t = Tensor.bools(shape, data);
		} else {
			throw new IOException("тип тензора " + type + " не поддерживается (" + name + ")");
		}
		return new NamedTensor(name, t);
	}

	private static String readValueInfoName(Reader r) throws IOException {
		String name = "";
		while (r.more()) {
			int key = (int) r.varint();
			if (key >>> 3 == 1 && (key & 7) == 2) {
				name = r.string();
			} else {
				r.skip(key & 7);
			}
		}
		return name;
	}

	/** Чтение protobuf: varint, fixed32/64, length-delimited. */
	private static final class Reader {
		private final byte[] b;
		private int pos;
		private final int end;

		Reader(byte[] b, int pos, int end) {
			this.b = b;
			this.pos = pos;
			this.end = end;
		}

		boolean more() {
			return pos < end;
		}

		long varint() throws IOException {
			long result = 0;
			for (int shift = 0; shift < 64; shift += 7) {
				if (pos >= end) {
					throw new IOException("обрыв varint");
				}
				byte x = b[pos++];
				result |= (long) (x & 0x7F) << shift;
				if ((x & 0x80) == 0) {
					return result;
				}
			}
			throw new IOException("слишком длинный varint");
		}

		float fixed32Float() throws IOException {
			if (pos + 4 > end) {
				throw new IOException("обрыв fixed32");
			}
			int bits = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
			pos += 4;
			return Float.intBitsToFloat(bits);
		}

		Reader sub() throws IOException {
			int len = (int) varint();
			if (len < 0 || pos + len > end) {
				throw new IOException("обрыв вложенного сообщения");
			}
			Reader r = new Reader(b, pos, pos + len);
			pos += len;
			return r;
		}

		byte[] bytes() throws IOException {
			Reader r = sub();
			byte[] out = new byte[r.end - r.pos];
			System.arraycopy(b, r.pos, out, 0, out.length);
			return out;
		}

		String string() throws IOException {
			return new String(bytes(), StandardCharsets.UTF_8);
		}

		void skipBytes() throws IOException {
			sub();
		}

		void skip(int wire) throws IOException {
			switch (wire) {
				case 0 -> varint();
				case 1 -> pos += 8;
				case 2 -> skipBytes();
				case 5 -> pos += 4;
				default -> throw new IOException("неизвестный тип поля " + wire);
			}
			if (pos > end) {
				throw new IOException("обрыв поля");
			}
		}
	}
}
