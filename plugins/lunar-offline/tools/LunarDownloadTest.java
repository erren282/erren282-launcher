import org.dsh.lunarofl.LunarApi;
import org.dsh.lunarofl.LunarClientDownloader;
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
import java.util.Map;

/// 端到端验证「不经过 Celestial 的完整下载与启动」：
/// 1) 用插件自己的 LunarApi 拉版本目录，确认 1.8.9 可选；
/// 2) 用 LunarClientDownloader 从 Lunar 官方源下载整份客户端（classpath / 外部文件 / native /
///    贴图 / UI / 许可证）到临时目录；
/// 3) 用 LunarVersionInstaller 生成 HMCL 版本，确认 HMCL 的版本仓库能发现它；
/// 4) 按生成的版本 json 组装启动命令，真实启动并等待 LUNARCLIENT_STATUS_STARTED。
public final class LunarDownloadTest {

    /// 直接调用受保护的 refreshVersionsImpl，绕开事件总线（测试环境里没有启动器的刷新监听器）。
    static final class ProbeRepository extends DefaultGameRepository {
        ProbeRepository(Path baseDirectory) {
            super(baseDirectory);
        }

        void forceRefresh() {
            refreshVersionsImpl();
        }
    }

    /// 某个坐标在已解析版本的库列表里的下标（子版本在前、父版本在后）。
    private static int indexOfLibrary(org.jackhuang.hmcl.game.Version version, String name) {
        var libraries = version.getLibraries();
        for (int index = 0; index < libraries.size(); index++)
            if (name.equals(libraries.get(index).getName()))
                return index;
        return -1;
    }

    /// 坐标前缀（例如 `it.unimi.dsi:fastutil:`）第一次出现的下标；没有就返回 -1。
    private static int indexOfLibraryPrefix(org.jackhuang.hmcl.game.Version version, String prefix) {
        var libraries = version.getLibraries();
        for (int index = 0; index < libraries.size(); index++)
            if (libraries.get(index).getName().startsWith(prefix))
                return index;
        return -1;
    }

    /// `org.dsh.lunar:fastutil-8.5.6:1.0` → `fastutil-8.5.6`；其它坐标原样返回。
    private static String artifactOf(String coordinate) {
        if (!coordinate.startsWith("org.dsh.lunar:"))
            return coordinate;
        String rest = coordinate.substring("org.dsh.lunar:".length());
        int colon = rest.lastIndexOf(':');
        return colon < 0 ? rest : rest.substring(0, colon);
    }

    /// 选模块：给了 id 就用它（找不到就列出可选模块并退出），否则用服务端标的默认模块。
    private static LunarApi.Module pickModule(LunarApi.SubVersion target, String requested) {
        if (requested == null || requested.isBlank())
            return target.defaultModule();
        for (LunarApi.Module module : target.modules())
            if (module.id().equals(requested))
                return module;
        System.out.println("FAIL - 模块 " + requested + " 不存在，可选："
                + target.modules().stream().map(LunarApi.Module::id).toList());
        System.exit(1);
        return target.defaultModule();
    }

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        String gameVersion = args.length > 0 ? args[0] : "1.8.9";
        // 传第二个参数可复用上次的目录，用来快速验证「缓存命中」这条路径
        Path root = args.length > 1
                ? Path.of(args[1].replace('/', java.io.File.separatorChar))
                : Files.createTempDirectory("lunar-download-test");
        Files.createDirectories(root);
        Path gameDirectory = root.resolve("game");
        Path clientDirectory = gameDirectory.resolve("lunar-client").resolve(gameVersion);
        Path dataDirectory = root.resolve("plugin-data");
        Files.createDirectories(dataDirectory);

        System.out.println("临时游戏目录: " + gameDirectory);
        System.out.println("客户端目录  : " + clientDirectory);

        // ---------- 1) 版本目录 ----------
        LunarApi api = new LunarApi(dataDirectory);
        System.out.println("installation id: " + api.installationId() + "，os=" + api.os() + "，arch=" + api.arch());
        List<LunarApi.SubVersion> versions = api.versions();
        check(!versions.isEmpty(), "拉取到 Lunar 版本目录：" + versions.size() + " 个版本");
        LunarApi.SubVersion target = null;
        for (LunarApi.SubVersion version : versions)
            if (version.id().equals(gameVersion))
                target = version;
        check(target != null, "目录里包含 " + gameVersion);
        if (target == null) {
            System.out.println("FAIL - 目标版本不存在，终止");
            System.exit(1);
        }
        LunarApi.Module module = pickModule(target, args.length > 2 ? args[2] : null);
        System.out.println("目标版本: " + target.id() + "（父项 " + target.parentId()
                + "）模块: " + module.id() + "（" + module.name() + "），共 "
                + target.modules().size() + " 个模块可选");

        // ---------- 2) 下载客户端 ----------
        long started = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicReference<String> lastStep =
                new java.util.concurrent.atomic.AtomicReference<>("");
        LunarClientDownloader.Installed installed = LunarClientDownloader.download(
                clientDirectory, api, gameVersion, module.id(),
                (step, done, total) -> {
                    if (!step.equals(lastStep.getAndSet(step)))
                        System.out.println("  [" + step + "] " + mb(done) + " / " + mb(total));
                },
                () -> false);
        long elapsed = System.currentTimeMillis() - started;
        System.out.println("下载完成：" + installed.files() + " 个文件，" + mb(installed.bytes())
                + "，耗时 " + (elapsed / 1000) + " 秒");

        LunarVersionInstaller.LaunchData data = installed.launchData();
        check(data.classpath().size() >= 6, "classpath 条目：" + data.classpath().size());
        check(data.ichorpath().size() >= 20, "外部文件（ichorpath）条目：" + data.ichorpath().size());
        check(countFiles(clientDirectory.resolve("natives")) > 10,
                "native 已解压：" + countFiles(clientDirectory.resolve("natives")) + " 个文件");
        check(countFiles(clientDirectory.resolve("textures")) > 100,
                "贴图已下载：" + countFiles(clientDirectory.resolve("textures")) + " 个文件");
        check(Files.isRegularFile(clientDirectory.resolve("textures").resolve("assets/lunar/cosmetics.json")),
                "贴图保留分层结构（textures/assets/lunar/cosmetics.json 存在）");
        check(Files.isRegularFile(clientDirectory.resolve("ui").resolve("index.html")),
                "UI 已解压（ui/index.html 存在）");
        check(countFiles(clientDirectory.resolve("licenses")) > 5,
                "许可证已下载：" + countFiles(clientDirectory.resolve("licenses")) + " 个文件");
        boolean allClasspathPresent = true;
        for (String entry : data.classpath())
            if (!Files.isRegularFile(Path.of(entry)))
                allClasspathPresent = false;
        check(allClasspathPresent, "classpath 里的文件都真实存在");

        // 再下一次应几乎全是缓存命中
        LunarClientDownloader.Installed cached = LunarClientDownloader.download(
                clientDirectory, api, gameVersion, module.id(), (step, done, total) -> { }, () -> false);
        check(cached.bytes() == 0, "重复下载命中缓存（本次写入 " + cached.bytes() + " 字节，跳过 "
                + cached.skipped() + " 个）");

        // ---------- 3) 安装成 HMCL 版本 ----------
        // 基础版本桩刻意用**老式 minecraftArguments**（真实 1.8.9/1.21.11 就是这样），
        // 因为启动器会把这段模板也拼进命令行 —— 我们自己的参数一旦重复（例如 --version
        // 出现两次），Genesis 的 joptsimple 就会抛 MultipleArgumentsForOptionException。
        Path baseVersionDir = gameDirectory.resolve("versions").resolve(gameVersion);
        Files.createDirectories(baseVersionDir);
        writeBaseVersion(baseVersionDir, gameVersion, "legacy");
        Files.write(baseVersionDir.resolve(gameVersion + ".jar"), new byte[]{'P', 'K', 5, 6, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        String versionId = "Lunar-" + gameVersion;
        LunarVersionInstaller.Result result = LunarVersionInstaller.install(gameDirectory, versionId, data,
                (step, bytes) -> { });
        System.out.println("安装结果: " + result.versionId() + "（库 " + result.libraries() + " 个，复制 "
                + mb(result.bytes()) + "）");

        ProbeRepository repository = new ProbeRepository(gameDirectory);
        Version version = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && version == null) {
            try {
                repository.forceRefresh();
                version = repository.getVersion(versionId);
            } catch (Exception notYet) {
                version = null;
                Thread.sleep(300);
            }
        }
        check(version != null, "启动器的版本仓库能发现 " + versionId);
        if (version == null) {
            System.out.println("FAIL - 仓库中没有该版本，终止");
            System.exit(1);
        }
        // 主类是插件的入口包装类（它把 --version 归一化后再调用 Genesis）
        check(LunarVersionInstaller.WRAPPER_MAIN_CLASS.equals(version.getMainClass()),
                "HMCL 解析出入口包装主类：" + version.getMainClass());
        // 继承的是「零库壳版本」而不是原版本身：壳版本自身不带 libraries。注意真实的原版库
        // （1.8.9 的 gson 2.2.4、1.12.2 的 fastutil 7.1.0 …）仍会经壳版本里的原版 patch 进入
        // classpath，所以插件必须把 Lunar 自己声明的那套库排在它们前面（见下面的顺序断言）。
        check(version.getInheritsFrom() != null
                        && version.getInheritsFrom().startsWith(gameVersion)
                        && version.getInheritsFrom().endsWith("-lunar-base"),
                "继承零库壳版本：" + version.getInheritsFrom());

        // 库顺序（版本无关的写法）：Lunar 自己的 jar → Lunar 运行库 → 其它（继承来的原版库）。
        // 两个方向反了都是 NoSuchMethodError：运行库在前会盖掉 Lunar jar 里自带的 shaded 新版类
        // （commons-io 的 BoundedInputStream 就是这样），在后会被原版旧库盖掉（fastutil 7.1.0 对 8.x）。
        var resolvedForOrder = repository.getResolvedVersion(versionId);
        List<String> ordered = resolvedForOrder.getLibraries().stream()
                .map(org.jackhuang.hmcl.game.Library::getName).toList();
        List<String> classpathArtifacts = data.classpath().stream()
                .map(path -> Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("\\.jar$", ""))
                .toList();
        List<String> runtimeArtifacts = data.libraries().stream()
                .map(path -> Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("\\.jar$", ""))
                .toList();

        int lastClasspath = -1;
        int firstRuntime = -1;
        int runtimeCount = 0;
        for (int index = 0; index < ordered.size(); index++) {
            String artifact = artifactOf(ordered.get(index));
            if (classpathArtifacts.contains(artifact))
                lastClasspath = index;
            else if (runtimeArtifacts.contains(artifact)) {
                if (firstRuntime < 0)
                    firstRuntime = index;
                runtimeCount++;
            }
        }
        check(runtimeArtifacts.size() >= 20, "Lunar 运行库已装进 libraries：" + runtimeArtifacts.size() + " 个");
        check(runtimeCount == runtimeArtifacts.size(),
                "运行库条目数与下载数一致（" + runtimeCount + "/" + runtimeArtifacts.size() + "）");
        check(lastClasspath >= 0 && firstRuntime == lastClasspath + 1,
                "运行库紧跟在 Lunar 自己的 jar 之后（最后一个 jar " + lastClasspath
                        + "，第一个运行库 " + firstRuntime + "）");
        int vanillaFastutil = indexOfLibraryPrefix(resolvedForOrder, "it.unimi.dsi:fastutil:");
        check(vanillaFastutil < 0 || firstRuntime < vanillaFastutil,
                "Lunar 运行库排在继承来的原版 fastutil 之前（" + firstRuntime + " < " + vanillaFastutil + "）");

        // ---------- 3b) Java 版本要求 ----------
        // Lunar 的参数里有 --add-modules/--add-opens，用 Java 8 启动会被 JVM 直接拒绝
        // （Unrecognized option: --add-modules），所以版本 json 必须声明 Java 17+，
        // 启动器才会去挑/下载合适的 Java，而不是默默用基础版本的 Java 8。
        check(version.getJavaVersion() != null, "版本声明了 javaVersion");
        if (version.getJavaVersion() != null) {
            check(version.getJavaVersion().majorVersion() == LunarVersionInstaller.REQUIRED_JAVA.majorVersion(),
                    "要求的 Java 主版本：" + version.getJavaVersion().majorVersion()
                            + "（component=" + version.getJavaVersion().component() + "）");
        }
        var javaRange = org.jackhuang.hmcl.game.JavaVersionConstraint.GAME_JSON
                .getJavaVersionRange(version, null);
        check(javaRange != null && !javaRange.contains(
                        org.jackhuang.hmcl.util.versioning.VersionNumber.asVersion("1.8.0_504")),
                "启动器的强制约束会拒绝 Java 8");
        check(javaRange != null && javaRange.contains(
                        org.jackhuang.hmcl.util.versioning.VersionNumber.asVersion("21.0.6")),
                "启动器的强制约束接受 Java 21");

        // 启动器真正用来挑 Java 的函数：给一个 Java 8 和一个 Java 21，它必须选 21；
        // 只有 Java 8 时必须返回 null（于是启动器会提示下载合适的 Java，而不是用 Java 8 硬起）。
        var gameVersionNumber = org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion(gameVersion);
        var java8 = org.jackhuang.hmcl.java.JavaRuntime.of(
                Path.of("java8", "bin", "java.exe"),
                org.jackhuang.hmcl.java.JavaInfo.newBuilder(
                        org.jackhuang.hmcl.util.platform.Platform.CURRENT_PLATFORM, "1.8.0_504").build(),
                false);
        var java21 = org.jackhuang.hmcl.java.JavaRuntime.of(
                Path.of("java21", "bin", "java.exe"),
                org.jackhuang.hmcl.java.JavaInfo.newBuilder(
                        org.jackhuang.hmcl.util.platform.Platform.CURRENT_PLATFORM, "21.0.6").build(),
                false);
        // 启动器实际传给这个函数的是「已解析」（inheritsFrom 已展开）的版本，这里照做
        var resolvedVersion = repository.getResolvedVersion(versionId);
        var chosen = org.jackhuang.hmcl.java.JavaManager.findSuitableJava(
                List.of(java8, java21), gameVersionNumber, resolvedVersion);
        check(chosen != null && chosen.getParsedVersion() >= 17,
                "启动器在 Java 8 与 Java 21 之间选择了 Java "
                        + (chosen == null ? "无" : chosen.getParsedVersion()));
        var onlyJava8 = org.jackhuang.hmcl.java.JavaManager.findSuitableJava(
                List.of(java8), gameVersionNumber, resolvedVersion);
        check(onlyJava8 == null, "只装了 Java 8 时启动器不会选它（会改为提示/下载 Java 17+）");

        // 老版本 json（既没有 javaVersion，也没有 -cp ${classpath}）能被一次性补齐
        Path legacy = gameDirectory.resolve("versions").resolve("Lunar-legacy").resolve("Lunar-legacy.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"id\":\"Lunar-legacy\",\"inheritsFrom\":\"" + gameVersion
                + "\",\"mainClass\":\"com.moonsworth.lunar.genesis.Genesis\",\"arguments\":{"
                + "\"jvm\":[\"-Djava.library.path=natives\",\"--add-modules\",\"jdk.naming.dns\"],\"game\":[]}}");
        check(LunarVersionInstaller.ensureLaunchRequirements(legacy),
                "旧的版本 json 被补上 Java 要求与 classpath");
        check(!LunarVersionInstaller.ensureLaunchRequirements(legacy), "已修好的版本 json 不会被重复改写");
        String repaired = Files.readString(legacy);
        check(repaired.contains("\"majorVersion\""), "补充后的 json 里含有 javaVersion.majorVersion");
        check(repaired.contains("${classpath}"), "补充后的 json 里含有 -cp ${classpath}");

        // 老方案（参数与基础版本重复）的版本 json 能被「按新方案重建」，且不用重新下载
        Path oldSchemeDir = gameDirectory.resolve("versions").resolve("Lunar-" + gameVersion + "-old");
        Files.createDirectories(oldSchemeDir);
        Path oldSchemeJson = oldSchemeDir.resolve("Lunar-" + gameVersion + "-old.json");
        Files.writeString(oldSchemeJson, "{\"id\":\"Lunar-" + gameVersion + "-old\",\"inheritsFrom\":\""
                + gameVersion + "\",\"mainClass\":\"" + data.mainClass() + "\",\"libraries\":[],\"arguments\":{"
                + "\"jvm\":[\"-Djava.library.path=x\",\"--add-modules\",\"jdk.naming.dns\"],"
                + "\"game\":[\"--ichorClassPath\"," + jsonString(String.join(",", data.classpath())) + ","
                + "\"--ichorExternalFiles\"," + jsonString(String.join(",", data.ichorpath())) + ","
                + "\"--classpathDir\"," + jsonString(data.installation().toString()) + ","
                + "\"--version\",\"" + gameVersion + "\",\"--width\",\"854\"]}}");
        LunarVersionInstaller.Result rebuilt = LunarVersionInstaller.regenerate(oldSchemeJson);
        check(rebuilt.libraries() == data.libraries().size() + data.classpath().size() + 1,
                "重建后的版本含 " + rebuilt.libraries() + " 个库（" + data.libraries().size() + " 个 Lunar 运行库 + "
                        + data.classpath().size() + " 个客户端库 + 入口包装库）");
        var rebuiltJson = com.google.gson.JsonParser.parseString(Files.readString(oldSchemeJson, StandardCharsets.UTF_8))
                .getAsJsonObject();
        long versionFlags = rebuiltJson.getAsJsonObject("arguments").getAsJsonArray("game").asList().stream()
                .filter(element -> "--version".equals(element.getAsString())).count();
        check(versionFlags == 0, "重建后的版本 json 里不再出现 --version（交给入口包装类）");
        check(!rebuiltJson.has("minecraftArguments"),
                "重建后不再覆盖 minecraftArguments（账号参数交给基础版本提供）");
        check(rebuiltJson.getAsJsonObject("arguments").getAsJsonArray("jvm").asList().stream()
                        .anyMatch(element -> "${classpath}".equals(element.getAsString())),
                "重建后带有 -cp ${classpath}");

        // ---------- 4) 真实启动：两种基础版本写法各跑一次 ----------
        // 注意：这里**不**手工加 -cp，也不手工拼参数——完全用启动器自己的解析器
        // （Arguments.parseArguments + 占位符 + has_custom_resolution 特性），
        // 这样“参数重复”“忘写 -cp”这类回归才会被测试抓到。
        String java = System.getenv("JAVA_HOME") != null
                ? Path.of(System.getenv("JAVA_HOME"), "bin", "java.exe").toString()
                : Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var json = com.google.gson.JsonParser.parseString(
                Files.readString(result.versionJson(), StandardCharsets.UTF_8)).getAsJsonObject();
        var arguments = json.getAsJsonObject("arguments");

        check(LunarVersionInstaller.WRAPPER_MAIN_CLASS.equals(json.get("mainClass").getAsString()),
                "主类是入口包装类：" + json.get("mainClass").getAsString());
        check(arguments.getAsJsonArray("jvm").asList().stream().anyMatch(element ->
                        ("-D" + LunarVersionInstaller.GAME_VERSION_PROPERTY + "=" + gameVersion)
                                .equals(element.getAsString())),
                "JVM 属性里带上了游戏版本（给入口包装类用）");
        check(arguments.getAsJsonArray("game").asList().stream()
                        .noneMatch(element -> "--version".equals(element.getAsString())),
                "版本 json 自己不写 --version（交给入口包装类归一化）");
        check(arguments.getAsJsonArray("jvm").asList().stream()
                        .anyMatch(element -> "${classpath}".equals(element.getAsString())),
                "版本 json 自己声明了 -cp ${classpath}");

        Map<String, String> configuration = new java.util.HashMap<>();
        configuration.put("${auth_player_name}", "Player");
        configuration.put("${auth_uuid}", "00000000000040008000000000000001");
        configuration.put("${auth_access_token}", "0");
        configuration.put("${auth_session}", "0");
        configuration.put("${user_type}", "msa");
        configuration.put("${user_properties}", "{}");
        configuration.put("${version_name}", versionId);
        configuration.put("${game_directory}", gameDirectory.toString());
        configuration.put("${assets_root}", gameDirectory.resolve("assets").toString());
        configuration.put("${assets_index_name}", "1.8");
        configuration.put("${resolution_width}", "854");
        configuration.put("${resolution_height}", "480");
        configuration.put("${library_directory}", gameDirectory.resolve("libraries").toString());
        configuration.put("${classpath_separator}", System.getProperty("path.separator"));
        Map<String, Boolean> features = Map.of("has_custom_resolution", true);

        for (String style : new String[]{"legacy", "modern"}) {
            writeBaseVersion(baseVersionDir, gameVersion, style);
            repository.forceRefresh();
            Version resolved = repository.getResolvedVersion(versionId);

            // 与 DefaultLauncher 一致：${classpath} = 版本的库 + 该版本的主 jar
            var launcherClasspath = repository.getClasspath(resolved);
            launcherClasspath.add(repository.getVersionJar(resolved).toAbsolutePath().toString());
            String classpathValue = String.join(System.getProperty("path.separator"), launcherClasspath);
            if ("legacy".equals(style))
                check(classpathValue.contains("genesis-0.1.0-snapshot-all"),
                        "启动器算出的 classpath 含 Genesis jar");
            check(classpathValue.contains("lunar-wrapper"),
                    "[" + style + "] 启动器算出的 classpath 含入口包装库");

            List<String> command = new ArrayList<>();
            command.add(java);
            for (var element : arguments.getAsJsonArray("jvm"))
                command.add(element.getAsString().replace("${classpath}", classpathValue));
            command.add(json.get("mainClass").getAsString());

            // 启动器的顺序：主类 → 基础版本旧式 minecraftArguments → 合并后的 arguments.game
            //              → 启动器默认游戏参数（分辨率）
            if (resolved.getMinecraftArguments().isPresent()) {
                command.addAll(org.jackhuang.hmcl.game.Arguments.parseStringArguments(
                        org.jackhuang.hmcl.util.StringUtils.tokenize(
                                resolved.getMinecraftArguments().orElse("")), configuration));
            }
            resolved.getArguments().ifPresent(value -> command.addAll(
                    org.jackhuang.hmcl.game.Arguments.parseArguments(value.game(), configuration, features)));
            if (resolved.getMinecraftArguments().isPresent()) {
                command.addAll(org.jackhuang.hmcl.game.Arguments.parseArguments(
                        org.jackhuang.hmcl.game.Arguments.DEFAULT_GAME_ARGUMENTS, configuration, features));
            }

            check(command.contains(classpathValue), "[" + style + "] 最终命令行里带上了 classpath");
            for (String option : new String[]{"--version", "--classpathDir", "--ichorClassPath",
                    "--ichorExternalFiles", "--installationId", "--workingDirectory"}) {
                long count = command.stream().filter(option::equals).count();
                check(count == 1, "[" + style + "] 命令行里 " + option + " 恰好出现一次（实际 " + count + " 次）");
            }
            // 基础版本给的是实例名（Genesis 认为非法），必须靠入口包装类换成真正的游戏版本
            int versionIndex = command.indexOf("--version");
            System.out.println("[" + style + "] 基础版本给的 --version = "
                    + command.get(versionIndex + 1) + "（入口包装类会换成 " + gameVersion + "）");

            launchAndWait(command, data.installation(), style);
        }

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.out.println("（临时目录 " + root + " 保留，可人工检查）");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 写基础版本桩：`legacy` 用老式 minecraftArguments，`modern` 用新式 arguments.game。
    ///
    /// <p>刻意用 HMCL 自己的「快照 + patches」格式（真实原版 json 就是这样）：库挂在 `patches[0]`
    /// 上，插件生成的零库壳版本会把它原样带过去 —— 这正是原版库（1.12.2 的 fastutil 7.1.0 等）
    /// 仍然会进 classpath 的原因，也是必须校验库顺序的原因。</p>
    private static void writeBaseVersion(Path versionDir, String gameVersion, String style) throws Exception {
        String body;
        if ("legacy".equals(style)) {
            body = ",\"minecraftArguments\":\"--username ${auth_player_name} --version ${version_name} "
                    + "--gameDir ${game_directory} --assetsDir ${assets_root} "
                    + "--assetIndex ${assets_index_name} --uuid ${auth_uuid} "
                    + "--accessToken ${auth_access_token} --userProperties ${user_properties} "
                    + "--userType ${user_type}\"";
        } else {
            body = ",\"arguments\":{\"game\":[\"--username\",\"${auth_player_name}\","
                    + "\"--version\",\"${version_name}\",\"--gameDir\",\"${game_directory}\","
                    + "\"--assetsDir\",\"${assets_root}\",\"--assetIndex\",\"${assets_index_name}\","
                    + "\"--uuid\",\"${auth_uuid}\",\"--accessToken\",\"${auth_access_token}\","
                    + "\"--userType\",\"${user_type}\",\"--userProperties\",\"${user_properties}\"]}";
        }
        body = body.replace("${access_token_variable}", "auth_access_token");
        String patch = "\"id\":\"game\",\"version\":\"" + gameVersion + "\",\"priority\":0,"
                + "\"mainClass\":\"net.minecraft.client.main.Main\",\"assetIndex\":{\"id\":\"1.8\"},"
                + BASE_LIBRARIES;
        Files.writeString(versionDir.resolve(gameVersion + ".json"),
                "{\"id\":\"" + gameVersion + "\",\"mainClass\":\"net.minecraft.client.main.Main\","
                        + "\"assetIndex\":{\"id\":\"1.8\"},\"libraries\":[],"
                        + "\"patches\":[{" + patch + body + "}]}", StandardCharsets.UTF_8);
    }

    /// 基础版本桩里挂的原版库：故意留一条真实原版 1.12.2 的 `fastutil 7.1.0`，
    /// 用来验证 Lunar 的 fastutil 8.5.6 排在它前面。
    private static final String BASE_LIBRARIES =
            "\"libraries\":[{\"name\":\"it.unimi.dsi:fastutil:7.1.0\"}]";

    /// 启动并等待 LUNARCLIENT_STATUS_STARTED。
    private static void launchAndWait(List<String> command, Path workingDirectory, String label) throws Exception {
        System.out.println("[" + label + "] 启动命令参数个数: " + command.size() + "，开始启动（最多观察 180 秒）…");
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).start();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<String>());
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

        // 首次启动要烘焙上万个类（1.21.11 实测 38 秒），窗口给足，别把慢当成崩
        long deadline = System.currentTimeMillis() + 180_000;
        boolean started = false;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            Thread.sleep(500);
            if (lines.stream().anyMatch(line -> line.contains("LUNARCLIENT_STATUS_STARTED"))) {
                started = true;
                break;
            }
        }
        System.out.println("--- [" + label + "] 客户端输出（末 12 行）---");
        List<String> snapshot = new ArrayList<>(lines);
        for (String line : snapshot.subList(Math.max(0, snapshot.size() - 12), snapshot.size()))
            System.out.println(line.substring(0, Math.min(160, line.length())));
        check(started, "[" + label + "] Lunar 客户端启动完成（LUNARCLIENT_STATUS_STARTED）");
        check(process.isAlive(), "[" + label + "] 客户端进程持续运行");

        process.destroy();
        Thread.sleep(3000);
        if (process.isAlive())
            process.destroyForcibly();
    }

    private static String substitute(String value, LunarVersionInstaller.LaunchData data, Path gameDirectory) {
        return switch (value) {
            case "${auth_access_token}" -> "0";
            case "${auth_player_name}" -> "Player";
            case "${auth_uuid}" -> "00000000000040008000000000000001";
            case "${auth_session}" -> "0";
            case "${user_type}" -> "msa";
            case "${user_properties}" -> "{}";
            case "${version_name}" -> "Lunar-" + data.gameVersion();
            case "${game_directory}" -> gameDirectory.toString();
            case "${assets_root}" -> gameDirectory.resolve("assets").toString();
            case "${assets_index_name}" -> "1.8";
            case "${resolution_width}" -> "854";
            case "${resolution_height}" -> "480";
            default -> value;
        };
    }

    private static long countFiles(Path directory) {
        if (!Files.isDirectory(directory))
            return 0;
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}
