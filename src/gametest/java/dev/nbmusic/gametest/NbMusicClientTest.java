package dev.nbmusic.gametest;

import dev.nbmusic.NbMusicMod;
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
				.adjustSettings(s -> s.setSeed("nbmusic"))
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
