package dev.nbmusic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.layout.LayoutGenerator;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки окна в config/nbmusic.json: переживают перезапуск игры. */
final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static final class Data {
		int speed = 100;
		int polyphony = Arranger.Options.DEFAULT.maxPolyphony();
		int transpose = 0;
		int rowLength = LayoutGenerator.DEFAULT_ROW_LENGTH;
		boolean hologram = true;
		String lastFile;

		/** Защита от ручной правки файла: значения в допустимых пределах. */
		Data sanitized() {
			speed = Math.clamp(speed, 10, 400);
			polyphony = Math.clamp(polyphony, 1, LayoutGenerator.MAX_LEVELS * 2);
			transpose = Math.clamp(transpose, -24, 24);
			rowLength = Math.clamp(rowLength, LayoutGenerator.MIN_ROW_LENGTH, 1024);
			return this;
		}
	}

	private ClientConfig() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(NbMusicMod.MOD_ID + ".json");
	}

	static Data load() {
		Path p = path();
		if (!Files.isRegularFile(p)) {
			return new Data();
		}
		try {
			Data d = GSON.fromJson(Files.readString(p), Data.class);
			return d == null ? new Data() : d.sanitized();
		} catch (IOException | JsonParseException e) {
			NbMusicMod.LOGGER.warn("Could not read {}, using defaults", p, e);
			return new Data();
		}
	}

	static void save(Data data) {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(data));
		} catch (IOException e) {
			NbMusicMod.LOGGER.warn("Could not save {}", path(), e);
		}
	}
}
