package dev.nbmusic.core.model;

/**
 * Инструменты нотного блока. Инструмент задаётся блоком под нотным блоком,
 * у каждого мелодического инструмента свой диапазон в 2 октавы (ключи 0..24).
 */
public enum Instrument {
	HARP("harp", 54, "minecraft:dirt"),
	BASS("bass", 30, "minecraft:oak_planks"),
	BASEDRUM("basedrum", -1, "minecraft:stone"),
	SNARE("snare", -1, "minecraft:sand"),
	HAT("hat", -1, "minecraft:glass"),
	GUITAR("guitar", 42, "minecraft:white_wool"),
	FLUTE("flute", 66, "minecraft:clay"),
	BELL("bell", 78, "minecraft:gold_block"),
	CHIME("chime", 78, "minecraft:packed_ice"),
	XYLOPHONE("xylophone", 78, "minecraft:bone_block[axis=y]"),
	IRON_XYLOPHONE("iron_xylophone", 54, "minecraft:iron_block"),
	COW_BELL("cow_bell", 66, "minecraft:soul_sand"),
	DIDGERIDOO("didgeridoo", 30, "minecraft:pumpkin"),
	BIT("bit", 54, "minecraft:emerald_block"),
	BANJO("banjo", 54, "minecraft:hay_block[axis=y]"),
	PLING("pling", 54, "minecraft:glowstone");

	public static final int KEYS = 25;

	/** Значение свойства instrument у minecraft:note_block. */
	public final String id;
	/** MIDI-номер ноты для ключа 0 (F#), либо -1 для ударных. */
	public final int lowestMidi;
	/** Блок, который ставится под нотный блок. */
	public final String baseBlock;

	Instrument(String id, int lowestMidi, String baseBlock) {
		this.id = id;
		this.lowestMidi = lowestMidi;
		this.baseBlock = baseBlock;
	}

	public boolean isPercussion() {
		return lowestMidi < 0;
	}

	public boolean contains(int midiPitch) {
		return !isPercussion() && midiPitch >= lowestMidi && midiPitch < lowestMidi + KEYS;
	}

	/** Блок-подложка падает (песок), под ним нужна опора. */
	public boolean needsSupport() {
		return this == SNARE;
	}
}
