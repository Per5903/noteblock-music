package dev.nbmusic.gametest;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.client.ClientSession;
import dev.nbmusic.client.NbMusicClient;
import dev.nbmusic.client.NbMusicScreen;
import dev.nbmusic.client.PreviewPlayer;
import dev.nbmusic.core.UploadBuffer;
import dev.nbmusic.core.transcribe.AudioTranscriber;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.model.MidiNote;
import dev.nbmusic.world.SchematicPlacer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Настоящий клиент: MIDI в папке input → /nbmusic info/export/build от лица игрока →
 * нажатие кнопки → все нотные блоки сыграли. Скриншоты змейки с двух направлений.
 * Логи: [nbmusic-test].
 */
public final class NbMusicClientTest implements FabricClientGameTest {
	private static final String ARGS = " 100 6 16";

	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			writeTestMidi(NbMusicMod.inputDir().resolve("test.mid"));
		} catch (Exception e) {
			throw new AssertionError("не удалось записать test.mid", e);
		}

		try (TestSingleplayerContext world = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(s -> {
					s.setSeed("nbmusic");
					s.setAllowCommands(true); // «Разрешить читы»: без них строить из окна нельзя
				})
				.create()) {
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksRender();
			server.runCommand("time set day");
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule spawn_monsters false");
			server.runCommand("gamemode creative @a");

			server.runCommand("execute as @a at @s run nbmusic info test.mid" + ARGS);
			server.runCommand("execute as @a at @s run nbmusic export test.mid" + ARGS);
			Path schem = NbMusicMod.outputDir().resolve("test.schem");
			check(Files.isRegularFile(schem), "export записал " + schem);

			Pipeline.Result result;
			try {
				result = Pipeline.run(NbMusicMod.inputDir().resolve("test.mid"), new Arranger.Options(100, 6, 0), 16);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			LayoutGenerator.Layout layout = result.layout();
			log("схема: этажей " + layout.levels() + ", рядов " + layout.rows() + ", размер "
					+ layout.grid().width() + "×" + layout.grid().height() + "×" + layout.grid().length()
					+ ", задержка старта " + layout.startDelay());

			buildAndPlay(context, world, server, layout, Direction.EAST, new BlockPos(0, 150, 0), "east");
			buildAndPlay(context, world, server, layout, Direction.NORTH, new BlockPos(200, 150, 0), "north");
			screenFlow(context, world, server);
			audioFlow(context, world, server);
			log("ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ");
		}
	}

	private static void buildAndPlay(ClientGameTestContext context, TestSingleplayerContext world, TestServerContext server,
			LayoutGenerator.Layout layout, Direction forward, BlockPos feet, String name) {
		float yaw = forward.toYRot();
		// платформа под ногами и полёт: игрок не должен сдвинуться до постройки
		server.runCommand(String.format("setblock %d %d %d glass", feet.getX(), feet.getY() - 1, feet.getZ()));
		server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
		}));
		server.runCommand(String.format("tp @a %d %d %d %.0f 0", feet.getX(), feet.getY(), feet.getZ(), yaw));
		context.waitTicks(5);
		server.runCommand("execute as @a at @s run nbmusic build test.mid" + ARGS);
		context.waitTicks(2);

		BlockGrid grid = layout.grid();
		BlockPos anchor = SchematicPlacer.anchorInFrontOf(feet, forward, layout.button());
		BlockPos button = SchematicPlacer.toWorld(anchor, forward, layout.button());
		List<BlockPos> notes = new ArrayList<>();
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					if (grid.get(x, y, z).startsWith("minecraft:note_block")) {
						notes.add(SchematicPlacer.toWorld(anchor, forward, x, y, z));
					}
				}
			}
		}
		boolean built = server.computeOnServer(s -> s.overworld().getBlockState(button).getBlock() instanceof ButtonBlock
				&& notes.stream().allMatch(p -> s.overworld().getBlockState(p).getBlock() instanceof NoteBlock));
		check(built, name + ": /nbmusic build поставил кнопку и " + notes.size() + " нотных блоков");

		// Камера над серединой змейки, смотрим вниз
		BlockPos mid = SchematicPlacer.toWorld(anchor, forward, grid.width() / 2, 0, grid.length() / 2);
		server.runCommand(String.format("tp @a %d %d %d %.0f 55", mid.getX() - forward.getStepX() * 30,
				mid.getY() + 32, mid.getZ() - forward.getStepZ() * 30, yaw));
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot("nbmusic-" + name + "-built");

		server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			BlockState state = level.getBlockState(button);
			((ButtonBlock) state.getBlock()).press(state, level, button, null);
		});
		Set<BlockPos> played = new HashSet<>();
		int ticks = 2 * (layout.startDelay() + 400) + 40;
		for (int t = 0; t < ticks && played.size() < notes.size(); t++) {
			played.addAll(server.computeOnServer(s -> notes.stream()
					.filter(p -> s.overworld().getBlockState(p).getValue(NoteBlock.POWERED)).toList()));
			if (t == 60) {
				context.takeScreenshot("nbmusic-" + name + "-playing");
			}
			context.waitTick();
		}
		log(name + ": сыграло " + played.size() + " из " + notes.size() + " нотных блоков");
		check(played.size() == notes.size(), name + ": все нотные блоки сыграли");
	}

	/**
	 * Этап 3 как игрок: клавиша R → окно → выбор файла → «Слушать» → «Закрепить здесь» →
	 * голограмма → «Построить». Файл больше 30 КБ, чтобы уйти на сервер несколькими кусками.
	 */
	private static void screenFlow(ClientGameTestContext context, TestSingleplayerContext world, TestServerContext server) {
		Path big = NbMusicMod.inputDir().resolve("big.mid");
		try {
			writeBigMidi(big);
			log("big.mid: " + Files.size(big) + " байт, кусков " + UploadBuffer.chunkCount((int) Files.size(big)));
			check(UploadBuffer.chunkCount((int) Files.size(big)) >= 2, "big.mid уходит на сервер несколькими кусками");
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		BlockPos feet = new BlockPos(400, 150, 0);
		server.runCommand(String.format("setblock %d %d %d glass", feet.getX(), feet.getY() - 1, feet.getZ()));
		server.runCommand(String.format("tp @a %d %d %d -90 0", feet.getX(), feet.getY(), feet.getZ()));
		world.getConnection().waitForChunksRender();
		context.waitTicks(5);

		context.getInput().pressKey(NbMusicClient.OPEN);
		context.waitTicks(3);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof NbMusicScreen), "клавиша R открывает окно");
		context.clickScreenButton("▶"); // первый файл в папке — big.mid
		context.waitTicks(3);
		check(context.computeOnClient(mc -> ClientSession.INSTANCE.result != null
				&& ClientSession.INSTANCE.file.getFileName().toString().equals("big.mid")), "файл выбран и разложен");
		context.takeScreenshot("nbmusic-gui");

		context.clickScreenButton("▶ Слушать");
		context.waitTicks(40);
		int played = context.computeOnClient(mc -> PreviewPlayer.INSTANCE.notesPlayed);
		log("предпрослушивание: за 2 с прозвучало нот " + played);
		check(played > 0 && context.computeOnClient(mc -> PreviewPlayer.INSTANCE.isPlaying()), "предпрослушивание играет");
		context.takeScreenshot("nbmusic-gui-playing");
		context.clickScreenButton("■ Стоп");
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> PreviewPlayer.INSTANCE.isPlaying()), "кнопка «Стоп» останавливает");

		context.clickScreenButton("Закрепить здесь");
		ClientSession.Placement pinned = context.computeOnClient(mc -> ClientSession.INSTANCE.pinned);
		check(pinned != null, "голограмма закреплена");
		LayoutGenerator.Layout layout = context.computeOnClient(mc -> ClientSession.INSTANCE.result.layout());
		context.clickScreenButton("Готово");
		// смотрим на голограмму сбоку-сверху; игрок улетает, а голограмма остаётся на месте
		BlockPos mid = SchematicPlacer.toWorld(pinned.anchor(), pinned.forward(), layout.grid().width() / 2, 0, layout.grid().length() / 2);
		server.runCommand(String.format("tp @a %d %d %d -90 50", mid.getX() - 45, mid.getY() + 40, mid.getZ()));
		world.getConnection().waitForChunksRender();
		context.waitTicks(10);
		context.takeScreenshot("nbmusic-hologram");

		context.getInput().pressKey(NbMusicClient.OPEN);
		context.waitTicks(3);
		context.clickScreenButton("Построить");
		context.waitTicks(20);
		check(context.computeOnClient(mc -> mc.gui.screen() == null), "после «Построить» окно закрылось");

		BlockGrid grid = layout.grid();
		BlockPos button = SchematicPlacer.toWorld(pinned.anchor(), pinned.forward(), layout.button());
		List<BlockPos> notes = new ArrayList<>();
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					if (grid.get(x, y, z).startsWith("minecraft:note_block")) {
						notes.add(SchematicPlacer.toWorld(pinned.anchor(), pinned.forward(), x, y, z));
					}
				}
			}
		}
		boolean built = server.computeOnServer(s -> s.overworld().getBlockState(button).getBlock() instanceof ButtonBlock
				&& notes.stream().allMatch(p -> s.overworld().getBlockState(p).getBlock() instanceof NoteBlock));
		check(built, "«Построить» поставил схему ровно по голограмме (" + notes.size() + " нотных блоков)");
		context.waitTicks(5);
		context.takeScreenshot("nbmusic-built-from-gui");
	}

	/** Ноты синтетической мелодии из resources/audio (как в юнит-тесте AudioPipelineTest). */
	private static final int[] MELODY_PITCHES = {60, 64, 67, 72, 65, 69, 72, 48, 71, 74, 79};

	/**
	 * Аудио: команда с MP3 распознаёт в фоне и отвечает в чат; окно распознаёт OGG
	 * (stb_vorbis есть только в клиенте) с полосой прогресса и строит по кнопке.
	 */
	private static void audioFlow(ClientGameTestContext context, TestSingleplayerContext world, TestServerContext server) {
		Path mp3 = NbMusicMod.inputDir().resolve("melody.mp3");
		Path ogg = NbMusicMod.inputDir().resolve("melody.ogg");
		try {
			for (Path p : List.of(mp3, ogg)) {
				try (var in = NbMusicClientTest.class.getResourceAsStream("/audio/" + p.getFileName())) {
					Files.write(p, in.readAllBytes());
				}
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		server.runCommand("execute as @a at @s run nbmusic info melody.mp3");
		int waited = 0;
		while (!AudioTranscriber.isCached(mp3, NbMusicMod.cacheDir()) && waited < 1200) {
			context.waitTicks(10);
			waited += 10;
		}
		log("команда распознала MP3 за ~" + waited / 20.0 + " с");
		check(AudioTranscriber.isCached(mp3, NbMusicMod.cacheDir()), "/nbmusic info с MP3 распознаёт в фоне и кэширует");
		context.waitTicks(5);

		BlockPos feet = new BlockPos(600, 150, 0);
		server.runCommand(String.format("setblock %d %d %d glass", feet.getX(), feet.getY() - 1, feet.getZ()));
		server.runCommand(String.format("tp @a %d %d %d -90 0", feet.getX(), feet.getY(), feet.getZ()));
		world.getConnection().waitForChunksRender();
		context.getInput().pressKey(NbMusicClient.OPEN);
		context.waitTicks(3);
		context.runOnClient(mc -> ClientSession.INSTANCE.load(ogg));
		context.waitTicks(4);
		check(context.computeOnClient(mc -> ClientSession.INSTANCE.isTranscribing()), "OGG распознаётся в фоне");
		context.takeScreenshot("nbmusic-audio-transcribing");
		waited = 0;
		while (context.computeOnClient(mc -> ClientSession.INSTANCE.isTranscribing()) && waited < 1200) {
			context.waitTicks(5);
			waited += 5;
		}
		String error = context.computeOnClient(mc -> ClientSession.INSTANCE.error);
		check(error == null, "OGG распознан без ошибок (" + error + "), ~" + waited / 20.0 + " с");
		List<Integer> pitches = context.computeOnClient(mc -> ClientSession.INSTANCE.song.notes().stream().map(MidiNote::pitch).toList());
		log("OGG → ноты " + pitches);
		for (int p : MELODY_PITCHES) {
			check(pitches.contains(p), "в OGG услышана нота " + p);
		}
		check(context.computeOnClient(mc -> ClientSession.INSTANCE.uploadFile.getFileName().toString().endsWith(".mid")),
				"на сервер уйдёт распознанный MIDI");
		context.waitTicks(3);
		context.takeScreenshot("nbmusic-audio-ready");

		context.clickScreenButton("Закрепить здесь");
		ClientSession.Placement pinned = context.computeOnClient(mc -> ClientSession.INSTANCE.pinned);
		LayoutGenerator.Layout layout = context.computeOnClient(mc -> ClientSession.INSTANCE.result.layout());
		context.clickScreenButton("Построить");
		context.waitTicks(20);
		BlockGrid grid = layout.grid();
		List<BlockPos> notes = new ArrayList<>();
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.length(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					if (grid.get(x, y, z).startsWith("minecraft:note_block")) {
						notes.add(SchematicPlacer.toWorld(pinned.anchor(), pinned.forward(), x, y, z));
					}
				}
			}
		}
		boolean built = server.computeOnServer(s -> notes.stream().allMatch(p -> s.overworld().getBlockState(p).getBlock() instanceof NoteBlock));
		check(built && !notes.isEmpty(), "песня из OGG построена по голограмме (" + notes.size() + " нотных блоков)");
	}

	/** Плотные аккорды по 10 нот — файл крупнее одного сетевого куска. */
	private static void writeBigMidi(Path file) throws Exception {
		Sequence seq = new Sequence(Sequence.PPQ, 480);
		Track track = seq.createTrack();
		for (int i = 0; i < 700; i++) {
			long tick = i * 96L;
			for (int v = 0; v < 10; v++) {
				int pitch = 48 + (i * 5 + v * 3) % 36;
				track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, v % 3, pitch, 60 + v * 6), tick));
				track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, v % 3, pitch, 0), tick + 90));
			}
		}
		MidiSystem.write(seq, 1, file.toFile());
	}

	/** Синтетическая мелодия теста (как в серверном GameTest) в виде MIDI-файла. */
	private static void writeTestMidi(Path file) throws Exception {
		Files.createDirectories(file.getParent());
		int ppq = 480; // 120 BPM: 0,1 с = 96 тиков
		Sequence seq = new Sequence(Sequence.PPQ, ppq);
		Track track = seq.createTrack();
		track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 1, 33, 0), 0));
		for (MidiNote n : LayoutGameTest.testSong().notes()) {
			long tick = Math.round(n.seconds() * 960);
			int channel = n.isDrum() ? MidiNote.DRUM_CHANNEL : n.program() == 33 ? 1 : 0;
			track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, n.pitch(), n.velocity()), tick));
			track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, n.pitch(), 0), tick + 48));
		}
		MidiSystem.write(seq, 1, file.toFile());
	}

	private static void log(String s) {
		System.out.println("[nbmusic-test] " + s);
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("[nbmusic-test] FAIL: " + what);
		}
		log("ok: " + what);
	}
}
