package dev.nbmusic.world;

import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.layout.BlockGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Постройка песни в мире — общая для /nbmusic build и кнопки «Построить» в окне. */
public final class SongBuilder {
	public static final int MAX_BUILD_VOLUME = 4_000_000;

	private SongBuilder() {
	}

	/**
	 * @return текст для чата с координатами кнопки
	 * @throws IllegalArgumentException если схема не помещается
	 */
	public static String build(ServerLevel level, Pipeline.Result r, BlockPos anchor, Direction forward) {
		BlockGrid grid = r.layout().grid();
		long volume = (long) grid.width() * grid.height() * grid.length();
		if (volume > MAX_BUILD_VOLUME) {
			throw new IllegalArgumentException("Слишком большая схема (" + volume + " блоков), используй export + WorldEdit");
		}
		if (anchor.getY() < level.getMinY() || anchor.getY() + grid.height() - 1 > level.getMaxY()) {
			throw new IllegalArgumentException("Схема высотой " + grid.height() + " не помещается по высоте мира");
		}
		SchematicPlacer.place(level, anchor, forward, grid);
		BlockPos button = SchematicPlacer.toWorld(anchor, forward, r.layout().button());
		return summary(r) + "\nПостроено. Кнопка запуска: " + button.getX() + " " + button.getY() + " " + button.getZ();
	}

	public static String summary(Pipeline.Result r) {
		var a = r.arrangement();
		BlockGrid g = r.layout().grid();
		return String.format("«%s»: %.1f с, нот %d → %d (срезано %d, перенесено по октаве %d), этажей %d, рядов %d, размер %d×%d×%d",
				r.song().title(), a.lengthTicks() / (double) Arranger.TICKS_PER_SECOND,
				a.inputNotes(), a.placedNotes(), a.droppedNotes(), a.shiftedNotes(),
				r.layout().levels(), r.layout().rows(), g.width(), g.height(), g.length());
	}
}
