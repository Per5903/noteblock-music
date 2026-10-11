package dev.nbmusic.core;

import java.util.Locale;

/**
 * Сообщение для игрока: ключ перевода, английский текст (он же запасной, если у игрока нет мода)
 * и аргументы для {@code %s}. Ядро не зависит от Minecraft, поэтому переводит уже слой мода.
 */
public record Msg(String key, String english, Object... args) {
	public String text() {
		return String.format(Locale.ROOT, english, args);
	}

	/** Ошибка ввода-вывода с переводимым текстом. */
	public static final class Failure extends java.io.IOException {
		private final Msg msg;

		public Failure(Msg msg) {
			super(msg.text());
			this.msg = msg;
		}

		public Failure(Msg msg, Throwable cause) {
			super(msg.text(), cause);
			this.msg = msg;
		}

		public Msg msg() {
			return msg;
		}
	}

	/** Неверные данные или настройки с переводимым текстом. */
	public static final class Invalid extends IllegalArgumentException {
		private final Msg msg;

		public Invalid(Msg msg) {
			super(msg.text());
			this.msg = msg;
		}

		public Msg msg() {
			return msg;
		}
	}
}
