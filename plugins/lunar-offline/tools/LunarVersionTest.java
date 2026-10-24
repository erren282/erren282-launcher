import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.dsh.lunarofl.LunarVersionInstaller;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.Version;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 端到端验证「安装为 HMCL 版本」：
/// 1) 读取 Celestial 的启动数据；
/// 2) 安装到临时游戏目录，生成 versions/Lunar-1.8.9/Lunar-1.8.9.json 与 libraries；
/// 3) 用 HMCL 自己的版本模型解析这份 json，确认 HMCL 认得它；
/// 4) 按该版本 json 组装出与 HMCL 相同的启动命令并真实启动 Lunar，确认能进到客户端。
public final class LunarVersionTest {

    /// 直接调用受保护的 refreshVersionsImpl，绕开事件总线（测试环境里没有启动器的刷新监听器）。
    static final class ProbeRepository extends DefaultGameRepository {
        ProbeRepository(Path baseDirectory) {
            super(baseDirectory);
        }

        void forceRefresh() {
            refreshVersionsImpl();
        }

        Version parse(Path json) throws Exception {
            return readVersionJson(json);
        }
    }

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Files.createTempDirectory("lunar-game-dir");
        String versionId = "Lunar-1.8.9";

        LunarVersionInstaller.LaunchData data =
                LunarVersionInstaller.read(LunarVersionInstaller.celestialHome().resolve("launch-data.json"));
        System.out.println("Celestial 客户端目录: " + data.installation());
        System.out.println("主类: " + data.mainClass() + "  基础版本: " + data.gameVersion()
                + "  classpath: " + data.classpath().size() + "  ichor: " + data.ichorpath().size());

        // 先放一个基础版本桩（真实环境中用户机器上已安装对应的 1.8.9）
        Path baseDir = gameDirectory.resolve("versions").resolve(data.gameVersion());
        Files.createDirectories(baseDir);
        Files.writeString(baseDir.resolve(data.gameVersion() + ".json"),
                "{\"id\":\"" + data.gameVersion() + "\",\"mainClass\":\"net.minecraft.client.main.Main\",\"libraries\":[]}");
        Files.write(baseDir.resolve(data.gameVersion() + ".jar"), new byte[]{'P', 'K', 5, 6, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        LunarVersionInstaller.Result result = LunarVersionInstaller.install(gameDirectory, versionId, data,
                (step, bytes) -> { });
        System.out.println("安装结果: " + result.versionId() + " -> " + result.versionJson()
                + "（库 " + result.libraries() + " 个，复制 " + (result.bytes() / 1024 / 1024) + " MB）");
        check(Files.isRegularFile(result.versionJson()), "版本 json 已生成");

        // 2) 再次安装应命中缓存（不重复复制 81MB）
        LunarVersionInstaller.Result again = LunarVersionInstaller.install(gameDirectory, versionId, data,
                (step, bytes) -> { });
        check(again.bytes() == 0, "重复安装不重复复制文件（本次复制 " + again.bytes() + " 字节）");

        // 3) HMCL 自己解析这个版本
        ProbeRepository repository = new ProbeRepository(gameDirectory);
        // 启动器的版本列表是异步加载的，这里轮询等待它扫描到我们刚写入的版本
        Version version = null;
        long loadDeadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < loadDeadline && version == null) {
            try {
                repository.forceRefresh();
                version = repository.getVersion(versionId);
            } catch (Exception notYet) {
                version = null;
                Thread.sleep(300);
            }
        }
        if (version == null) {
            try {
                repository.parse(result.versionJson());
                System.out.println("（解析本身成功，说明是发现流程的其他环节）");
            } catch (Throwable t) {
                System.out.println("解析版本 json 失败：" + t);
                t.printStackTrace(System.out);
            }
        }
        check(version != null, "启动器的版本仓库能发现该版本");
        if (version == null) {
            System.out.println("FAIL - 仓库中没有该版本，终止");
            System.exit(1);
        }
        // The generated version's main class is the entry wrapper, not Genesis itself: the
        // wrapper strips the duplicate --version the launcher appends and re-adds the right game
        // version before reflection-invoking Genesis. Asserting Genesis here was stale.
        check("org.dsh.lunarofl.wrapper.GenesisLauncher".equals(version.getMainClass()),
                "HMCL 解析出入口包装主类：" + version.getMainClass());
        // libraries = the Lunar runtime libraries + the client jars + the wrapper
        check(version.getLibraries().size() == data.libraries().size() + data.classpath().size() + 1,
                "库数量 = Lunar 运行库 + 客户端 jar + 包装库：" + version.getLibraries().size()
                        + "（运行库 " + data.libraries().size() + "，classpath " + data.classpath().size() + "）");
        // inheritsFrom points at the zero-library shell, so no vanilla loader can leak onto the
        // classpath and clash with Lunar's own patched classes
        check(version.getInheritsFrom() != null && version.getInheritsFrom().endsWith("-lunar-base"),
                "继承零库壳版本：" + version.getInheritsFrom());
        for (org.jackhuang.hmcl.game.Library library : version.getLibraries()) {
            Path file = repository.getLibraryFile(version, library);
            if (!Files.isRegularFile(file)) {
                check(false, "库文件缺失：" + file);
                break;
            }
        }
        System.out.println("HMCL 解析出的库路径示例: "
                + repository.getLibraryFile(version, version.getLibraries().get(0)));

        // 4) 按该 json 组装命令并真实启动（模拟 HMCL 的启动参数）
        JsonObject json = JsonParser.parseString(Files.readString(result.versionJson(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> command = new ArrayList<>();
        command.add(data.installation().resolve("../../../../ZULU21/bin/java.exe").normalize().toString());
        if (!Files.isRegularFile(Path.of(command.get(0))))
            command.set(0, "E:\\ZULU21\\bin\\java.exe");
        JsonObject arguments = json.getAsJsonObject("arguments");
        for (var element : arguments.getAsJsonArray("jvm"))
            command.add(element.getAsString());
        StringBuilder classpath = new StringBuilder();
        for (org.jackhuang.hmcl.game.Library library : version.getLibraries()) {
            if (classpath.length() > 0)
                classpath.append(';');
            classpath.append(repository.getLibraryFile(version, library));
        }
        command.add("-cp");
        command.add(classpath.toString());
        command.add(json.get("mainClass").getAsString());
        for (var element : arguments.getAsJsonArray("game"))
            command.add(substitute(element.getAsString(), data));

        System.out.println("启动命令参数个数: " + command.size() + "，开始启动（最多观察 40 秒）…");
        Process process = new ProcessBuilder(command).directory(data.installation().toFile())
                .redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null)
                    lines.add(line);
            } catch (Exception ignored) {
                // 进程结束时流关闭
            }
        });
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + 40_000;
        boolean reachedStarted = false;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            Thread.sleep(500);
            if (lines.stream().anyMatch(l -> l.contains("LUNARCLIENT_STATUS_STARTED"))) {
                reachedStarted = true;
                break;
            }
        }
        System.out.println("--- 客户端输出（末 15 行）---");
        for (String line : lines.subList(Math.max(0, lines.size() - 15), lines.size()))
            System.out.println(line.substring(0, Math.min(150, line.length())));
        check(reachedStarted, "Lunar 客户端启动完成（LUNARCLIENT_STATUS_STARTED）");
        check(process.isAlive(), "客户端进程持续运行");

        process.destroy();
        Thread.sleep(3000);
        if (process.isAlive())
            process.destroyForcibly();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 把 HMCL 启动器占位符替换成测试用的值。
    private static String substitute(String value, LunarVersionInstaller.LaunchData data) {
        return switch (value) {
            case "${auth_access_token}" -> "0";
            case "${auth_player_name}" -> "Player";
            case "${auth_uuid}" -> "00000000000040008000000000000001";
            case "${game_directory}" -> data.installation().resolveSibling(".minecraft").toString();
            case "${resolution_width}" -> "854";
            case "${resolution_height}" -> "480";
            default -> value;
        };
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}
