package dev.nbmusic;

import dev.nbmusic.core.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Переводимые тексты: у кого мод есть — на его языке, у кого нет — английский запасной текст. */
public final class Texts {
	private Texts() {
	}

	public static MutableComponent of(Msg m) {
		return Component.translatableWithFallback(m.key(), m.english(), m.args());
	}

	public static MutableComponent tr(String key, String english, Object... args) {
		return Component.translatableWithFallback(key, english, args);
	}

	/** Текст ошибки: переводимый, если она пришла из ядра с {@link Msg}, иначе как есть. */
	public static MutableComponent error(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof Msg.Failure f) {
				return of(f.msg());
			}
			if (t instanceof Msg.Invalid i) {
				return of(i.msg());
			}
		}
		return Component.literal(String.valueOf(e.getMessage()));
	}
}
