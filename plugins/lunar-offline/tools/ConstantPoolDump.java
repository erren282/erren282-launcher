import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 把 class 文件常量池里的字符串**按原样**写到指定文件（UTF-8），彻底避开控制台编码问题。
///
/// <p>为什么需要它：`javac` 会把 `"a" + b + "c"` 编译成 `invokedynamic` + 一份「配方」字符串
/// （`makeConcatWithConstants`），配方里的 `\u0001` 是插入点。所以直接在类文件里搜
/// 「下载完成！」可能搜不到被拆开的片段，而 PowerShell 的控制台重定向又会把中文变成乱码。</p>
///
/// 用法：ConstantPoolDump &lt;class 文件&gt; &lt;输出文件&gt; [要查找的字符串...]
public final class ConstantPoolDump {

    public static void main(String[] args) throws Exception {
        Path classFile = Path.of(args[0]);
        Path outputFile = Path.of(args[1]);
        List<String> utf8 = readConstantPoolStrings(classFile);

        List<String> lines = new ArrayList<>();
        lines.add("常量池字符串数: " + utf8.size());
        lines.add("");

        if (args.length > 2) {
            boolean allFound = true;
            for (int i = 2; i < args.length; i++) {
                String needle = args[i];
                List<String> matches = utf8.stream().filter(s -> s.contains(needle)).toList();
                lines.add((matches.isEmpty() ? "缺失  " : "找到  ") + "\"" + needle + "\"（" + matches.size() + " 处）");
                for (String match : matches.subList(0, Math.min(3, matches.size())))
                    lines.add("        → \"" + escape(match) + "\"");
                if (matches.isEmpty())
                    allFound = false;
            }
            if (!allFound) {
                lines.add("");
                lines.add("--- 含「完成」/「安装」/「Lunar」的常量 ---");
                utf8.stream()
                        .filter(s -> s.contains("完成") || s.contains("安装") || s.contains("Lunar"))
                        .forEach(s -> lines.add("  \"" + escape(s) + "\""));
            }
            Files.write(outputFile, lines, StandardCharsets.UTF_8);
            System.exit(allFound ? 0 : 1);
        } else {
            utf8.stream().filter(s -> !s.isBlank())
                    .forEach(s -> lines.add("  \"" + escape(s) + "\""));
            Files.write(outputFile, lines, StandardCharsets.UTF_8);
        }

        // 顺便把结果打到控制台（可能乱码，但文件里一定是好的）
        try (PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8)) {
            out.println("已写入: " + outputFile);
        }
    }

    private static String escape(String s) {
        StringBuilder builder = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20)
                builder.append(String.format("\\u%04x", (int) c));
            else
                builder.append(c);
        }
        return builder.toString();
    }

    private static List<String> readConstantPoolStrings(Path classFile) throws IOException {
        try (InputStream raw = Files.newInputStream(classFile);
             DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != 0xCAFEBABE)
                throw new IOException("不是 class 文件: " + classFile);
            in.readUnsignedShort();
            in.readUnsignedShort();

            int count = in.readUnsignedShort();
            String[] pool = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = decodeModifiedUtf8(in);
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++;
                    }
                    default -> throw new IOException("未知常量池 tag: " + tag);
                }
            }
            List<String> result = new ArrayList<>();
            for (String s : pool)
                if (s != null)
                    result.add(s);
            return result;
        }
    }

    private static String decodeModifiedUtf8(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
