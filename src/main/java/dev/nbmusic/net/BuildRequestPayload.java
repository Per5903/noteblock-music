package dev.nbmusic.net;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.arrange.Arranger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Построить загруженный файл: настройки окна и место, где стоит голограмма.
 * {@code anchor} — мировая позиция клетки (0,0,0) сетки, {@code forward} — направление рядов.
 */
public record BuildRequestPayload(String title, Arranger.Options options, int rowLength, BlockPos anchor, Direction forward)
		implements CustomPacketPayload {
	public static final Type<BuildRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NbMusicMod.MOD_ID, "build"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BuildRequestPayload> CODEC =
			CustomPacketPayload.codec(BuildRequestPayload::write, BuildRequestPayload::read);

	private static BuildRequestPayload read(RegistryFriendlyByteBuf buf) {
		String title = buf.readUtf(256);
		Arranger.Options options = new Arranger.Options(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
		return new BuildRequestPayload(title, options, buf.readVarInt(), buf.readBlockPos(), buf.readEnum(Direction.class));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(title, 256);
		buf.writeVarInt(options.speedPercent());
		buf.writeVarInt(options.maxPolyphony());
		buf.writeVarInt(options.transpose());
		buf.writeVarInt(options.mutedChannels());
		buf.writeVarInt(rowLength);
		buf.writeBlockPos(anchor);
		buf.writeEnum(forward);
	}

	@Override
	public Type<BuildRequestPayload> type() {
		return TYPE;
	}
}
