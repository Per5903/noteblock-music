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
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.world.SchematicExport;
import dev.nbmusic.world.SchematicPlacer;
import dev.nbmusic.world.SongBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
		int run(CommandContext<CommandSourceStack> c, Path file, Settings settings) throws CommandSyntaxException;
	}

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
				.executes(c -> action.run(c, resolve(c), Settings.DEFAULT))
				.then(Commands.argument("speed", IntegerArgumentType.integer(10, 400))
						.executes(c -> action.run(c, resolve(c), settings(c, 1)))
						.then(Commands.argument("polyphony", IntegerArgumentType.integer(1, LayoutGenerator.MAX_LEVELS * 2))
								.executes(c -> action.run(c, resolve(c), settings(c, 2)))
								.then(Commands.argument("width", IntegerArgumentType.integer(LayoutGenerator.MIN_ROW_LENGTH, 1024))
										.executes(c -> action.run(c, resolve(c), settings(c, 3)))))));
	}

	/** @param given сколько необязательных аргументов указано: скорость, полифония, ширина */
	private static Settings settings(CommandContext<CommandSourceStack> c, int given) {
		int speed = IntegerArgumentType.getInteger(c, "speed");
		int poly = given >= 2 ? IntegerArgumentType.getInteger(c, "polyphony") : Arranger.Options.DEFAULT.maxPolyphony();
		int width = given >= 3 ? IntegerArgumentType.getInteger(c, "width") : LayoutGenerator.DEFAULT_ROW_LENGTH;
		return new Settings(new Arranger.Options(speed, poly, 0), width);
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
			throw new SimpleCommandExceptionType(Component.literal("Нет такого файла в " + dir)).create();
		}
		return file;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		List<String> files = inputFiles();
		String text = files.isEmpty()
				? "Папка пуста. Положи .mid в " + NbMusicMod.inputDir().toAbsolutePath()
				: "Файлы (" + files.size() + "):\n  " + String.join("\n  ", files);
		c.getSource().sendSuccess(() -> Component.literal(text), false);
		return files.size();
	}

	private static Pipeline.Result process(CommandContext<CommandSourceStack> c, Path file, Settings settings) {
		try {
			return Pipeline.run(file, settings.options(), settings.rowLength());
		} catch (IOException | IllegalArgumentException e) {
			c.getSource().sendFailure(Component.literal("Ошибка: " + e.getMessage()));
			return null;
		}
	}

	private static int info(CommandContext<CommandSourceStack> c, Path file, Settings settings) {
		Pipeline.Result r = process(c, file, settings);
		if (r == null) {
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(SongBuilder.summary(r)), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int export(CommandContext<CommandSourceStack> c, Path file, Settings settings) {
		Pipeline.Result r = process(c, file, settings);
		if (r == null) {
			return 0;
		}
		try {
			List<Path> written = SchematicExport.export(r, c.getSource().getTextName());
			StringBuilder msg = new StringBuilder(SongBuilder.summary(r)).append("\nСохранено: ").append(written.getFirst().toAbsolutePath());
			if (written.size() > 1) {
				msg.append("\nWorldEdit: //schem load ").append(Pipeline.baseName(written.get(1).getFileName().toString()));
			}
			c.getSource().sendSuccess(() -> Component.literal(msg.toString()), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException e) {
			NbMusicMod.LOGGER.error("Не удалось записать схематику", e);
			c.getSource().sendFailure(Component.literal("Не удалось записать: " + e.getMessage()));
			return 0;
		}
	}

	/** Ставит схему перед игроком по направлению его взгляда; уровень механизма — на уровне ног. */
	private static int build(CommandContext<CommandSourceStack> c, Path file, Settings settings) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Pipeline.Result r = process(c, file, settings);
		if (r == null) {
			return 0;
		}
		Direction forward = player.getDirection();
		BlockPos anchor = SchematicPlacer.anchorInFrontOf(player.blockPosition(), forward, r.layout().button());
		try {
			String text = SongBuilder.build(player.level(), r, anchor, forward);
			c.getSource().sendSuccess(() -> Component.literal(text), true);
			return Command.SINGLE_SUCCESS;
		} catch (IllegalArgumentException e) {
			c.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}
}
