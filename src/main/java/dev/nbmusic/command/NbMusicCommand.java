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
import dev.nbmusic.core.export.SpongeSchematicWriter;
import dev.nbmusic.core.layout.BlockGrid;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.world.SchematicPlacer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * /nbmusic list
 * /nbmusic info|export|build &lt;файл&gt; [скорость%] [полифония]
 */
public final class NbMusicCommand {
	private static final int MAX_BUILD_VOLUME = 4_000_000;

	private NbMusicCommand() {
	}

	private interface Action {
		int run(CommandContext<CommandSourceStack> c, Path file, Arranger.Options options) throws CommandSyntaxException;
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
				.executes(c -> action.run(c, resolve(c), Arranger.Options.DEFAULT))
				.then(Commands.argument("speed", IntegerArgumentType.integer(10, 400))
						.executes(c -> action.run(c, resolve(c), options(c, false)))
						.then(Commands.argument("polyphony", IntegerArgumentType.integer(1, LayoutGenerator.MAX_LANES * 2))
								.executes(c -> action.run(c, resolve(c), options(c, true))))));
	}

	private static Arranger.Options options(CommandContext<CommandSourceStack> c, boolean withPolyphony) {
		int poly = withPolyphony ? IntegerArgumentType.getInteger(c, "polyphony") : Arranger.Options.DEFAULT.maxPolyphony();
		return new Arranger.Options(IntegerArgumentType.getInteger(c, "speed"), poly, 0);
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

	private static Pipeline.Result process(CommandContext<CommandSourceStack> c, Path file, Arranger.Options options) {
		try {
			return Pipeline.run(file, options);
		} catch (IOException | IllegalArgumentException e) {
			c.getSource().sendFailure(Component.literal("Ошибка: " + e.getMessage()));
			return null;
		}
	}

	private static String summary(Pipeline.Result r) {
		var a = r.arrangement();
		BlockGrid g = r.layout().grid();
		return String.format("«%s»: %.1f с, нот %d → %d (срезано %d, перенесено по октаве %d), дорожек %d, размер %d×%d×%d",
				r.song().title(), a.lengthTicks() / (double) Arranger.TICKS_PER_SECOND,
				a.inputNotes(), a.placedNotes(), a.droppedNotes(), a.shiftedNotes(),
				r.layout().lanes(), g.width(), g.height(), g.length());
	}

	private static int info(CommandContext<CommandSourceStack> c, Path file, Arranger.Options options) {
		Pipeline.Result r = process(c, file, options);
		if (r == null) {
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(summary(r)), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int export(CommandContext<CommandSourceStack> c, Path file, Arranger.Options options) {
		Pipeline.Result r = process(c, file, options);
		if (r == null) {
			return 0;
		}
		String name = r.song().title().replaceAll("[^\\p{L}\\p{N}_.\\- ]", "_") + ".schem";
		int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		String author = c.getSource().getTextName();
		try {
			Path out = NbMusicMod.outputDir().resolve(name);
			writeSchematic(r, out, author, dataVersion);
			StringBuilder msg = new StringBuilder(summary(r)).append("\nСохранено: ").append(out.toAbsolutePath());
			Path worldEdit = FabricLoader.getInstance().getConfigDir().resolve("worldedit").resolve("schematics");
			if (Files.isDirectory(worldEdit)) {
				writeSchematic(r, worldEdit.resolve(name), author, dataVersion);
				msg.append("\nWorldEdit: //schem load ").append(Pipeline.baseName(name));
			}
			c.getSource().sendSuccess(() -> Component.literal(msg.toString()), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException e) {
			NbMusicMod.LOGGER.error("Не удалось записать схематику", e);
			c.getSource().sendFailure(Component.literal("Не удалось записать: " + e.getMessage()));
			return 0;
		}
	}

	private static void writeSchematic(Pipeline.Result r, Path out, String author, int dataVersion) throws IOException {
		try (OutputStream os = Files.newOutputStream(out)) {
			SpongeSchematicWriter.write(r.layout().grid(), r.song().title(), author, dataVersion, os);
		}
	}

	/** Ставит схему в мир: начало в 2 блоках по +X от игрока, механизм на уровне ног, кнопка напротив игрока. */
	private static int build(CommandContext<CommandSourceStack> c, Path file, Arranger.Options options) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Pipeline.Result r = process(c, file, options);
		if (r == null) {
			return 0;
		}
		BlockGrid grid = r.layout().grid();
		long volume = (long) grid.width() * grid.height() * grid.length();
		if (volume > MAX_BUILD_VOLUME) {
			c.getSource().sendFailure(Component.literal("Слишком большая схема (" + volume + " блоков), используй export + WorldEdit"));
			return 0;
		}
		ServerLevel level = player.level();
		BlockPos origin = player.blockPosition().offset(2, -2, -r.layout().buttonZ());
		SchematicPlacer.place(level, origin, grid);
		BlockPos button = origin.offset(0, 2, r.layout().buttonZ());
		c.getSource().sendSuccess(() -> Component.literal(summary(r) + "\nПостроено. Кнопка запуска: "
				+ button.getX() + " " + button.getY() + " " + button.getZ()), true);
		return Command.SINGLE_SUCCESS;
	}
}
