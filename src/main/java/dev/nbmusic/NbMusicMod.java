package dev.nbmusic;

import dev.nbmusic.command.NbMusicCommand;
import dev.nbmusic.net.ServerNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NbMusicMod implements ModInitializer {
	public static final String MOD_ID = "nbmusic";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Path inputDir() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID).resolve("input");
	}

	public static Path outputDir() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID).resolve("output");
	}

	@Override
	public void onInitialize() {
		try {
			Files.createDirectories(inputDir());
			Files.createDirectories(outputDir());
		} catch (IOException e) {
			LOGGER.error("Не удалось создать папки {}", inputDir().getParent(), e);
		}
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> NbMusicCommand.register(dispatcher));
		ServerNetworking.register();
		LOGGER.info("NoteBlock Music {} загружен, папка с MIDI: {}",
				FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString(),
				inputDir().toAbsolutePath());
	}
}
