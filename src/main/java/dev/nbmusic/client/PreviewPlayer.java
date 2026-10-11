package dev.nbmusic.client;

import dev.nbmusic.core.arrange.Arranger.Arrangement;
import dev.nbmusic.core.model.Note;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.util.List;

/** Предпрослушивание: проигрывает аранжировку звуками нотных блоков, как её сыграет схема. */
public final class PreviewPlayer {
	public static final PreviewPlayer INSTANCE = new PreviewPlayer();

	private Arrangement arrangement;
	private int clientTicks;
	/** Сколько нот прозвучало с последнего запуска (для тестов и полосы прогресса). */
	public int notesPlayed;

	private PreviewPlayer() {
	}

	public boolean isPlaying() {
		return arrangement != null;
	}

	public void start(Arrangement arrangement) {
		this.arrangement = arrangement;
		clientTicks = 0;
		notesPlayed = 0;
	}

	public void stop() {
		arrangement = null;
	}

	/** Доля проигранного, 0..1. */
	public float progress() {
		return arrangement == null ? 0 : Math.min(1, clientTicks / 2f / arrangement.lengthTicks());
	}

	/** Вызывается каждый клиентский тик (20 в секунду), редстоун-тик — каждые 2. */
	public void tick() {
		if (arrangement == null) {
			return;
		}
		if (clientTicks % 2 == 0) {
			List<Note> chord = arrangement.chords().get(clientTicks / 2);
			if (chord != null) {
				for (Note n : chord) {
					play(n);
				}
			}
		}
		clientTicks++;
		if (clientTicks / 2 >= arrangement.lengthTicks()) {
			stop();
		}
	}

	private void play(Note note) {
		NoteBlockInstrument inst = NoteBlockInstrument.valueOf(note.instrument().name());
		float pitch = NoteBlock.getPitchFromNote(note.key());
		float volume = 0.4f + 0.6f * note.velocity() / 127f;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(inst.getSoundEvent().value(), pitch, volume));
		notesPlayed++;
	}
}
