package dev.nbmusic.client;

import dev.nbmusic.NbMusicMod;
import dev.nbmusic.core.layout.LayoutGenerator;
import dev.nbmusic.world.SongBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
	private static final String[] FAMILIES = {
			"Фортепиано", "Хром. перкуссия", "Орган", "Гитара", "Бас", "Струнные", "Ансамбль", "Медные",
			"Язычковые", "Флейты", "Синт-лид", "Синт-пэд", "Синт-эффекты", "Этнические", "Перкуссия", "Эффекты"};

	private final Screen parent;
	private final ClientSession s = ClientSession.INSTANCE;
	private String status;

	public NbMusicScreen(Screen parent) {
		super(Component.literal("NoteBlock Music"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = width / 2 - W / 2;
		int y = 28;

		// Файл: листание папки input, системный диалог, открыть папку
		addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleFile(-1)).bounds(left, y, 20, 20).build());
		String name = s.file == null ? "— файл не выбран —" : s.file.getFileName().toString();
		addRenderableWidget(Button.builder(Component.literal(ellipsize(name, 170)), b -> cycleFile(1)).bounds(left + 22, y, 180, 20).build());
		addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleFile(1)).bounds(left + 204, y, 20, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Обзор…"), b -> browse()).bounds(left + 226, y, 42, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Папка"), b -> openFolder()).bounds(left + 270, y, 40, 20).build());
		y += 24;

		addRenderableWidget(new IntSlider(left, y, 154, 10, 400, s.speed, v -> "Скорость: " + v + "%", v -> s.speed = v));
		addRenderableWidget(new IntSlider(left + 156, y, 154, 1, LayoutGenerator.MAX_LEVELS * 2, s.polyphony,
				v -> "Полифония: " + v + " (этажей " + LayoutGenerator.levelsFor(v) + ")", v -> s.polyphony = v));
		y += 22;
		addRenderableWidget(new IntSlider(left, y, 154, -24, 24, s.transpose,
				v -> "Транспонирование: " + (v > 0 ? "+" : "") + v, v -> s.transpose = v));
		addRenderableWidget(new IntSlider(left + 156, y, 154, LayoutGenerator.MIN_ROW_LENGTH, 256, s.rowLength,
				v -> "Ширина ряда: " + v, v -> s.rowLength = v));
		y += 26;

		// Дорожки (MIDI-каналы), по 3 в строке
		List<ClientSession.Channel> channels = s.channels();
		for (int i = 0; i < channels.size(); i++) {
			ClientSession.Channel ch = channels.get(i);
			String family = ch.isDrums() ? "Ударные" : FAMILIES[ch.program() / 8];
			String label = (s.isMuted(ch.channel()) ? "✘ " : "✔ ") + (ch.channel() + 1) + " " + family;
			addRenderableWidget(Button.builder(Component.literal(ellipsize(label, 96)), b -> {
				s.toggleChannel(ch.channel());
				rebuildWidgets();
			}).bounds(left + (i % 3) * 104, y + 10 + (i / 3) * 20, 102, 18)
					.tooltip(Tooltip.create(Component.literal("Канал " + (ch.channel() + 1) + ": " + family
							+ ", программа " + ch.program() + ", нот " + ch.notes())))
					.build());
		}

		int bottom = height - 50;
		boolean ready = s.result != null;
		Button play = addRenderableWidget(Button.builder(Component.literal(PreviewPlayer.INSTANCE.isPlaying() ? "■ Стоп" : "▶ Слушать"), b -> {
			if (PreviewPlayer.INSTANCE.isPlaying()) {
				PreviewPlayer.INSTANCE.stop();
			} else {
				PreviewPlayer.INSTANCE.start(s.result.arrangement());
			}
			rebuildWidgets();
		}).bounds(left, bottom, 102, 20).build());
		play.active = ready;
		addRenderableWidget(Button.builder(Component.literal("Голограмма: " + (s.hologram ? "вкл" : "выкл")), b -> {
			s.hologram = !s.hologram;
			rebuildWidgets();
		}).bounds(left + 104, bottom, 102, 20).build());
		Button pin = addRenderableWidget(Button.builder(Component.literal(s.pinned == null ? "Закрепить здесь" : "Открепить"), b -> {
			s.togglePin(minecraft.player);
			rebuildWidgets();
		}).bounds(left + 208, bottom, 102, 20).build());
		pin.active = ready && minecraft.player != null;

		Button export = addRenderableWidget(Button.builder(Component.literal("Экспорт .schem"), b -> {
			status = s.export(minecraft.player);
		}).bounds(left, bottom + 24, 102, 20).build());
		export.active = ready && minecraft.player != null;
		Button build = addRenderableWidget(Button.builder(Component.literal("Построить"), b -> {
			String error = s.requestBuild(minecraft.player);
			if (error != null) {
				status = error;
			} else {
				onClose();
			}
		}).bounds(left + 104, bottom + 24, 102, 20).build());
		build.active = ready && minecraft.player != null;
		addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose()).bounds(left + 208, bottom + 24, 102, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int left = width / 2 - W / 2;
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		int y = 28 + 24 + 22 + 26;
		if (!s.channels().isEmpty()) {
			g.text(font, "Дорожки (нажми, чтобы выключить):", left, y, 0xFFA0A0A0);
		}
		int infoY = height - 50 - 34;
		if (s.error != null) {
			g.text(font, s.error, left, infoY, 0xFFFF6060);
		} else if (s.result != null) {
			for (String line : wrap(SongBuilder.summary(s.result), 2)) {
				g.text(font, line, left, infoY, 0xFFE0E0E0);
				infoY += 10;
			}
		} else {
			g.text(font, "Положи .mid в папку (кнопка «Папка») или выбери через «Обзор…»", left, infoY, 0xFFA0A0A0);
		}
		if (PreviewPlayer.INSTANCE.isPlaying()) {
			int barY = height - 50 - 6;
			g.fill(left, barY, left + W, barY + 3, 0xFF404040);
			g.fill(left, barY, left + (int) (W * PreviewPlayer.INSTANCE.progress()), barY + 3, 0xFF4FC3F7);
		}
		if (status != null) {
			g.centeredText(font, ellipsize(status, width - 20), width / 2, height - 10, 0xFFFFFF80);
		}
	}

	@Override
	public void tick() {
		// кнопка «Стоп» сама возвращается в «Слушать», когда песня доиграла
		if (!PreviewPlayer.INSTANCE.isPlaying() && wasPlaying) {
			rebuildWidgets();
		}
		wasPlaying = PreviewPlayer.INSTANCE.isPlaying();
	}

	private boolean wasPlaying;

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
			status = "В папке нет .mid: " + NbMusicMod.inputDir().toAbsolutePath();
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
		Thread t = new Thread(() -> {
			String chosen;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(2);
				filters.put(stack.UTF8("*.mid")).put(stack.UTF8("*.midi")).flip();
				chosen = TinyFileDialogs.tinyfd_openFileDialog("NoteBlock Music: выбери MIDI",
						NbMusicMod.inputDir().toAbsolutePath() + "/", filters, "MIDI (*.mid)", false);
			}
			if (chosen != null) {
				Minecraft.getInstance().execute(() -> {
					s.load(Path.of(chosen));
					rebuildWidgets();
				});
			}
		}, "nbmusic-file-dialog");
		t.setDaemon(true);
		t.start();
	}

	private String ellipsize(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
	}

	private List<String> wrap(String text, int maxLines) {
		return font.split(Component.literal(text), W).stream().limit(maxLines)
				.map(line -> {
					StringBuilder sb = new StringBuilder();
					line.accept((index, style, codePoint) -> {
						sb.appendCodePoint(codePoint);
						return true;
					});
					return sb.toString();
				}).toList();
	}

	/** Целочисленный ползунок; пересчитывает схему, когда его отпускают. */
	private final class IntSlider extends AbstractSliderButton {
		private final int min;
		private final int max;
		private final IntFunction<String> label;
		private final IntConsumer onChange;

		IntSlider(int x, int y, int width, int min, int max, int value, IntFunction<String> label, IntConsumer onChange) {
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
			setMessage(Component.literal(label.apply(intValue())));
		}

		@Override
		protected void applyValue() {
			onChange.accept(intValue());
		}

		@Override
		public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
			super.onRelease(event);
			s.recompute();
		}
	}
}
