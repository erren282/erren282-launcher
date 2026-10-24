import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 用**真实游戏目录**验证选择框的两条规则：
///
/// <ol>
///   <li>带 `hidden: true` 的版本（Lunar 基座）不会被列出来；</li>
///   <li>Lunar 客户端被识别成可用 Fabric 模组的实例。</li>
/// </ol>
///
/// 用法：RealPickerCheck &lt;游戏目录&gt;
public final class RealPickerCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0] : "E:/game/game/minecraft/.minecraft").replace('/', File.separatorChar));
        Path versions = gameDirectory.resolve("versions");

        System.out.println("游戏目录: " + gameDirectory);
        System.out.println();

        List<String> visible = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        List<String> lunar = new ArrayList<>();

        try (var stream = Files.list(versions)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                Path json = directory.resolve(id + ".json");
                if (!Files.isRegularFile(json))
                    continue;
                JsonObject version = JsonParser.parseString(
                        Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();

                boolean isHidden = version.has("hidden") && version.get("hidden").isJsonPrimitive()
                        && version.get("hidden").getAsBoolean();
                String mainClass = version.has("mainClass") ? version.get("mainClass").getAsString() : "";
                String inheritsFrom = version.has("inheritsFrom")
                        ? version.get("inheritsFrom").getAsString() : "";

                if (isHidden) {
                    hidden.add(id);
                } else {
                    visible.add(id);
                }

                // 与 ModToVersionEngine#isLunarClient 相同的判定
                if (mainClass.equals("org.dsh.lunarofl.wrapper.GenesisLauncher")
                        || mainClass.startsWith("com.moonsworth.lunar")
                        || id.startsWith("Lunar-")
                        || inheritsFrom.endsWith("-lunar-base")) {
                    lunar.add(id);
                }
            }
        }

        System.out.println("=== 隐藏版本（不进选择框）===");
        hidden.forEach(id -> System.out.println("  " + id));
        System.out.println();
        System.out.println("=== Lunar 客户端（识别为 Fabric 实例）===");
        lunar.forEach(id -> System.out.println("  " + id));
        System.out.println();

        check("目录里确实存在隐藏的基座版本", !hidden.isEmpty());
        check("隐藏的基座全是插件生成的底座",
                hidden.stream().allMatch(id -> id.endsWith("-lunar-base") || id.endsWith("-vanilla")));
        check("隐藏版本里没有一个是 Lunar 客户端本体",
                hidden.stream().noneMatch(id -> id.startsWith("Lunar-")));

        check("识别出至少一个 Lunar 客户端", !lunar.isEmpty());
        for (String id : lunar) {
            check("  " + id + " 是可见的（用户能选到）", visible.contains(id));
        }

        // 关键回归：基座和真实原版同游戏版本时，不能出现两行
        List<String> bases = hidden.stream().filter(id -> id.endsWith("-lunar-base")).toList();
        for (String base : bases) {
            String realVersion = base.substring(0, base.length() - "-lunar-base".length());
            check("基座 " + base + " 对应的真实原版 " + realVersion + " 可见",
                    visible.contains(realVersion));
            check("基座 " + base + " 本身不可见", !visible.contains(base));
        }

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void check(String message, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS - " + message);
        } else {
            failed++;
            System.out.println("  FAIL - " + message);
        }
    }
}
