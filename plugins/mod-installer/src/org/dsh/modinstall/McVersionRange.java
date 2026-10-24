package org.dsh.modinstall;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches a Minecraft version against the version specifiers mod metadata uses.
 *
 * <p>Three notations show up in practice and all three are handled here:</p>
 * <ul>
 *   <li><b>Fabric / Quilt</b>: {@code "1.20.1"}, {@code ">=1.20.1"},
 *       {@code ">=1.20.1 <1.21"}, {@code "~1.20.1"}, {@code "^1.20"}, {@code "*"},
 *       or an array whose entries must all hold;</li>
 *   <li><b>Forge / NeoForge</b> (Maven ranges): {@code "[1.20.1,1.21)"},
 *       {@code "[1.20.1,]"}, {@code "(,1.21]"}.</li>
 * </ul>
 *
 * <p>Comparisons are numeric per component, so {@code 1.20.10 > 1.20.9} — a plain
 * string compare would get that backwards and silently hide a compatible
 * version.</p>
 */
final class McVersionRange {

    private McVersionRange() {
    }

    /**
     * True when {@code mcVersion} satisfies every specifier in {@code specs}.
     *
     * <p>An empty list means "the metadata does not say", which is treated as
     * compatible so an unknown mod is still offered to the user rather than
     * being hidden.</p>
     */
    static boolean matches(List<String> specs, String mcVersion) {
        if (specs == null || specs.isEmpty()) {
            return true;
        }
        for (String spec : specs) {
            if (!matchesSpec(spec, mcVersion)) {
                return false;
            }
        }
        return true;
    }

    /** True when one specifier is satisfied; {@code *} and blank always are. */
    static boolean matchesSpec(String spec, String mcVersion) {
        if (spec == null) {
            return true;
        }
        String text = spec.trim();
        if (text.isEmpty() || "*".equals(text)) {
            return true;
        }
        // Maven interval notation.
        if (text.startsWith("[") || text.startsWith("(")) {
            return matchesMavenInterval(text, mcVersion);
        }
        // Space-separated conditions are a conjunction ("">=1.20 <1.21"").
        for (String condition : text.split("\\s+")) {
            if (!matchesCondition(condition.trim(), mcVersion)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesCondition(String condition, String mcVersion) {
        if (condition.isEmpty() || "*".equals(condition)) {
            return true;
        }
        String operator = "";
        String operand = condition;
        for (String candidate : new String[]{">=", "<=", ">", "<", "~", "^", "="}) {
            if (condition.startsWith(candidate)) {
                operator = candidate;
                operand = condition.substring(candidate.length()).trim();
                break;
            }
        }
        if (operand.isEmpty()) {
            return true;
        }
        int comparison = compare(mcVersion, operand);
        return switch (operator) {
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            // "~1.20.1": at least 1.20.1, below the next minor (1.21).
            case "~" -> comparison >= 0 && compare(mcVersion, bump(operand, 2)) < 0;
            // "^1.20": at least 1.20, below the next major (2.0).
            case "^" -> comparison >= 0 && compare(mcVersion, bump(operand, 1)) < 0;
            default -> comparison == 0;
        };
    }

    /** Maven ranges: {@code [a,b)} inclusive-lower / exclusive-upper, etc. */
    private static boolean matchesMavenInterval(String text, String mcVersion) {
        boolean lowerInclusive = text.startsWith("[");
        boolean upperInclusive = text.endsWith("]");
        String body = text.substring(1, text.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            // "[1.20.1]" — a single exact version.
            return compare(mcVersion, body.trim()) == 0;
        }
        String lower = body.substring(0, comma).trim();
        String upper = body.substring(comma + 1).trim();
        if (!lower.isEmpty()) {
            int comparison = compare(mcVersion, lower);
            if (lowerInclusive ? comparison < 0 : comparison <= 0) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int comparison = compare(mcVersion, upper);
            if (upperInclusive ? comparison > 0 : comparison >= 0) {
                return false;
            }
        }
        return true;
    }

    /** Raises the n-th component by one: {@code bump("1.20.1", 2)} = {@code "1.21"}. */
    private static String bump(String version, int component) {
        int[] parts = components(version);
        if (parts.length == 0) {
            return version;
        }
        int index = Math.min(component - 1, parts.length - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('.');
            }
            out.append(i == index ? parts[i] + 1 : parts[i]);
            if (i == index) {
                // Drop the parts below the bumped one.
                break;
            }
        }
        return out.toString();
    }

    /** Numeric comparison of dotted versions; missing components count as 0. */
    static int compare(String left, String right) {
        int[] a = components(left);
        int[] b = components(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** Splits a version into its numeric parts, ignoring any suffix. */
    private static int[] components(String version) {
        if (version == null) {
            return new int[0];
        }
        List<Integer> parts = new ArrayList<>();
        for (String piece : version.trim().split("\\.")) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            if (digits.length() == 0) {
                break;
            }
            try {
                parts.add(Integer.parseInt(digits.toString()));
            } catch (NumberFormatException e) {
                break;
            }
        }
        int[] result = new int[parts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = parts.get(i);
        }
        return result;
    }
}
