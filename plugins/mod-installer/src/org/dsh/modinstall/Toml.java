package org.dsh.modinstall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal TOML reader, just enough for Forge / NeoForge {@code mods.toml}.
 *
 * <p>The launcher ships a JSON reader but no TOML one, and Forge metadata is TOML
 * only, so the subset that actually appears in mod metadata is implemented here:</p>
 *
 * <ul>
 *   <li>{@code key = "string"}, {@code key = 1.2}, {@code key = true};</li>
 *   <li>{@code [table]} and dotted keys ({@code a.b = 1});</li>
 *   <li>{@code [[array.of.tables]]} — the form Forge uses for
 *       {@code [[mods]]} and {@code [[dependencies.<modid>]]};</li>
 *   <li>{@code #} comments, single- and double-quoted strings, inline arrays of
 *       strings ({@code ["a", "b"]}, including multi-line).</li>
 * </ul>
 *
 * <p>Values come back as {@code String}, {@code Boolean}, {@code Long},
 * {@code Double}, {@code List<Object>} or {@code Map<String, Object>}. Anything
 * the reader does not understand is skipped rather than throwing: one unknown
 * line must not make a mod unreadable.</p>
 */
final class Toml {

    private Toml() {
    }

    /** Parses TOML text into nested maps/lists. */
    static Map<String, Object> parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        // The table that bare `key = value` lines are assigned into.
        Map<String, Object> current = root;
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));

        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[[") && line.endsWith("]]")) {
                String path = line.substring(2, line.length() - 2).trim();
                current = appendArrayTable(root, splitKey(path));
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String path = line.substring(1, line.length() - 1).trim();
                current = tableAt(root, splitKey(path));
                continue;
            }
            int equals = indexOfEquals(line);
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String rawValue = line.substring(equals + 1).trim();
            // A multi-line array (Forge writes long dependency lists that way).
            if (rawValue.startsWith("[") && !isBalanced(rawValue)) {
                StringBuilder joined = new StringBuilder(rawValue);
                while (++i < lines.size() && !isBalanced(joined.toString())) {
                    joined.append(' ').append(stripComment(lines.get(i)).trim());
                }
                rawValue = joined.toString();
            }
            assign(current, splitKey(key), parseValue(rawValue));
        }
        return root;
    }

    /** Reads a TOML file. */
    static Map<String, Object> parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------- navigation

    /** Walks/creates the plain tables along {@code path}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> tableAt(Map<String, Object> root, List<String> path) {
        Map<String, Object> node = root;
        for (String part : path) {
            Object existing = node.get(part);
            if (existing instanceof Map) {
                node = (Map<String, Object>) existing;
            } else {
                Map<String, Object> created = new LinkedHashMap<>();
                node.put(part, created);
                node = created;
            }
        }
        return node;
    }

    /** Appends a new table to the array of tables at {@code path}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> appendArrayTable(Map<String, Object> root, List<String> path) {
        if (path.isEmpty()) {
            return root;
        }
        Map<String, Object> parent = tableAt(root, path.subList(0, path.size() - 1));
        String name = path.get(path.size() - 1);
        Object existing = parent.get(name);
        List<Object> list;
        if (existing instanceof List) {
            list = (List<Object>) existing;
        } else {
            list = new ArrayList<>();
            parent.put(name, list);
        }
        Map<String, Object> created = new LinkedHashMap<>();
        list.add(created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static void assign(Map<String, Object> table, List<String> path, Object value) {
        Map<String, Object> node = table;
        for (int i = 0; i < path.size() - 1; i++) {
            Object existing = node.get(path.get(i));
            if (existing instanceof Map) {
                node = (Map<String, Object>) existing;
            } else {
                Map<String, Object> created = new LinkedHashMap<>();
                node.put(path.get(i), created);
                node = created;
            }
        }
        node.put(path.get(path.size() - 1), value);
    }

    // ---------------------------------------------------------------- values

    private static Object parseValue(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("[")) {
            return parseArray(value);
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            return unquote(value);
        }
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        Long asLong = tryLong(value);
        if (asLong != null) {
            return asLong;
        }
        Double asDouble = tryDouble(value);
        if (asDouble != null) {
            return asDouble;
        }
        // Dates, times and anything else: keep the raw text, callers ignore it.
        return value;
    }

    /** Parses {@code ["a", "b"]}; nested tables are not used by mod metadata. */
    private static List<Object> parseArray(String raw) {
        List<Object> values = new ArrayList<>();
        String body = raw.substring(1, raw.endsWith("]") ? raw.length() - 1 : raw.length());
        StringBuilder token = new StringBuilder();
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                token.append(c);
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                token.append(c);
            } else if (c == ',') {
                addToken(values, token);
            } else {
                token.append(c);
            }
        }
        addToken(values, token);
        return values;
    }

    private static void addToken(List<Object> values, StringBuilder token) {
        String text = token.toString().trim();
        token.setLength(0);
        if (!text.isEmpty()) {
            values.add(parseValue(text));
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char quote = value.charAt(0);
            if ((quote == '"' || quote == '\'') && value.charAt(value.length() - 1) == quote) {
                String inner = value.substring(1, value.length() - 1);
                return quote == '"' ? unescape(inner) : inner;
            }
        }
        return value;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static Long tryLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double tryDouble(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- lexing

    /** Removes a trailing comment, ignoring {@code #} inside strings. */
    private static String stripComment(String line) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indexOfEquals(String line) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBalanced(String value) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return depth <= 0;
    }

    private static List<String> splitKey(String key) {
        List<String> parts = new ArrayList<>();
        for (String part : key.split("\\.")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(unquote(trimmed));
            }
        }
        return parts;
    }
}
