package dev.nbmusic.client;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.UploadBuffer;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.io.AudioDecoder;
import dev.nbmusic.core.layout.LayoutGenerator;
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
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.io.InputStream;
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
	/** Что отправлять на сервер: сам MIDI или MIDI, распознанный из аудио. */
	public Path uploadFile;
	/** 0..1, пока идёт распознавание аудио; -1 — не идёт. */
	public volatile double transcribeProgress = -1;
	private int loadGeneration;
	public Song song;
	public Pipeline.Result result;
	public String error;

	public int speed = 100;
	public int polyphony = Arranger.Options.DEFAULT.maxPolyphony();
	public int transpose = 0;
	public int rowLength = LayoutGenerator.DEFAULT_ROW_LENGTH;
	public int mutedChannels = 0;

	public boolean hologram = true;
	/** Закреплённое место голограммы; null — голограмма следует за игроком. */
	public Placement pinned;

	public record Placement(BlockPos anchor, Direction forward) {
	}

	/** Канал MIDI, как его показывает список дорожек. */
	public record Channel(int channel, int program, int notes) {
		public boolean isDrums() {
			return channel == MidiNote.DRUM_CHANNEL;
		}
	}

	private ClientSession() {
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
		if (AudioDecoder.isAudio(path.getFileName().toString())) {
			transcribeAsync(path, generation);
			return;
		}
		try (InputStream in = Files.newInputStream(path)) {
			song = Pipeline.load(Pipeline.baseName(path.getFileName().toString()), in);
			uploadFile = path;
		} catch (IOException | RuntimeException e) {
			error = "Не удалось прочитать: " + e.getMessage();
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
				Path copy = NbMusicMod.outputDir().resolve(SchematicExport.fileName(r.song().title()).replace(".schem", ".mid"));
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
				NbMusicMod.LOGGER.warn("Не удалось распознать {}", path, e);
				Minecraft.getInstance().execute(() -> {
					if (generation == loadGeneration) {
						transcribeProgress = -1;
						error = "Не удалось распознать: " + e.getMessage();
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
		if (song == null) {
			return;
		}
		try {
			result = Pipeline.arrange(song, options(), rowLength);
			error = null;
		} catch (IllegalArgumentException e) {
			result = null;
			error = e.getMessage();
		}
	}

	public List<Channel> channels() {
		if (song == null) {
			return List.of();
		}
		int[] counts = new int[16];
		int[][] programs = new int[16][128];
		for (MidiNote n : song.notes()) {
			counts[n.channel()]++;
			programs[n.channel()][n.program()]++;
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
			out.add(new Channel(ch, main, counts[ch]));
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
		return new Placement(SchematicPlacer.anchorInFrontOf(player.blockPosition(), forward, result.layout().button()), forward);
	}

	public void togglePin(Player player) {
		pinned = pinned == null && result != null ? placement(player) : null;
	}

	/** Экспорт .schem локально, в папку клиента. */
	public String export(Player player) {
		try {
			List<Path> written = SchematicExport.export(result, player.getName().getString());
			return "Сохранено: " + written.getFirst().toAbsolutePath()
					+ (written.size() > 1 ? " (и в папку WorldEdit)" : "");
		} catch (IOException e) {
			return "Не удалось сохранить: " + e.getMessage();
		}
	}

	/** Отправляет файл на сервер кусками и просит построить там, где стоит голограмма. */
	public String requestBuild(Player player) {
		if (!ClientPlayNetworking.canSend(BuildRequestPayload.TYPE)) {
			return "На сервере нет NoteBlock Music — используй экспорт .schem";
		}
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(uploadFile);
		} catch (IOException e) {
			return "Не удалось прочитать файл: " + e.getMessage();
		}
		if (bytes.length > UploadBuffer.MAX_BYTES) {
			return "Файл больше 2 МБ";
		}
		int total = UploadBuffer.chunkCount(bytes.length);
		for (int i = 0; i < total; i++) {
			int from = i * UploadBuffer.CHUNK_BYTES;
			int to = Math.min(bytes.length, from + UploadBuffer.CHUNK_BYTES);
			ClientPlayNetworking.send(new UploadChunkPayload(i, total, Arrays.copyOfRange(bytes, from, to)));
		}
		Placement p = placement(player);
		ClientPlayNetworking.send(new BuildRequestPayload(song.title(), options(), rowLength, p.anchor(), p.forward()));
		pinned = null;
		return null;
	}
}
