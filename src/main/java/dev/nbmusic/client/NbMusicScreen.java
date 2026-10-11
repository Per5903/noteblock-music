package dev.nbmusic.client;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.Texts;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.world.SongBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Окно мода (клавиша R): файл, настройки, дорожки, предпрослушивание, голограмма, экспорт и постройка. */
public final class NbMusicScreen extends Screen {
	private static final int W = 310;
	private static final String[] FAMILIES_EN = {
			"Piano", "Chromatic perc.", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass",
			"Reed", "Pipe", "Synth lead", "Synth pad", "Synth FX", "Ethnic", "Percussive", "Sound FX"};

	private final Screen parent;
	private final ClientSession s = ClientSession.INSTANCE;
	/** Сообщение после экспорта/ошибки; показывается вместо сводки, пока не изменятся настройки. */
	private Component status;

	public NbMusicScreen(Screen parent) {
		super(Component.literal("NoteBlock Music"));
		this.parent = parent;
		s.restoreLastFile();
	}

	@Override
	protected void init() {
		int left = width / 2 - W / 2;
		int y = 28;

		// Файл: листание папки input, системный диалог, открыть папку
		addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleFile(-1)).bounds(left, y, 20, 20).build());
		Component name = s.file == null ? Texts.tr("nbmusic.gui.no_file", "— no file selected —") : Component.literal(s.file.getFileName().toString());
		addRenderableWidget(Button.builder(ellipsize(name, 170), b -> cycleFile(1)).bounds(left + 22, y, 180, 20).build());
		addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleFile(1)).bounds(left + 204, y, 20, 20).build());
		addRenderableWidget(Button.builder(ellipsize(Texts.tr("nbmusic.gui.browse", "Browse…"), 38), b -> browse()).bounds(left + 226, y, 42, 20).build());
		addRenderableWidget(Button.builder(ellipsize(Texts.tr("nbmusic.gui.folder", "Folder"), 36), b -> openFolder()).bounds(left + 270, y, 40, 20).build());
		y += 24;

		addRenderableWidget(new IntSlider(left, y, 154, 10, 400, s.speed,
				v -> Texts.tr("nbmusic.gui.speed", "Speed: %s%%", v), v -> s.speed = v));
		addRenderableWidget(new IntSlider(left + 156, y, 154, 1, LayoutGenerator.MAX_LEVELS * 2, s.polyphony,
				v -> Texts.tr("nbmusic.gui.polyphony", "Polyphony: %s (levels: %s)", v, LayoutGenerator.levelsFor(v)), v -> s.polyphony = v));
		y += 22;
		addRenderableWidget(new IntSlider(left, y, 154, -24, 24, s.transpose,
				v -> Texts.tr("nbmusic.gui.transpose", "Transpose: %s", (v > 0 ? "+" : "") + v), v -> s.transpose = v));
		addRenderableWidget(new IntSlider(left + 156, y, 154, LayoutGenerator.MIN_ROW_LENGTH, 256, s.rowLength,
				v -> Texts.tr("nbmusic.gui.row_width", "Row width: %s", v), v -> s.rowLength = v));
		y += 22;
		// высота меняет только место постройки, а не схему — пересчёт не нужен
		addRenderableWidget(new IntSlider(left, y, 310, -32, 32, s.heightOffset,
				v -> Texts.tr("nbmusic.gui.height", "Height: %s blocks from your feet", (v > 0 ? "+" : "") + v),
				v -> s.heightOffset = v));
		y += 26;

		// Дорожки: MIDI-каналы или инструменты .nbs, по 3 в строке
		List<ClientSession.Channel> channels = s.channels();
		for (int i = 0; i < channels.size(); i++) {
			ClientSession.Channel ch = channels.get(i);
			Component trackName = trackName(ch);
			Component label = Component.literal((s.isMuted(ch.channel()) ? "✘ " : "✔ ") + (ch.instrument() == null ? (ch.channel() + 1) + " " : ""))
					.append(trackName);
			Component tip = ch.instrument() != null
					? Texts.tr("nbmusic.gui.track_tip_nbs", "%s: %s notes", trackName, ch.notes())
					: Texts.tr("nbmusic.gui.track_tip", "Channel %s: %s, program %s, %s notes", ch.channel() + 1, trackName, ch.program(), ch.notes());
			addRenderableWidget(Button.builder(ellipsize(label, 96), b -> {
				s.toggleChannel(ch.channel());
				status = null;
				rebuildWidgets();
			}).bounds(left + (i % 3) * 104, y + 10 + (i / 3) * 20, 102, 18).tooltip(Tooltip.create(tip)).build());
		}

		int bottom = height - 50;
		boolean ready = s.result != null;
		Component playLabel = PreviewPlayer.INSTANCE.isPlaying()
				? Texts.tr("nbmusic.gui.stop", "■ Stop") : Texts.tr("nbmusic.gui.listen", "▶ Listen");
		Button play = addRenderableWidget(Button.builder(playLabel, b -> {
			if (PreviewPlayer.INSTANCE.isPlaying()) {
				PreviewPlayer.INSTANCE.stop();
			} else {
				PreviewPlayer.INSTANCE.start(s.result.arrangement());
			}
			rebuildWidgets();
		}).bounds(left, bottom, 102, 20).build());
		play.active = ready;
		Component holo = s.hologram ? Texts.tr("nbmusic.gui.hologram_on", "Hologram: on") : Texts.tr("nbmusic.gui.hologram_off", "Hologram: off");
		addRenderableWidget(Button.builder(holo, b -> {
			s.hologram = !s.hologram;
			s.saveConfig();
			rebuildWidgets();
		}).bounds(left + 104, bottom, 102, 20).build());
		Component pinLabel = s.pinned == null ? Texts.tr("nbmusic.gui.pin", "Pin here") : Texts.tr("nbmusic.gui.unpin", "Unpin");
		Button pin = addRenderableWidget(Button.builder(pinLabel, b -> {
			s.togglePin(minecraft.player);
			rebuildWidgets();
		}).bounds(left + 208, bottom, 102, 20).build());
		pin.active = ready && minecraft.player != null;

		Button export = addRenderableWidget(Button.builder(Texts.tr("nbmusic.gui.export", "Export"), b -> status = s.export(minecraft.player))
				.bounds(left, bottom + 24, 102, 20)
				.tooltip(Tooltip.create(Texts.tr("nbmusic.gui.export_tip", ".schem (WorldEdit), .litematic (Litematica), .nbt (/place template), .nbs (Note Block Studio)")))
				.build());
		export.active = ready && minecraft.player != null;
		Button build = addRenderableWidget(Button.builder(Texts.tr("nbmusic.gui.build", "Build"), b -> {
			Component error = s.requestBuild(minecraft.player);
			if (error != null) {
				status = error;
			} else {
				onClose();
			}
		}).bounds(left + 104, bottom + 24, 102, 20).build());
		build.active = ready && minecraft.player != null;
		addRenderableWidget(Button.builder(Texts.tr("nbmusic.gui.done", "Done"), b -> onClose()).bounds(left + 208, bottom + 24, 102, 20).build());
	}

	private static Component trackName(ClientSession.Channel ch) {
		if (ch.instrument() != null) {
			String id = ch.instrument().id;
			return Texts.tr("nbmusic.instrument." + id, id);
		}
		if (ch.isDrums()) {
			return Texts.tr("nbmusic.family.drums", "Drums");
		}
		int f = ch.program() / 8;
		return Texts.tr("nbmusic.family." + f, FAMILIES_EN[f]);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int left = width / 2 - W / 2;
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		int y = 28 + 24 + 22 + 22 + 26;
		if (!s.channels().isEmpty()) {
			g.text(font, Texts.tr("nbmusic.gui.tracks", "Tracks (click to mute):"), left, y, 0xFFA0A0A0);
		}
		int infoY = height - 50 - 34;
		if (s.isTranscribing()) {
			int pct = (int) Math.round(s.transcribeProgress * 100);
			g.text(font, Texts.tr("nbmusic.gui.transcribing", "Transcribing notes with Basic Pitch… %s%%", pct), left, infoY, 0xFF80D8FF);
			g.fill(left, infoY + 12, left + W, infoY + 15, 0xFF404040);
			g.fill(left, infoY + 12, left + (int) (W * s.transcribeProgress), infoY + 15, 0xFF80D8FF);
		} else if (status != null) {
			drawLines(g, status, left, infoY - 10, 4, 0xFFFFFF80);
		} else if (s.error != null) {
			drawLines(g, s.error, left, infoY, 3, 0xFFFF6060);
		} else if (s.result != null) {
			drawLines(g, SongBuilder.summary(s.result), left, infoY, 2, 0xFFE0E0E0);
		} else {
			drawLines(g, Texts.tr("nbmusic.gui.hint", "Put .mid / .nbs / .mp3 / .wav / .ogg into the folder or pick one with “Browse…”"),
					left, infoY, 2, 0xFFA0A0A0);
		}
		if (PreviewPlayer.INSTANCE.isPlaying()) {
			int barY = height - 50 - 6;
			g.fill(left, barY, left + W, barY + 3, 0xFF404040);
			g.fill(left, barY, left + (int) (W * PreviewPlayer.INSTANCE.progress()), barY + 3, 0xFF4FC3F7);
		}
	}

	/** Текст с переносами (и явными \n), не больше {@code maxLines} строк. */
	private void drawLines(GuiGraphicsExtractor g, Component text, int x, int y, int maxLines, int color) {
		int drawn = 0;
		for (String paragraph : text.getString().split("\n")) {
			for (FormattedCharSequence line : font.split(Component.literal(paragraph), W)) {
				if (drawn++ >= maxLines) {
					return;
				}
				g.text(font, line, x, y, color);
				y += 10;
			}
		}
	}

	@Override
	public void tick() {
		// кнопка «Стоп» сама возвращается в «Слушать», когда песня доиграла,
		// и кнопки включаются, когда распознавание закончилось
		boolean busy = s.isTranscribing();
		if (!PreviewPlayer.INSTANCE.isPlaying() && wasPlaying || busy != wasBusy) {
			rebuildWidgets();
		}
		wasPlaying = PreviewPlayer.INSTANCE.isPlaying();
		wasBusy = busy;
	}

	private boolean wasPlaying;
	private boolean wasBusy;

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private void cycleFile(int step) {
		List<Path> files = ClientSession.inputFiles();
		if (files.isEmpty()) {
			status = Texts.tr("nbmusic.gui.folder_empty", "No music in %s", NbMusicMod.inputDir().toAbsolutePath().toString());
			return;
		}
		int i = files.indexOf(s.file);
		s.load(files.get(Math.floorMod(i < 0 && step < 0 ? 0 : i + step, files.size())));
		status = null;
		rebuildWidgets();
	}

	private void openFolder() {
		try {
			Files.createDirectories(NbMusicMod.inputDir());
		} catch (Exception ignored) {
			// откроется то, что есть
		}
		Util.getPlatform().openPath(NbMusicMod.inputDir());
	}

	/** Системный диалог выбора файла; блокирующий, поэтому в отдельном потоке. */
	private void browse() {
		String dialogTitle = Texts.tr("nbmusic.gui.browse_title", "NoteBlock Music: choose music").getString();
		Thread t = new Thread(() -> {
			String chosen;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				String[] patterns = {"*.mid", "*.midi", "*.nbs", "*.mp3", "*.wav", "*.ogg"};
				PointerBuffer filters = stack.mallocPointer(patterns.length);
				for (String p : patterns) {
					filters.put(stack.UTF8(p));
				}
				filters.flip();
				chosen = TinyFileDialogs.tinyfd_openFileDialog(dialogTitle,
						NbMusicMod.inputDir().toAbsolutePath() + "/", filters, "MIDI / NBS / MP3 / WAV / OGG", false);
			}
			if (chosen != null) {
				Minecraft.getInstance().execute(() -> {
					s.load(Path.of(chosen));
					status = null;
					rebuildWidgets();
				});
			}
		}, "nbmusic-file-dialog");
		t.setDaemon(true);
		t.start();
	}

	private Component ellipsize(Component text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return Component.literal(font.plainSubstrByWidth(text.getString(), maxWidth - font.width("…")) + "…");
	}

	/** Целочисленный ползунок; пересчитывает схему, когда его отпускают. */
	private final class IntSlider extends AbstractSliderButton {
		private final int min;
		private final int max;
		private final IntFunction<Component> label;
		private final IntConsumer onChange;

		IntSlider(int x, int y, int width, int min, int max, int value, IntFunction<Component> label, IntConsumer onChange) {
			super(x, y, width, 20, Component.empty(), (double) (value - min) / (max - min));
			this.min = min;
			this.max = max;
			this.label = label;
			this.onChange = onChange;
			updateMessage();
		}

		private int intValue() {
			return min + (int) Math.round(Mth.clamp(value, 0, 1) * (max - min));
		}

		@Override
		protected void updateMessage() {
			setMessage(label.apply(intValue()));
		}

		@Override
		protected void applyValue() {
			onChange.accept(intValue());
		}

		@Override
		public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
			super.onRelease(event);
			status = null;
			s.recompute();
		}
	}
}
