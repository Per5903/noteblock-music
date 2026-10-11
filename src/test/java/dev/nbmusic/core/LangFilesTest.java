package dev.nbmusic.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Переводы: одинаковые ключи, все ключи из кода есть, совпадают подстановки и английский текст. */
class LangFilesTest {
	private static final Path LANG = Path.of("src/main/resources/assets/nbmusic/lang");
	private static final Pattern CALL = Pattern.compile("(?:Texts\\.tr|new Msg)\\(\\s*\"([a-z0-9_.]+)\"\\s*,\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)");
	private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

	static Map<String, String> lang(String name) throws Exception {
		return new Gson().fromJson(Files.readString(LANG.resolve(name + ".json")), new TypeToken<Map<String, String>>() {
		}.getType());
	}

	static int placeholders(String s) {
		return s.replace("%%", "").split("%s", -1).length - 1;
	}

	@Test
	void languagesHaveSameKeysAndPlaceholders() throws Exception {
		Map<String, String> en = lang("en_us");
		Map<String, String> ru = lang("ru_ru");
		assertEquals(en.keySet(), ru.keySet());
		for (String k : en.keySet()) {
			assertEquals(placeholders(en.get(k)), placeholders(ru.get(k)), "подстановки в " + k);
		}
	}

	@Test
	void everyKeyInCodeIsTranslatedAndMatchesFallback() throws Exception {
		Map<String, String> en = lang("en_us");
		Map<String, String> inCode = new HashMap<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher m = CALL.matcher(Files.readString(f));
				while (m.find()) {
					StringBuilder text = new StringBuilder();
					Matcher lit = LITERAL.matcher(m.group(2));
					while (lit.find()) {
						text.append(lit.group(1).replace("\\\"", "\"").replace("\\n", "\n"));
					}
					inCode.put(m.group(1), text.toString());
				}
			}
		}
		assertTrue(inCode.size() > 50, "ключей в коде: " + inCode.size());
		inCode.forEach((k, text) -> {
			assertTrue(en.containsKey(k), "нет перевода для " + k);
			assertEquals(text, en.get(k), "английский текст в коде и en_us.json расходятся: " + k);
		});
	}
}
