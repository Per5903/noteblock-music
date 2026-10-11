package dev.nbmusic.net;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.Texts;
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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Приём файла из окна мода и постройка там, где игрок поставил голограмму. */
public final class ServerNetworking {
	/** Насколько далеко от игрока можно строить по голограмме. */
	private static final int MAX_DISTANCE = 128;
	private static final Set<String> FORMATS = Set.of("mid", "nbs");

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
				tell(ctx.player(), Texts.tr("nbmusic.net.upload_bad", "The file is damaged or larger than 2 MB"));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(BuildRequestPayload.TYPE, (payload, ctx) -> build(ctx.player(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> UPLOADS.remove(handler.getPlayer().getUUID()));
	}

	private static boolean isAllowed(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	private static void tell(ServerPlayer player, Component text) {
		player.sendSystemMessage(Component.literal("[NoteBlock Music] ").append(text));
	}

	private static void build(ServerPlayer player, BuildRequestPayload request) {
		if (!isAllowed(player)) {
			tell(player, Texts.tr("nbmusic.net.no_permission", "Building requires operator rights (cheats in singleplayer)"));
			return;
		}
		UploadBuffer buffer = UPLOADS.remove(player.getUUID());
		if (buffer == null || !buffer.isComplete()) {
			tell(player, Texts.tr("nbmusic.net.upload_missing", "The file did not reach the server, try again"));
			return;
		}
		Arranger.Options o = request.options();
		if (!FORMATS.contains(request.format())
				|| o.speedPercent() < 10 || o.speedPercent() > 400 || o.maxPolyphony() < 1 || o.maxPolyphony() > LayoutGenerator.MAX_LEVELS * 2
				|| request.rowLength() < LayoutGenerator.MIN_ROW_LENGTH || request.rowLength() > 1024
				|| request.forward().getAxis().isVertical()) {
			tell(player, Texts.tr("nbmusic.net.bad_settings", "Invalid settings"));
			return;
		}
		if (request.anchor().distSqr(player.blockPosition()) > (double) MAX_DISTANCE * MAX_DISTANCE) {
			tell(player, Texts.tr("nbmusic.net.too_far", "The hologram is more than %s blocks away from you", MAX_DISTANCE));
			return;
		}
		try {
			Pipeline.Result r = Pipeline.arrange(Pipeline.load(request.title(), request.format(), buffer.bytes()), o, request.rowLength());
			tell(player, SongBuilder.build(player.level(), r, request.anchor(), request.forward()));
			NbMusicMod.LOGGER.info("{} built \"{}\" at {}", player.getName().getString(), request.title(), request.anchor());
		} catch (IOException | IllegalArgumentException e) {
			tell(player, Texts.error(e));
		}
	}
}
