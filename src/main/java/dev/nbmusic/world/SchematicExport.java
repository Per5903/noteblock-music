package dev.nbmusic.world;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.Texts;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.export.LitematicWriter;
import dev.nbmusic.core.export.SpongeSchematicWriter;
import dev.nbmusic.core.export.StructureWriter;
import dev.nbmusic.core.io.NbsFormat;
import dev.nbmusic.core.layout.GridPos;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Экспорт схемы во все форматы сразу: .schem (WorldEdit), .litematic (Litematica), .nbt
 * (/place template, структурные блоки) и .nbs (Note Block Studio). Всё ложится в
 * nbmusic/output, а копии — туда, где их сразу видят другие моды и игра.
 */
public final class SchematicExport {
	public static final String STRUCTURE_NAMESPACE = NbMusicMod.MOD_ID;

	private SchematicExport() {
	}

	/**
	 * @param files       все записанные файлы
	 * @param structureId id для {@code /place template}, если .nbt попал в мир; иначе null
	 */
	public record Exported(List<Path> files, String structureId, boolean fitsStructureBlock, boolean worldEdit, boolean litematica) {
		public Component message() {
			MutableComponent m = Texts.tr("nbmusic.export.saved", "Saved to %s: .schem, .litematic, .nbt, .nbs",
					files.getFirst().getParent().toAbsolutePath().toString());
			if (worldEdit) {
				m.append("\n").append(Texts.tr("nbmusic.export.worldedit", "WorldEdit: //schem load %s",
						Pipeline.baseName(files.getFirst().getFileName().toString())));
			}
			if (litematica) {
				m.append("\n").append(Texts.tr("nbmusic.export.litematica", "Litematica: the schematic is in the Load Schematics menu"));
			}
			if (structureId != null) {
				m.append("\n").append(Texts.tr("nbmusic.export.place", "Vanilla: /place template %s", structureId));
				if (!fitsStructureBlock) {
					m.append(" ").append(Texts.tr("nbmusic.export.too_big_for_block", "(larger than 48³ — structure blocks cannot load it)"));
				}
			}
			return m;
		}
	}

	/** Папка структур мира: generated/nbmusic/structure (с 1.21 — в единственном числе, как и в датапаках). */
	public static Path structuresDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.GENERATED_DIR).resolve(STRUCTURE_NAMESPACE).resolve("structure");
	}

	/**
	 * @param worldStructures куда положить .nbt для {@code /place template}, или null (мультиплеер с клиента)
	 */
	public static Exported export(Pipeline.Result r, String author, Path worldStructures) throws IOException {
		String base = fileBase(r.song().title());
		Path out = NbMusicMod.outputDir();
		Files.createDirectories(out);
		int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		List<Path> files = new ArrayList<>();

		Path schem = out.resolve(base + ".schem");
		writeSchem(r, schem, author, dataVersion);
		files.add(schem);
		Path worldEditDir = FabricLoader.getInstance().getConfigDir().resolve("worldedit").resolve("schematics");
		boolean worldEdit = Files.isDirectory(worldEditDir);
		if (worldEdit) {
			Files.copy(schem, worldEditDir.resolve(schem.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		Path litematic = out.resolve(base + ".litematic");
		try (OutputStream os = Files.newOutputStream(litematic)) {
			LitematicWriter.write(r.layout().grid(), r.song().title(), author, dataVersion, os);
		}
		files.add(litematic);
		// Litematica ищет схемы в .minecraft/schematics
		Path litematicaDir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		boolean litematica = FabricLoader.getInstance().isModLoaded("litematica") || Files.isDirectory(litematicaDir);
		if (litematica) {
			Files.createDirectories(litematicaDir);
			Files.copy(litematic, litematicaDir.resolve(litematic.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		String structureName = structureName(r.song().title());
		Path nbt = out.resolve(structureName + ".nbt");
		try (OutputStream os = Files.newOutputStream(nbt)) {
			StructureWriter.write(r.layout().grid(), dataVersion, os);
		}
		files.add(nbt);
		String structureId = null;
		if (worldStructures != null) {
			Files.createDirectories(worldStructures);
			Files.copy(nbt, worldStructures.resolve(nbt.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			structureId = STRUCTURE_NAMESPACE + ":" + structureName;
		}

		Path nbs = out.resolve(base + ".nbs");
		Files.write(nbs, NbsFormat.write(r.arrangement(), r.song().title(), author));
		files.add(nbs);

		return new Exported(files, structureId, StructureWriter.fitsStructureBlock(r.layout().grid()), worldEdit, litematica);
	}

	/** Имя файла: буквы любого алфавита, цифры, пробел, _ . - */
	public static String fileBase(String title) {
		String s = title.replaceAll("[^\\p{L}\\p{N}_.\\- ]", "_").strip();
		return s.isEmpty() ? "song" : s;
	}

	/** Путь ресурса Minecraft: только [a-z0-9_.-]; кириллица и прочее заменяются, хвост — хэш названия. */
	public static String structureName(String title) {
		String ascii = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
		String hash = Integer.toHexString(title.hashCode() & 0xFFFFFF);
		if (ascii.length() < Math.max(3, title.strip().length() / 2)) {
			return "song_" + hash;
		}
		return ascii.length() > 40 ? ascii.substring(0, 40) + "_" + hash : ascii;
	}

	/** При //schem load + //paste кнопка окажется в 2 блоках к востоку от игрока, на уровне ног. */
	private static void writeSchem(Pipeline.Result r, Path out, String author, int dataVersion) throws IOException {
		GridPos button = r.layout().button();
		int[] offset = {2 - button.x(), -button.y(), -button.z()};
		try (OutputStream os = Files.newOutputStream(out)) {
			SpongeSchematicWriter.write(r.layout().grid(), r.song().title(), author, dataVersion, offset, os);
		}
	}
}
