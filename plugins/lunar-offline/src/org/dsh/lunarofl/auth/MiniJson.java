package org.dsh.lunarofl.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// 极简 JSON 解析 / 序列化，只依赖 JDK。
///
/// <p>为什么自己写：包装类（GenesisLauncher）跑在游戏进程里，classpath 上虽然可能有 gson，
/// 但那是 Lunar 的内部 jar，版本与位置都不受我们控制；插件的 classpath 又没有 gson。
/// accounts.json 的结构很简单（对象 / 数组 / 字符串 / 数字 / 布尔 / null），一份两百行的
/// 解析器就够，还能保证读写出的格式与官方启动器一致（2 空格缩进）。</p>
///
/// <p>映射关系：object → {@link LinkedHashMap}&lt;String,Object&gt;（保持键序），
/// array → {@link ArrayList}&lt;Object&gt;，string → {@link String}，
/// number → {@link Double}（能表示为整数时写回不带小数点），true/false → {@link Boolean}，null → null。</p>
public final class MiniJson {

    private MiniJson() {
    }

    /// 解析一段 JSON 文本；结构不符合预期时抛 {@link IllegalArgumentException}。
    ///
    /// <p>会先去掉 UTF-8 BOM：Windows 工具（PowerShell 的 `Set-Content -Encoding UTF8`、
    /// 记事本另存等）会在文件开头写 `U+FEFF`，而 JSON 规范不允许，直接解析会报
    /// "位置 0 不是合法字符"——accounts.json 被这类工具改过之后就会整个读不出来。</p>
    public static Object parse(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF')
            text = text.substring(1);
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd())
            throw new IllegalArgumentException("JSON 末尾有多余内容，位置 " + parser.pos);
        return value;
    }

    /// 反序列化为对象（无嵌套约束）。
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map))
            throw new IllegalArgumentException("JSON 顶层不是对象");
        return (Map<String, Object>) value;
    }

    /// 序列化，2 空格缩进（与官方启动器写 accounts.json 的格式一致）。
    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value, 0);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value, int indent) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            writeString(builder, string);
        } else if (value instanceof Double) {
            // 整数值的 double（例如 Unix 时间戳）写回时不能带小数点或科学计数法
            builder.append(doubleToString((Double) value));
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            builder.append(value);
        } else if (value instanceof Float) {
            builder.append(doubleToString(((Float) value).doubleValue()));
        } else if (value instanceof Number number) {
            builder.append(doubleToString(number.doubleValue()));
        } else if (value instanceof Map<?, ?> map) {
            writeObject(builder, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(builder, list, indent);
        } else {
            throw new IllegalArgumentException("无法序列化的类型：" + value.getClass());
        }
    }

    private static String doubleToString(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 9.007199254740992E15)
            return Long.toString((long) value);
        return Double.toString(value);
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            builder.append("{}");
            return;
        }
        builder.append("{\n");
        String pad = pad(indent + 2);
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first)
                builder.append(",\n");
            first = false;
            builder.append(pad);
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(": ");
            writeValue(builder, entry.getValue(), indent + 2);
        }
        builder.append('\n').append(pad(indent)).append('}');
    }

    private static void writeArray(StringBuilder builder, List<?> list, int indent) {
        if (list.isEmpty()) {
            builder.append("[]");
            return;
        }
        builder.append("[\n");
        String pad = pad(indent + 2);
        boolean first = true;
        for (Object element : list) {
            if (!first)
                builder.append(",\n");
            first = false;
            builder.append(pad);
            writeValue(builder, element, indent + 2);
        }
        builder.append('\n').append(pad(indent)).append(']');
    }

    private static String pad(int level) {
        return " ".repeat(Math.max(0, level));
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
                    pos++;
                else
                    break;
            }
        }

        char peek() {
            if (atEnd())
                throw new IllegalArgumentException("JSON 意外结束");
            return text.charAt(pos);
        }

        void expect(char c) {
            if (atEnd() || text.charAt(pos) != c)
                throw new IllegalArgumentException("JSON 解析错误：位置 " + pos + " 期望 '" + c + "'");
            pos++;
        }

        Object readValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        void expectWord(String word) {
            if (!text.startsWith(word, pos))
                throw new IllegalArgumentException("JSON 解析错误：位置 " + pos + " 期望 " + word);
            pos += word.length();
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                result.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return result;
                }
                throw new IllegalArgumentException("JSON 解析错误：位置 " + pos + " 期望 ',' 或 '}'");
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return result;
                }
                throw new IllegalArgumentException("JSON 解析错误：位置 " + pos + " 期望 ',' 或 ']'");
            }
        }

        String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (true) {
                if (atEnd())
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                char c = text.charAt(pos++);
                if (c == '"')
                    return result.toString();
                if (c == '\\') {
                    if (atEnd())
                        throw new IllegalArgumentException("JSON 转义序列未闭合");
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length())
                                throw new IllegalArgumentException("JSON \\u 转义不完整");
                            result.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("JSON 未知转义 \\" + escaped);
                    }
                } else {
                    result.append(c);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            if (!atEnd() && (peek() == '-' || peek() == '+'))
                pos++;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+')
                    pos++;
                else
                    break;
            }
            String literal = text.substring(start, pos);
            if (literal.isEmpty())
                throw new IllegalArgumentException("JSON 解析错误：位置 " + start + " 不是合法数字");
            try {
                return Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSON 数字无法解析：" + literal);
            }
        }
    }
}
