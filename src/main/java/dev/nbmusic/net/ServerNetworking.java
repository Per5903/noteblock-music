package dev.nbmusic.net;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.UploadBuffer;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.world.SongBuilder;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Приём файла из окна мода и постройка там, где игрок поставил голограмму. */
public final class ServerNetworking {
	/** Насколько далеко от игрока можно строить по голограмме. */
	private static final int MAX_DISTANCE = 128;

	private static final Map<UUID, UploadBuffer> UPLOADS = new HashMap<>();

	private ServerNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(UploadChunkPayload.TYPE, UploadChunkPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BuildRequestPayload.TYPE, BuildRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UploadChunkPayload.TYPE, (payload, ctx) -> {
			if (!isAllowed(ctx.player())) {
				return;
			}
			UploadBuffer buffer = UPLOADS.computeIfAbsent(ctx.player().getUUID(), id -> new UploadBuffer());
			if (!buffer.accept(payload.index(), payload.total(), payload.data())) {
				ctx.player().sendSystemMessage(Component.literal("NoteBlock Music: файл повреждён или больше 2 МБ"));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(BuildRequestPayload.TYPE, (payload, ctx) -> build(ctx.player(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> UPLOADS.remove(handler.getPlayer().getUUID()));
	}

	private static boolean isAllowed(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	private static void build(ServerPlayer player, BuildRequestPayload request) {
		if (!isAllowed(player)) {
			player.sendSystemMessage(Component.literal("NoteBlock Music: строить можно только с правами оператора (читами)"));
			return;
		}
		UploadBuffer buffer = UPLOADS.remove(player.getUUID());
		if (buffer == null || !buffer.isComplete()) {
			player.sendSystemMessage(Component.literal("NoteBlock Music: файл не дошёл до сервера, попробуй ещё раз"));
			return;
		}
		Arranger.Options o = request.options();
		if (o.speedPercent() < 10 || o.speedPercent() > 400 || o.maxPolyphony() < 1 || o.maxPolyphony() > LayoutGenerator.MAX_LEVELS * 2
				|| request.rowLength() < LayoutGenerator.MIN_ROW_LENGTH || request.rowLength() > 1024
				|| request.forward().getAxis().isVertical()) {
			player.sendSystemMessage(Component.literal("NoteBlock Music: неверные настройки"));
			return;
		}
		if (request.anchor().distSqr(player.blockPosition()) > (double) MAX_DISTANCE * MAX_DISTANCE) {
			player.sendSystemMessage(Component.literal("NoteBlock Music: голограмма дальше " + MAX_DISTANCE + " блоков от тебя"));
			return;
		}
		try {
			Pipeline.Result r = Pipeline.arrange(Pipeline.load(request.title(), new ByteArrayInputStream(buffer.bytes())), o, request.rowLength());
			String text = SongBuilder.build(player.level(), r, request.anchor(), request.forward());
			player.sendSystemMessage(Component.literal(text));
			NbMusicMod.LOGGER.info("{} построил «{}» в {}", player.getName().getString(), request.title(), request.anchor());
		} catch (IOException | IllegalArgumentException e) {
			player.sendSystemMessage(Component.literal("NoteBlock Music: " + e.getMessage()));
		}
	}
}
