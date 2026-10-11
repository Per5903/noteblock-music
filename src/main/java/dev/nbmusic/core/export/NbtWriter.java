package dev.nbmusic.core.export;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Минимальный потоковый писатель NBT (big-endian, как в Java Edition). */
final class NbtWriter implements Closeable {
	private static final int END = 0;
	private static final int SHORT = 2;
	private static final int INT = 3;
	private static final int LONG = 4;
	private static final int BYTE_ARRAY = 7;
	private static final int STRING = 8;
	private static final int COMPOUND = 10;
	private static final int INT_ARRAY = 11;

	private final DataOutputStream out;

	NbtWriter(OutputStream out) {
		this.out = new DataOutputStream(out);
	}

	NbtWriter beginCompound(String name) throws IOException {
		header(COMPOUND, name);
		return this;
	}

	NbtWriter endCompound() throws IOException {
		out.writeByte(END);
		return this;
	}

	NbtWriter putShort(String name, int value) throws IOException {
		header(SHORT, name);
		out.writeShort(value);
		return this;
	}

	NbtWriter putInt(String name, int value) throws IOException {
		header(INT, name);
		out.writeInt(value);
		return this;
	}

	NbtWriter putLong(String name, long value) throws IOException {
		header(LONG, name);
		out.writeLong(value);
		return this;
	}

	NbtWriter putString(String name, String value) throws IOException {
		header(STRING, name);
		writeUtf(value);
		return this;
	}

	NbtWriter putByteArray(String name, byte[] value) throws IOException {
		header(BYTE_ARRAY, name);
		out.writeInt(value.length);
		out.write(value);
		return this;
	}

	NbtWriter putIntArray(String name, int... value) throws IOException {
		header(INT_ARRAY, name);
		out.writeInt(value.length);
		for (int v : value) {
			out.writeInt(v);
		}
		return this;
	}

	private void header(int type, String name) throws IOException {
		out.writeByte(type);
		writeUtf(name);
	}

	/** NBT использует modified UTF-8 — ровно то, что пишет {@link DataOutputStream#writeUTF}. */
	private void writeUtf(String s) throws IOException {
		if (s.getBytes(StandardCharsets.UTF_8).length > 65535) {
			throw new IOException("string too long for NBT");
		}
		out.writeUTF(s);
	}

	@Override
	public void close() throws IOException {
		out.close();
	}
}
