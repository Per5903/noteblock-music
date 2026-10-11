package dev.nbmusic.core.layout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Разбор и поворот строк состояний вида {@code minecraft:repeater[delay=2,facing=west]}. */
public final class BlockStates {
	/** Стороны света по часовой стрелке (вид сверху: +X восток, +Z юг). */
	private static final List<String> CLOCKWISE = List.of("north", "east", "south", "west");

	private BlockStates() {
	}

	public static String name(String state) {
		int bracket = state.indexOf('[');
		return bracket < 0 ? state : state.substring(0, bracket);
	}

	public static Map<String, String> properties(String state) {
		Map<String, String> props = new LinkedHashMap<>();
		int bracket = state.indexOf('[');
		if (bracket < 0) {
			return props;
		}
		for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
			int eq = pair.indexOf('=');
			props.put(pair.substring(0, eq), pair.substring(eq + 1));
		}
		return props;
	}

	public static String of(String name, Map<String, String> props) {
		if (props.isEmpty()) {
			return name;
		}
		StringJoiner joiner = new StringJoiner(",", name + "[", "]");
		props.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(e -> joiner.add(e.getKey() + "=" + e.getValue()));
		return joiner.toString();
	}

	/** Поворот вокруг вертикали на {@code turns} четвертей по часовой стрелке. */
	public static String rotate(String state, int turns) {
		turns = Math.floorMod(turns, 4);
		if (turns == 0 || state.indexOf('[') < 0) {
			return state;
		}
		Map<String, String> props = properties(state);
		Map<String, String> out = new LinkedHashMap<>();
		for (var e : props.entrySet()) {
			String key = e.getKey();
			String value = e.getValue();
			if (CLOCKWISE.contains(key)) {
				// redstone_wire: north/east/south/west=side|up|none
				out.put(turn(key, turns), value);
			} else if (key.equals("facing") && CLOCKWISE.contains(value)) {
				out.put(key, turn(value, turns));
			} else if (key.equals("axis") && turns % 2 == 1 && !value.equals("y")) {
				out.put(key, value.equals("x") ? "z" : "x");
			} else {
				out.put(key, value);
			}
		}
		return of(name(state), out);
	}

	static String turn(String direction, int turns) {
		return CLOCKWISE.get((CLOCKWISE.indexOf(direction) + turns) % 4);
	}
}
