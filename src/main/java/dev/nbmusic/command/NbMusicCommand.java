package dev.nbmusic.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.nbmusic.NbMusicMod;
import dev.nbmusic.Texts;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.io.AudioDecoder;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.core.transcribe.AudioTranscriber;
import dev.nbmusic.world.SchematicExport;
import dev.nbmusic.world.SchematicPlacer;
import dev.nbmusic.world.SongBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * /nbmusic list
 * /nbmusic info|export|build &lt;файл&gt; [скорость%] [полифония] [ширина]
 */
public final class NbMusicCommand {
	private NbMusicCommand() {
	}

	/** Параметры команды: аранжировка + длина ряда змейки. */
	private record Settings(Arranger.Options options, int rowLength) {
		static final Settings DEFAULT = new Settings(Arranger.Options.DEFAULT, LayoutGenerator.DEFAULT_ROW_LENGTH);
	}

	private interface Action {
		int run(CommandContext<CommandSourceStack> c, Pipeline.Result result) throws CommandSyntaxException;
	}

	/** Распознавание аудио по одному за раз, чтобы не забрать у сервера все ядра. */
	private static final ExecutorService TRANSCRIBE = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "nbmusic-command-transcribe");
		t.setDaemon(true);
		return t;
	});

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("nbmusic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("list").executes(NbMusicCommand::list))
				.then(withFile("info", NbMusicCommand::info))
				.then(withFile("export", NbMusicCommand::export))
				.then(withFile("build", NbMusicCommand::build)));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> withFile(String name, Action action) {
		return Commands.literal(name).then(Commands.argument("file", StringArgumentType.string())
				.suggests(FILES)
				.executes(c -> start(c, resolve(c), Settings.DEFAULT, action))
				.then(Commands.argument("speed", IntegerArgumentType.integer(10, 400))
						.executes(c -> start(c, resolve(c), settings(c, 1), action))
						.then(Commands.argument("polyphony", IntegerArgumentType.integer(1, LayoutGenerator.MAX_LEVELS * 2))
								.executes(c -> start(c, resolve(c), settings(c, 2), action))
								.then(Commands.argument("width", IntegerArgumentType.integer(LayoutGenerator.MIN_ROW_LENGTH, 1024))
										.executes(c -> start(c, resolve(c), settings(c, 3), action))))));
	}

	/** @param given сколько необязательных аргументов указано: скорость, полифония, ширина */
	private static Settings settings(CommandContext<CommandSourceStack> c, int given) {
		int speed = IntegerArgumentType.getInteger(c, "speed");
		int poly = given >= 2 ? IntegerArgumentType.getInteger(c, "polyphony") : Arranger.Options.DEFAULT.maxPolyphony();
		int width = given >= 3 ? IntegerArgumentType.getInteger(c, "width") : LayoutGenerator.DEFAULT_ROW_LENGTH;
		return new Settings(new Arranger.Options(speed, poly, 0), width);
	}

	/**
	 * MIDI, NBS и уже распознанное аудио обрабатываются сразу. Новое аудио распознаётся в фоне,
	 * а действие выполняется в игровом потоке, когда ноты готовы.
	 */
	private static int start(CommandContext<CommandSourceStack> c, Path file, Settings settings, Action action) throws CommandSyntaxException {
		String name = file.getFileName().toString();
		if (!AudioDecoder.isAudio(name)) {
			Pipeline.Result r = process(c, () -> Pipeline.run(file, settings.options(), settings.rowLength()));
			return r == null ? 0 : action.run(c, r);
		}
		Path cache = NbMusicMod.cacheDir();
		if (AudioTranscriber.isCached(file, cache)) {
			Pipeline.Result r = process(c, () -> Pipeline.arrange(AudioTranscriber.transcribe(file, cache, p -> {
			}).song(), settings.options(), settings.rowLength()));
			return r == null ? 0 : action.run(c, r);
		}
		CommandSourceStack source = c.getSource();
		MinecraftServer server = source.getServer();
		source.sendSuccess(() -> Texts.tr("nbmusic.cmd.transcribing",
				"Transcribing “%s” — this takes up to a minute, the result will appear in chat", name), false);
		CompletableFuture.supplyAsync(() -> {
			try {
				return AudioTranscriber.transcribe(file, cache, p -> {
				}).song();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, TRANSCRIBE).whenComplete((song, error) -> server.execute(() -> {
			if (error != null) {
				NbMusicMod.LOGGER.warn("Could not transcribe {}", name, error);
				source.sendFailure(Texts.tr("nbmusic.cmd.transcribe_failed", "Could not transcribe “%s”: %s", name, Texts.error(error)));
				return;
			}
			Pipeline.Result r = process(c, () -> Pipeline.arrange(song, settings.options(), settings.rowLength()));
			if (r == null) {
				return;
			}
			try {
				action.run(c, r);
			} catch (CommandSyntaxException e) {
				source.sendFailure(Component.literal(e.getRawMessage().getString()));
			}
		}));
		return Command.SINGLE_SUCCESS;
	}

	private static final SuggestionProvider<CommandSourceStack> FILES = (c, builder) -> {
		for (String name : inputFiles()) {
			boolean plain = name.chars().allMatch(ch -> StringReader.isAllowedInUnquotedString((char) ch));
			builder.suggest(plain ? name : '"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
		}
		return builder.buildFuture();
	};

	private static List<String> inputFiles() {
		try (Stream<Path> files = Files.list(NbMusicMod.inputDir())) {
			return files.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(n -> Pipeline.SUPPORTED_EXTENSIONS.contains(Pipeline.extension(n)))
					.sorted()
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Путь внутри input/, без выхода за пределы папки. */
	private static Path resolve(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Path dir = NbMusicMod.inputDir().toAbsolutePath().normalize();
		Path file = dir.resolve(StringArgumentType.getString(c, "file")).normalize();
		if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
			throw new SimpleCommandExceptionType(Texts.tr("nbmusic.cmd.no_file", "No such file in %s", dir.toString())).create();
		}
		return file;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		List<String> files = inputFiles();
		Component text = files.isEmpty()
				? Texts.tr("nbmusic.cmd.list_empty", "The folder is empty. Put .mid / .nbs / .mp3 / .wav / .ogg into %s",
						NbMusicMod.inputDir().toAbsolutePath().toString())
				: Texts.tr("nbmusic.cmd.list", "Files (%s):", files.size()).append("\n  " + String.join("\n  ", files));
		c.getSource().sendSuccess(() -> text, false);
		return files.size();
	}

	private interface Job {
		Pipeline.Result get() throws IOException;
	}

	private static Pipeline.Result process(CommandContext<CommandSourceStack> c, Job job) {
		try {
			return job.get();
		} catch (IOException | IllegalArgumentException e) {
			c.getSource().sendFailure(Texts.tr("nbmusic.cmd.error", "Error: %s", Texts.error(e)));
			return null;
		}
	}

	private static int info(CommandContext<CommandSourceStack> c, Pipeline.Result r) {
		c.getSource().sendSuccess(() -> SongBuilder.summary(r), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int export(CommandContext<CommandSourceStack> c, Pipeline.Result r) {
		try {
			SchematicExport.Exported e = SchematicExport.export(r, c.getSource().getTextName(),
					SchematicExport.structuresDir(c.getSource().getServer()));
			c.getSource().sendSuccess(() -> SongBuilder.summary(r).copy().append("\n").append(e.message()), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException e) {
			NbMusicMod.LOGGER.error("Could not write the schematic", e);
			c.getSource().sendFailure(Texts.tr("nbmusic.cmd.write_failed", "Could not write: %s", Texts.error(e)));
			return 0;
		}
	}

	/** Ставит схему перед игроком по направлению его взгляда; уровень механизма — на уровне ног. */
	private static int build(CommandContext<CommandSourceStack> c, Pipeline.Result r) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Direction forward = player.getDirection();
		BlockPos anchor = SchematicPlacer.anchorInFrontOf(player.blockPosition(), forward, r.layout().button());
		try {
			Component text = SongBuilder.build(player.level(), r, anchor, forward);
			c.getSource().sendSuccess(() -> text, true);
			return Command.SINGLE_SUCCESS;
		} catch (IllegalArgumentException e) {
			c.getSource().sendFailure(Texts.error(e));
			return 0;
		}
	}
}
