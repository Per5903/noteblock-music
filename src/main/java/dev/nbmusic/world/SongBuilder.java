package dev.nbmusic.world;

import dev.nbmusic.Texts;
import dev.nbmusic.core.Msg;
import dev.nbmusic.core.Pipeline;
import dev.nbmusic.core.arrange.Arranger;
import dev.nbmusic.core.layout.BlockGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/** Постройка песни в мире — общая для /nbmusic build и кнопки «Построить» в окне. */
public final class SongBuilder {
	public static final int MAX_BUILD_VOLUME = 4_000_000;

	private SongBuilder() {
	}

	/**
	 * @return сообщение для чата со сводкой и координатами кнопки
	 * @throws Msg.Invalid если схема не помещается
	 */
	public static Component build(ServerLevel level, Pipeline.Result r, BlockPos anchor, Direction forward) {
		BlockGrid grid = r.layout().grid();
		long volume = (long) grid.width() * grid.height() * grid.length();
		if (volume > MAX_BUILD_VOLUME) {
			throw new Msg.Invalid(new Msg("nbmusic.error.build_too_big",
					"The build is too large (%s blocks) — use export and WorldEdit or Litematica", volume));
		}
		if (anchor.getY() < level.getMinY() || anchor.getY() + grid.height() - 1 > level.getMaxY()) {
			throw new Msg.Invalid(new Msg("nbmusic.error.build_too_high",
					"A build %s blocks tall does not fit within the world height", grid.height()));
		}
		SchematicPlacer.place(level, anchor, forward, grid);
		BlockPos button = SchematicPlacer.toWorld(anchor, forward, r.layout().button());
		return summary(r).append("\n").append(Texts.tr("nbmusic.built", "Built. Start button: %s %s %s",
				button.getX(), button.getY(), button.getZ()));
	}

	public static MutableComponent summary(Pipeline.Result r) {
		var a = r.arrangement();
		BlockGrid g = r.layout().grid();
		String seconds = String.format(Locale.ROOT, "%.1f", a.lengthTicks() / (double) Arranger.TICKS_PER_SECOND);
		return Texts.tr("nbmusic.summary",
				"“%s”: %s s, notes %s → %s (dropped %s, octave-shifted %s), levels %s, rows %s, size %s×%s×%s",
				r.song().title(), seconds, a.inputNotes(), a.placedNotes(), a.droppedNotes(), a.shiftedNotes(),
				r.layout().levels(), r.layout().rows(), g.width(), g.height(), g.length());
	}
}
