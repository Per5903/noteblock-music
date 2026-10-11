package dev.nbmusic.net;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.UploadBuffer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Кусок MIDI-файла, который клиент отправляет серверу перед {@link BuildRequestPayload}. */
public record UploadChunkPayload(int index, int total, byte[] data) implements CustomPacketPayload {
	public static final Type<UploadChunkPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NbMusicMod.MOD_ID, "upload"));
	public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunkPayload> CODEC =
			CustomPacketPayload.codec(UploadChunkPayload::write, UploadChunkPayload::read);

	private static UploadChunkPayload read(RegistryFriendlyByteBuf buf) {
		return new UploadChunkPayload(buf.readVarInt(), buf.readVarInt(), buf.readByteArray(UploadBuffer.CHUNK_BYTES));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(index);
		buf.writeVarInt(total);
		buf.writeByteArray(data);
	}

	@Override
	public Type<UploadChunkPayload> type() {
		return TYPE;
	}
}
