import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.dsh.lunarofl.LunarVersionInstaller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 验证「一键下载原版 + Lunar」不会在启动器里产生第二个可见原版。
///
/// <p>场景：游戏目录里已经有同名的 **Fabric/Forge 修改版**（例如用户的 `1.21.11` 是 Fabric 版）。
/// 插件必须为 Lunar 准备一份原版底座，但那份底座（`&lt;id&gt;-vanilla` 与 `&lt;id&gt;-lunar-base`）
/// 不该以「第二个原版」的样子出现在版本列表里。</p>
///
/// 用法：DupVersionTest &lt;游戏目录&gt; &lt;原版id&gt;
public final class DupVersionTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0] : "E:/game/gamea/mcl/HMCL-test/tmp-dupcheck/game").replace('/', File.separatorChar));
        String gameVersion = args.length > 1 ? args[1] : "1.21.11";
        // 第三个参数：一份真实的 Lunar 版本 json（作为 install 的数据源；不传则只验证迁移逻辑）
        Path sourceLunarJson = args.length > 2 && !args[2].isBlank()
                ? Path.of(args[2].replace('/', File.separatorChar)) : null;

        System.out.println("游戏目录: " + gameDirectory);
        System.out.println("原版版本: " + gameVersion);

        // ---------- 前置：同名版本是修改版 ----------
        Path baseJson = gameDirectory.resolve("versions").resolve(gameVersion).resolve(gameVersion + ".json");
        check(Files.isRegularFile(baseJson), "同名版本 " + gameVersion + " 存在");
        String mainClass = readMainClass(baseJson);
        System.out.println("同名版本的 mainClass: " + mainClass);
        boolean modded = mainClass != null && !mainClass.equals("net.minecraft.client.main.Main");
        check(modded, "同名版本是修改版（Fabric/Forge 等），插件必须另备原版底座");

        // ---------- 让插件补齐底座并迁移 ----------
        // 从一个**真实的** Lunar 版本 json 触发 `regenerate`：它会走完整的 install 流程
        // （ensureVanillaBaseVersion → ensureShellBaseVersion → 写版本 json），
        // 于是「同名是修改版」的场景就会生成 `<id>-vanilla` 与 `<id>-lunar-base` 两个底座。
        if (sourceLunarJson != null) {
            Path targetLunarJson = gameDirectory.resolve("versions").resolve("Lunar-" + gameVersion)
                    .resolve("Lunar-" + gameVersion + ".json");
            Files.createDirectories(targetLunarJson.getParent());
            Files.copy(sourceLunarJson, targetLunarJson, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LunarVersionInstaller.Result result = LunarVersionInstaller.regenerate(targetLunarJson);
            System.out.println("安装结果: " + result.versionId() + "（继承 " + result.baseVersion() + "）");
        } else {
            System.out.println("（未提供真实 Lunar 版本 json，跳过 install，只验证迁移）");
        }

        LunarVersionInstaller.hideInstalledVanillaCopies(gameDirectory);
        LunarVersionInstaller.upgradeInstalledShellVersions(gameDirectory);
        LunarVersionInstaller.hideInstalledVanillaCopies(gameDirectory);

        // ---------- 断言：所有插件生成的底座都被隐藏 ----------
        List<String> visibleVanilla = new ArrayList<>();
        List<String> hiddenInternals = new ArrayList<>();
        Path versionsDirectory = gameDirectory.resolve("versions");
        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                Path json = directory.resolve(id + ".json");
                if (!Files.isRegularFile(json))
                    continue;
                boolean hidden = isHidden(json);
                System.out.println("  " + id + "  hidden=" + hidden);
                if (hidden)
                    hiddenInternals.add(id);
                // 「看起来像原版」的可见版本 = 用户会看到的原版数量
                if (!hidden && looksLikeVanilla(json))
                    visibleVanilla.add(id);
            }
        }

        System.out.println("可见的、看起来像原版的版本: " + visibleVanilla);
        System.out.println("已隐藏的内部底座: " + hiddenInternals);

        // 用户自己的那份 `1.21.11` 是 Fabric 版（不是原版），所以它不算「可见原版」；
        // 关键是插件生成的底座（*-vanilla / *-lunar-base）一个都不能可见。
        for (String id : hiddenInternals)
            check(id.endsWith("-vanilla") || id.endsWith("-lunar-base"),
                    "隐藏的都是插件内部产物：" + id);

        // 每个 `*-lunar-base` / `*-vanilla` 目录都必须隐藏（这是本测试的核心）
        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                if (!id.endsWith("-lunar-base") && !id.endsWith("-vanilla"))
                    continue;
                Path json = directory.resolve(id + ".json");
                if (!Files.isRegularFile(json))
                    continue;
                check(isHidden(json), "插件生成的底座 " + id + " 被隐藏（不显示为第二个原版）");
            }
        }

        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 版本 json 是否声明 hidden。
    private static boolean isHidden(Path json) {
        try {
            JsonObject version = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var hidden = version.get("hidden");
            return hidden != null && hidden.isJsonPrimitive() && hidden.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /// 这份 json 在版本列表里是否会被当成一个「可启动的原版」。
    private static boolean looksLikeVanilla(Path json) {
        String mainClass = readMainClass(json);
        return mainClass != null && mainClass.equals("net.minecraft.client.main.Main");
    }

    private static String readMainClass(Path json) {
        try {
            JsonObject version = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var mainClass = version.get("mainClass");
            return mainClass != null && mainClass.isJsonPrimitive() ? mainClass.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  PASS - " : "  FAIL - ") + message);
        if (!condition)
            failures++;
    }
}
