package dev.nbmusic.world;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.export.SpongeSchematicWriter;
import dev.nbmusic.core.layout.GridPos;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Запись .schem в nbmusic/output (и в папку WorldEdit, если она есть). */
public final class SchematicExport {
	private SchematicExport() {
	}

	/** @return пути, куда записан файл: сначала nbmusic/output, затем WorldEdit */
	public static List<Path> export(Pipeline.Result r, String author) throws IOException {
		String name = fileName(r.song().title());
		List<Path> written = new ArrayList<>();
		Path out = NbMusicMod.outputDir().resolve(name);
		Files.createDirectories(out.getParent());
		write(r, out, author);
		written.add(out);
		Path worldEdit = FabricLoader.getInstance().getConfigDir().resolve("worldedit").resolve("schematics");
		if (Files.isDirectory(worldEdit)) {
			write(r, worldEdit.resolve(name), author);
			written.add(worldEdit.resolve(name));
		}
		return written;
	}

	public static String fileName(String title) {
		return title.replaceAll("[^\\p{L}\\p{N}_.\\- ]", "_") + ".schem";
	}

	/** При //schem load + //paste кнопка окажется в 2 блоках к востоку от игрока, на уровне ног. */
	private static void write(Pipeline.Result r, Path out, String author) throws IOException {
		GridPos button = r.layout().button();
		int[] offset = {2 - button.x(), -button.y(), -button.z()};
		int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		try (OutputStream os = Files.newOutputStream(out)) {
			SpongeSchematicWriter.write(r.layout().grid(), r.song().title(), author, dataVersion, offset, os);
		}
	}
}
