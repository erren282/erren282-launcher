import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.dsh.lunarofl.LunarVersionInstaller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 验证「启动失败：未能下载游戏依赖 org.dsh.lunar:...」这个毛病不会复现。
///
/// <p>HMCL 的 `GameLibrariesTask` 只要发现版本 json 声明的某个库在 `libraries/` 下没有对应文件，
/// 就会按 Maven 坐标去 `libraries.minecraft.net` 下载。`org.dsh.lunar` 是插件私有坐标，官方库没有，
/// 于是 `FileNotFoundException` 404，启动被拦下。所以版本 json 里声明的每个 `org.dsh.lunar:*`
/// 都必须在磁盘上有对应 jar；修复入口会把「声明了但装不上」的库清掉。</p>
///
/// 用法：LibraryDeclarationTest &lt;游戏目录&gt; [版本id]
public final class LibraryDeclarationTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0] : "E:/game/game/minecraft/.minecraft").replace('/', File.separatorChar));
        String onlyVersion = args.length > 1 && !args[1].isBlank() ? args[1] : null;

        System.out.println("游戏目录: " + gameDirectory);

        // ---------- 先修，再检查（插件启用时就是这么做的） ----------
        LunarVersionInstaller.repairInstalledVersions(gameDirectory);

        Path versionsDirectory = gameDirectory.resolve("versions");
        List<Path> versionJsons = new ArrayList<>();
        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                if (!id.startsWith("Lunar-"))
                    continue;
                if (onlyVersion != null && !id.equals(onlyVersion))
                    continue;
                Path json = directory.resolve(id + ".json");
                if (Files.isRegularFile(json))
                    versionJsons.add(json);
            }
        }

        check(!versionJsons.isEmpty(), "找到 Lunar 版本 json：" + versionJsons.size() + " 个");

        for (Path versionJson : versionJsons) {
            String versionId = versionJson.getParent().getFileName().toString();
            JsonObject version = JsonParser.parseString(
                    Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray libraries = version.has("libraries") && version.get("libraries").isJsonArray()
                    ? version.getAsJsonArray("libraries") : new JsonArray();

            List<String> missing = new ArrayList<>();
            int declared = 0;
            for (JsonElement element : libraries) {
                if (!element.isJsonObject())
                    continue;
                JsonElement nameElement = element.getAsJsonObject().get("name");
                if (nameElement == null || !nameElement.isJsonPrimitive())
                    continue;
                String name = nameElement.getAsString();
                if (!name.startsWith("org.dsh.lunar"))
                    continue;
                declared++;
                Path jar = libraryFile(gameDirectory.resolve("libraries"), name);
                if (jar == null || !Files.isRegularFile(jar))
                    missing.add(name);
            }

            System.out.println("  " + versionId + "：声明 " + declared + " 个 Lunar 库，缺失 " + missing.size() + " 个");
            for (String name : missing)
                System.out.println("     缺失: " + name);

            check(missing.isEmpty(),
                    versionId + " 声明的 Lunar 库在 libraries/ 下都存在（否则启动器会去官方库下载并 404）");
        }

        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 把一个 maven 坐标映射成 `libraries/` 下的 jar 路径。
    private static Path libraryFile(Path librariesDir, String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3)
            return null;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return librariesDir.resolve(group).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + classifier + ".jar");
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  PASS - " : "  FAIL - ") + message);
        if (!condition)
            failures++;
    }
}
