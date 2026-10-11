package dev.nbmusic.client;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.Texts;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.UploadBuffer;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.io.AudioDecoder;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.model.Instrument;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.core.model.Song;
import dev.nbmusic.core.transcribe.AudioTranscriber;
import dev.nbmusic.net.BuildRequestPayload;
import dev.nbmusic.net.UploadChunkPayload;
import dev.nbmusic.world.SchematicExport;
import dev.nbmusic.world.SchematicPlacer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Состояние окна мода: выбранный файл, настройки, результат, место голограммы. Живёт весь сеанс игры. */
public final class ClientSession {
	public static final ClientSession INSTANCE = new ClientSession();

	public Path file;
	/** Что отправлять на сервер: сам MIDI/NBS или MIDI, распознанный из аудио. */
	public Path uploadFile;
	/** 0..1, пока идёт распознавание аудио; -1 — не идёт. */
	public volatile double transcribeProgress = -1;
	private int loadGeneration;
	public Song song;
	public Pipeline.Result result;
	public Component error;

	public int speed = 100;
	public int polyphony = Arranger.Options.DEFAULT.maxPolyphony();
	public int transpose = 0;
	public int rowLength = LayoutGenerator.DEFAULT_ROW_LENGTH;
	public int mutedChannels = 0;
	/** Сдвиг постройки по высоте относительно ног игрока, блоков. */
	public int heightOffset = 0;

	public boolean hologram = true;
	/** Закреплённое место голограммы; null — голограмма следует за игроком. */
	public Placement pinned;

	private boolean restored;

	public record Placement(BlockPos anchor, Direction forward) {
	}

	/**
	 * Дорожка в списке: MIDI-канал с основной программой либо, для .nbs, инструмент нотного блока.
	 */
	public record Channel(int channel, int program, int notes, Instrument instrument) {
		public boolean isDrums() {
			return instrument == null && channel == MidiNote.DRUM_CHANNEL;
		}
	}

	private ClientSession() {
		ClientConfig.Data c = ClientConfig.load();
		speed = c.speed;
		polyphony = c.polyphony;
		transpose = c.transpose;
		rowLength = c.rowLength;
		hologram = c.hologram;
		heightOffset = c.heightOffset;
	}

	/** При первом открытии окна — снова открыть последний файл. */
	public void restoreLastFile() {
		if (restored) {
			return;
		}
		restored = true;
		String last = ClientConfig.load().lastFile;
		if (file == null && last != null && Files.isRegularFile(Path.of(last))) {
			load(Path.of(last));
		}
	}

	public void saveConfig() {
		ClientConfig.Data c = new ClientConfig.Data();
		c.speed = speed;
		c.polyphony = polyphony;
		c.transpose = transpose;
		c.rowLength = rowLength;
		c.hologram = hologram;
		c.heightOffset = heightOffset;
		c.lastFile = file == null ? null : file.toAbsolutePath().toString();
		ClientConfig.save(c);
	}

	public Arranger.Options options() {
		return new Arranger.Options(speed, polyphony, transpose, mutedChannels);
	}

	public static List<Path> inputFiles() {
		try (Stream<Path> files = Files.list(NbMusicMod.inputDir())) {
			return files.filter(Files::isRegularFile)
					.filter(p -> Pipeline.SUPPORTED_EXTENSIONS.contains(Pipeline.extension(p.getFileName().toString())))
					.sorted()
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public boolean isTranscribing() {
		return transcribeProgress >= 0;
	}

	public void load(Path path) {
		PreviewPlayer.INSTANCE.stop();
		int generation = ++loadGeneration;
		file = path;
		uploadFile = null;
		song = null;
		result = null;
		mutedChannels = 0;
		pinned = null;
		error = null;
		transcribeProgress = -1;
		saveConfig();
		String name = path.getFileName().toString();
		if (AudioDecoder.isAudio(name)) {
			transcribeAsync(path, generation);
			return;
		}
		try {
			song = Pipeline.load(Pipeline.baseName(name), Pipeline.extension(name), Files.readAllBytes(path));
			uploadFile = path;
		} catch (IOException | RuntimeException e) {
			error = Texts.tr("nbmusic.gui.read_failed", "Could not read: %s", Texts.error(e));
			return;
		}
		recompute();
	}

	/** Распознавание в фоне; результат применяется в игровом потоке, если файл ещё выбран. */
	private void transcribeAsync(Path path, int generation) {
		transcribeProgress = 0;
		Thread t = new Thread(() -> {
			try {
				AudioTranscriber.Result r = AudioTranscriber.transcribe(path, NbMusicMod.cacheDir(), p -> {
					if (generation == loadGeneration) {
						transcribeProgress = p;
					}
				});
				// копия рядом со схемами — её можно открыть в любом MIDI-редакторе
				Path copy = NbMusicMod.outputDir().resolve(SchematicExport.fileBase(r.song().title()) + ".mid");
				Files.createDirectories(copy.getParent());
				Files.copy(r.midi(), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				Minecraft.getInstance().execute(() -> {
					if (generation != loadGeneration) {
						return;
					}
					transcribeProgress = -1;
					song = r.song();
					uploadFile = r.midi();
					recompute();
				});
			} catch (IOException | RuntimeException e) {
				NbMusicMod.LOGGER.warn("Could not transcribe {}", path, e);
				Minecraft.getInstance().execute(() -> {
					if (generation == loadGeneration) {
						transcribeProgress = -1;
						error = Texts.tr("nbmusic.gui.transcribe_failed", "Could not transcribe: %s", Texts.error(e));
					}
				});
			}
		}, "nbmusic-transcribe");
		t.setDaemon(true);
		t.start();
	}

	/** Пересчёт аранжировки и схемы после изменения настроек. */
	public void recompute() {
		PreviewPlayer.INSTANCE.stop();
		saveConfig();
		if (song == null) {
			return;
		}
		try {
			result = Pipeline.arrange(song, options(), rowLength);
			error = null;
		} catch (IllegalArgumentException e) {
			result = null;
			error = Texts.error(e);
		}
	}

	public List<Channel> channels() {
		if (song == null) {
			return List.of();
		}
		int[] counts = new int[16];
		int[][] programs = new int[16][128];
		Instrument[] instruments = new Instrument[16];
		for (MidiNote n : song.notes()) {
			counts[n.channel()]++;
			programs[n.channel()][n.program()]++;
			if (n.instrument() != null) {
				instruments[n.channel()] = n.instrument();
			}
		}
		List<Channel> out = new ArrayList<>();
		for (int ch = 0; ch < 16; ch++) {
			if (counts[ch] == 0) {
				continue;
			}
			int[] p = programs[ch];
			int main = 0;
			for (int i = 1; i < 128; i++) {
				if (p[i] > p[main]) {
					main = i;
				}
			}
			out.add(new Channel(ch, main, counts[ch], instruments[ch]));
		}
		return out;
	}

	public boolean isMuted(int channel) {
		return (mutedChannels >> channel & 1) != 0;
	}

	public void toggleChannel(int channel) {
		mutedChannels ^= 1 << channel;
		recompute();
	}

	/** Где стоит голограмма: закреплённое место или перед игроком по взгляду. */
	public Placement placement(Player player) {
		if (pinned != null) {
			return pinned;
		}
		Direction forward = player.getDirection();
		BlockPos feet = player.blockPosition().above(heightOffset);
		return new Placement(SchematicPlacer.anchorInFrontOf(feet, forward, result.layout().button()), forward);
	}

	public void togglePin(Player player) {
		pinned = pinned == null && result != null ? placement(player) : null;
	}

	/**
	 * Экспорт во все форматы в папку клиента. В одиночной игре .nbt ещё и кладётся в мир —
	 * тогда сразу работает /place template.
	 */
	public Component export(Player player) {
		try {
			var server = Minecraft.getInstance().getSingleplayerServer();
			Path structures = server == null ? null : SchematicExport.structuresDir(server);
			return SchematicExport.export(result, player.getName().getString(), structures).message();
		} catch (IOException e) {
			return Texts.tr("nbmusic.gui.export_failed", "Could not save: %s", Texts.error(e));
		}
	}

	/** Отправляет файл на сервер кусками и просит построить там, где стоит голограмма; null — отправлено. */
	public Component requestBuild(Player player) {
		if (!ClientPlayNetworking.canSend(BuildRequestPayload.TYPE)) {
			return Texts.tr("nbmusic.gui.no_server_mod", "NoteBlock Music is not installed on the server — use export instead");
		}
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(uploadFile);
		} catch (IOException e) {
			return Texts.tr("nbmusic.gui.read_failed", "Could not read: %s", Texts.error(e));
		}
		if (bytes.length > UploadBuffer.MAX_BYTES) {
			return Texts.tr("nbmusic.gui.too_large", "The file is larger than 2 MB");
		}
		int total = UploadBuffer.chunkCount(bytes.length);
		for (int i = 0; i < total; i++) {
			int from = i * UploadBuffer.CHUNK_BYTES;
			int to = Math.min(bytes.length, from + UploadBuffer.CHUNK_BYTES);
			ClientPlayNetworking.send(new UploadChunkPayload(i, total, Arrays.copyOfRange(bytes, from, to)));
		}
		Placement p = placement(player);
		String format = Pipeline.extension(uploadFile.getFileName().toString()).equals("nbs") ? "nbs" : "mid";
		ClientPlayNetworking.send(new BuildRequestPayload(song.title(), format, options(), rowLength, p.anchor(), p.forward()));
		pinned = null;
		return null;
	}
}
