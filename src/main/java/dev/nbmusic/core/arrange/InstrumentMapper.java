package dev.nbmusic.core.arrange;

import dev.nbmusic.core.model.Instrument;

import java.util.List;

import static dev.nbmusic.core.model.Instrument.*;

/** Программы General MIDI → семейства инструментов нотного блока. */
public final class InstrumentMapper {
	private InstrumentMapper() {
	}

	/** Ударная нота: инструмент + фиксированный ключ. */
	public record Drum(Instrument instrument, int key) {
	}

	/**
	 * Инструменты в порядке предпочтения. Берётся первый, в диапазон которого
	 * попадает нота; остальные служат запасными для низких и высоких нот.
	 */
	public static List<Instrument> family(int program) {
		int group = program / 8;
		return switch (group) {
			case 0 -> List.of(HARP, BASS, BELL);                       // фортепиано
			case 1 -> program == 13
					? List.of(XYLOPHONE, IRON_XYLOPHONE, BASS)          // ксилофон
					: List.of(BELL, IRON_XYLOPHONE, BASS);              // хроматическая перкуссия
			case 2 -> List.of(PLING, BASS, BELL);                      // орган
			case 3 -> List.of(GUITAR, BASS, HARP, BELL);               // гитары
			case 4 -> List.of(BASS, GUITAR, HARP);                     // басы
			case 5, 6 -> List.of(FLUTE, HARP, BASS, BELL);             // струнные, ансамбль
			case 7 -> List.of(FLUTE, DIDGERIDOO, HARP, BELL);          // медные
			case 8, 9 -> List.of(FLUTE, HARP, BASS, BELL);             // язычковые, флейты
			case 10 -> List.of(BIT, BASS, BELL);                       // синт-лид
			case 11, 12 -> List.of(PLING, BASS, BELL);                 // синт-пэд, эффекты
			case 13 -> List.of(BANJO, BASS, BELL);                     // этнические
			case 14 -> List.of(COW_BELL, HARP, BASS);                  // перкуссия
			default -> List.of(HARP, BASS, BELL);
		};
	}

	/** Карта ударных канала 10 (GM Percussion Key Map). */
	public static Drum drum(int midiKey) {
		return switch (midiKey) {
			case 35, 36 -> new Drum(BASEDRUM, 6);                       // бочка
			case 41 -> new Drum(BASEDRUM, 10);                          // томы
			case 43 -> new Drum(BASEDRUM, 12);
			case 45 -> new Drum(BASEDRUM, 14);
			case 47 -> new Drum(BASEDRUM, 16);
			case 48 -> new Drum(BASEDRUM, 18);
			case 50 -> new Drum(BASEDRUM, 20);
			case 37 -> new Drum(SNARE, 18);                             // сайд-стик
			case 38, 39, 40 -> new Drum(SNARE, 12);                     // малый, хлопок
			case 42, 44 -> new Drum(HAT, 16);                           // закрытый хэт
			case 46 -> new Drum(HAT, 12);                               // открытый хэт
			case 49, 51, 52, 53, 55, 57, 59 -> new Drum(HAT, 6);        // тарелки
			case 54 -> new Drum(HAT, 20);                               // бубен
			case 56 -> new Drum(COW_BELL, 12);                          // ковбелл
			case 60, 62 -> new Drum(BASEDRUM, 22);                      // бонги, конги
			case 61, 63, 64 -> new Drum(BASEDRUM, 18);
			default -> new Drum(HAT, 12);
		};
	}
}
