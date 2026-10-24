package org.dsh.lunarofl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/// 把一份 Lunar 客户端**变成 HMCL 自己的一个游戏版本**。
///
/// <p>Lunar（genesis）启动时用的是一条固定的命令：主类 `com.moonsworth.lunar.genesis.Genesis`，
/// 加上一份 classpath、一组 JVM 参数，以及 `--ichorClassPath/--ichorExternalFiles/--webosrDir/
/// --classpathDir/--workingDirectory/--texturesDir/--uiDir` 等 Lunar 专有参数。</p>
///
/// <p>[LaunchData] 有两个来源：
/// <ol>
///   <li>[LunarClientDownloader]——插件自己从 Lunar 官方接口下载客户端后直接组装（推荐，
///       不需要 Celestial）；</li>
///   <li>Celestial 缓存的 `%APPDATA%\celestial\launch-data.json`——给已经用 Celestial 下过
///       客户端的用户复用（[#read]）。</li>
/// </ol>
/// 本类负责把 classpath 里的 jar 安装进 HMCL 的 `libraries/`，并生成
/// `versions/&lt;id&gt;/&lt;id&gt;.json`——账号、内存、Java、资源索引等交给 HMCL 的启动流程，
/// 用户就能直接在版本列表里启动，无需任何第三方启动器。</p>
public final class LunarVersionInstaller {

    /// Celestial 缓存里的启动数据。
    ///
    /// @param installation 客户端安装目录
    /// @param mainClass Lunar 入口主类
    /// @param gameVersion 游戏版本（例如 `1.12.2`）
    /// @param vmArgs 清单给的 JVM 参数
    /// @param classpath 会被放进 `--ichorClassPath` 的 Lunar jar（绝对路径）
    /// @param ichorpath 会被放进 `--ichorExternalFiles` 的**文件名**清单
    /// @param libraries Lunar 自己声明的运行库（绝对路径，会装进 HMCL 的 `libraries/`）
    public record LaunchData(Path installation, String mainClass, String gameVersion, List<String> vmArgs,
                      List<String> classpath, List<String> ichorpath, List<String> libraries) {

        /// 没有额外运行库时的等价写法（Celestial 缓存里读不到运行库清单时用）。
        public LaunchData(Path installation, String mainClass, String gameVersion, List<String> vmArgs,
                          List<String> classpath, List<String> ichorpath) {
            this(installation, mainClass, gameVersion, vmArgs, classpath, ichorpath, List.of());
        }
    }

    /// 安装结果。
    public record Result(String versionId, Path versionJson, int libraries, long bytes, String baseVersion) {
    }

    private LunarVersionInstaller() {
    }

    /// Lunar 需要 **Java 17 及以上**。
    ///
    /// <p>清单里给官方启动器准备的 JRE 就是 Zulu 17，而启动参数里有
    /// `--add-modules/--add-exports/--add-opens`——用 Java 8 启动会被 JVM 直接拒绝
    /// （`Unrecognized option: --add-modules` → 进程秒退）。把要求写进版本 json 的
    /// `javaVersion` 之后，启动器会自己挑一个合适的 Java，或提示用户下载，而不是默默用 Java 8 起。</p>
    ///
    /// <p>1.21.9+ 的 Lunar 通过 ichor 自动装配 Fabric API，而新版 Fabric API 在其 mod 元数据里
    /// 声明 `depends java >= 21`——用 Java 17 启动会在 mod 解析阶段被拒绝：
    /// <c>Mod 'Fabric API' requires version 21 or later of 'OpenJDK 64-Bit Server VM'</c>。
    /// 要求 21 起步：原版 1.21.9+ 的 `javaVersion` 本来就是 21，机器上没有时启动器会引导下载，
    /// 有（例如 JDK 22）则直接选用。</p>
    public static final GameJavaVersion REQUIRED_JAVA = GameJavaVersion.JAVA_21;

    /// 版本的主类不是 Genesis，而是我们的小包装类：它会把命令行里所有 `--version` 丢掉，
    /// 再补上安装时写进 `-Ddsh.lunar.gameVersion` 的游戏版本，然后反射调用 Genesis。
    ///
    /// <p>必须这么做，因为启动器会把基础版本的参数也拼进命令行：老式 `minecraftArguments` 或
    /// 新式 `arguments.game` 里都有 `--version ${version_name}`（值是启动器里的实例名，例如
    /// `Lunar-1.21.11`）。Genesis 用 joptsimple 解析，`--version` 出现两次会抛
    /// `MultipleArgumentsForOptionException`；值不是合法游戏版本又会抛
    /// `IllegalArgumentException: Invalid Minecraft version`。我们在 json 里无法删掉
    /// 基础版本那一条，所以改由入口类统一处理。</p>
    public static final String WRAPPER_MAIN_CLASS = "org.dsh.lunarofl.wrapper.GenesisLauncher";

    /// 原版客户端的主类——判断基础版本是否被 Fabric/Forge 接管的依据。
    private static final String VANILLA_MAIN_CLASS = "net.minecraft.client.main.Main";

    /// 会把基础版本变成修改版的库（id 片段），命中任意一个就不是原版。
    private static final java.util.regex.Pattern LOADER_LIBRARY_PATTERN = java.util.regex.Pattern.compile(
            "net\\.fabricmc:fabric-loader|net\\.fabricmc:sponge-mixin|net\\.forge|fmlcore|"
                    + "net\\.neoforged|org\\.spongepowered:mixin|optifine:OptiFine");

    /// Mojang 官方版本清单。
    private static final String VANILLA_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /// 包装类的 JVM 属性名（`-Ddsh.lunar.gameVersion=1.21.11`）。
    public static final String GAME_VERSION_PROPERTY = "dsh.lunar.gameVersion";

    private static final String WRAPPER_RESOURCE = "/wrapper/lunar-wrapper.jar";
    private static final String WRAPPER_COORDINATE = "org.dsh.lunar-wrapper:lunar-wrapper:1.0";
    static final String WRAPPER_LIBRARY_PATH = "org/dsh/lunar-wrapper/lunar-wrapper/1.0/lunar-wrapper-1.0.jar";

    /// Celestial 数据目录（Windows：%APPDATA%\celestial）。
    public static Path celestialHome() {
        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".config")
                : Path.of(appData);
        return base.resolve("celestial");
    }

    /// 读取 `launch-data.json`。
    public static LaunchData read(Path launchDataFile) throws IOException {
        if (!Files.isRegularFile(launchDataFile))
            throw new IOException("没有找到 Celestial 的启动数据：" + launchDataFile
                    + "\n请先用「启动客户端」运行一次 Celestial（它会下载并缓存 Lunar 客户端），然后再安装。");
        JsonObject object = JsonParser.parseString(Files.readString(launchDataFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        Path installation = Path.of(required(object, "installation"));
        String mainClass = required(object, "mainClass");
        String gameVersion = required(object, "gameVersion");
        List<String> vmArgs = strings(object, "vmArgs");
        List<String> classpath = strings(object, "classpath");
        List<String> ichorpath = strings(object, "ichorpath");
        if (classpath.isEmpty())
            throw new IOException("启动数据里没有 classpath，Celestial 可能尚未下载完成。");
        return new LaunchData(installation, mainClass, gameVersion, vmArgs, classpath, ichorpath,
                LunarLibraries.installed(installation));
    }

    /// 安装/更新为 HMCL 版本。
    ///
    /// @param gameDirectory HMCL 的游戏目录（版本写到它的 versions/ 与 libraries/ 下）
    /// @param versionId     版本名称，例如 `Lunar-1.8.9`
    /// @param progress      进度回调：(说明, 已完成字节数)
    public static Result install(Path gameDirectory, String versionId, LaunchData data,
                          BiConsumer<String, Long> progress) throws IOException {
        if (!Files.isDirectory(data.installation()))
            throw new IOException("Lunar 客户端目录不存在：" + data.installation()
                    + "\n请先运行一次 Celestial 重新下载。");

        Path librariesDir = gameDirectory.resolve("libraries");
        Path versionDir = gameDirectory.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        // [regenerate] 传进来的 `gameVersion` 是 json 里的 `inheritsFrom`，可能已经是壳版本或
        // `-vanilla` 副本的 id（例如 `1.8.9-vanilla-lunar-base`）。这里以目标版本 json 里现有的
        // `inheritsFrom` 为准并剥掉这些后缀，否则会套娃生成 `...-lunar-base-lunar-base`，还会把
        // 壳 id 写进 `-Ddsh.lunar.gameVersion`，让 Genesis 认为拿到的不是合法游戏版本。
        String previousBase = readInheritsFrom(versionDir.resolve(versionId + ".json"));
        if (previousBase == null)
            previousBase = data.gameVersion();
        String gameVersion = normalizeBaseGameVersion(previousBase);
        if (gameVersion.isBlank())
            gameVersion = normalizeBaseGameVersion(data.gameVersion());
        if (!gameVersion.equals(data.gameVersion()))
            data = new LaunchData(data.installation(), data.mainClass(), gameVersion,
                    data.vmArgs(), data.classpath(), data.ichorpath(), data.libraries());

        // 0) 基础版本必须是中国原版（vanilla）——不能是 Fabric/Forge 等修改版。
        //
        // Lunar 的 ichor 会把 fabric-loader 打上补丁（FabricLoaderImpl 去掉 final）后装进自己的
        // 模块 jar；而 Fabric 版基础版本又把官方未打补丁的 fabric-loader 放上游戏 classpath。
        // 同一个类名、两份字节码：Lunar 的混淆类继承的是补丁版（非 final），JVM 却先解析到了
        // classpath 上的官方版（final），直接抛
        // IncompatibleClassChangeError: ... cannot inherit from final class FabricLoaderImpl。
        // 所以基础版本必须用原版 json，inheritsFrom 指向它。
        String baseVersion = ensureVanillaBaseVersion(gameDirectory, gameVersion, progress);

        // 0b) 生成一个“零库”的壳版本供继承 —— 关键的一步，老版本（1.8.9）不这么做就起不来。
        //
        // Lunar 的游戏类由 ichor 在运行时从 --ichorClassPath 装配（Celestial 官方的启动命令
        // 里 -cp 只有 7 个 Lunar jar，没有任何原版库）。而 HMCL 的版本继承会把基础版本的
        // libraries 合并进 classpath：1.8.9 原版带的 gson 2.2.4 / guava 17 / authlib 1.5.21
        // 等老库会抢先于 Lunar 内置的新版被加载，Client 线程直接抛 NoSuchMethodError
        // （1.21.11 没炸只是因为它的原版库比较新，恰好没撞上）。
        // 因此继承链指向一个不含任何 library 的壳版本：它只声明主 jar、资产索引与 JVM 要求，
        // 游戏本体由 Lunar 的 ichor 提供。
        //
        // 重装/重建时沿用原来的壳版本 id，免得旧壳变成没人引用的垃圾版本。
        baseVersion = ensureShellBaseVersion(gameDirectory, baseVersion, gameVersion, progress);

        // 1) classpath jar → HMCL 的 libraries/org/dsh/lunar/<artifact>/1.0/<artifact>-1.0.jar
        List<String> libraryNames = new ArrayList<>();
        long copied = 0;

        int classpathCount = 0;
        for (String jarPath : data.classpath()) {
            Path source = Path.of(jarPath);
            if (!Files.isRegularFile(source)) {
                Logger.LOG.warning("Lunar classpath entry missing: " + source);
                continue;
            }
            String artifact = sanitize(source.getFileName().toString());
            String coordinate = "org.dsh.lunar:" + artifact + ":1.0";
            Path target = classpathTarget(librariesDir, source);
            progress.accept("正在安装 " + source.getFileName(), copied);
            copied += copyIfNeeded(source, target);
            // 同上：复制没落地就不要声明，否则启动器会去官方库下载这个不存在的坐标
            if (!Files.isRegularFile(target)) {
                Logger.LOG.warning("Lunar classpath 未能装进 libraries，跳过声明：" + artifact);
                continue;
            }
            libraryNames.add(coordinate);
            classpathCount++;
        }
        if (classpathCount == 0)
            throw new IOException("classpath 中的文件都不存在，Lunar 客户端可能已被删除。");

        // 1a) Lunar 自己声明的运行库排在 **Lunar 自己的 jar 之后、继承来的原版库之前**。
        //     这个顺序是官方启动器的顺序，两个方向都不能反：
        //       · 排在 Lunar jar 之前 → Lunar jar 里自带的（shaded）新版类会被旧库盖掉，
        //         例如 forge-*.jar 自带 org.apache.commons.io.input.BoundedInputStream，
        //         被 commons-io 2.5 盖掉就报 NoSuchMethodError: BoundedInputStream.getCount()；
        //       · 排在原版库之后 → 原版那套旧库先生效，报 NoSuchMethodError: IntArraySet.<init>(IntSet)。
        //     HMCL 合并继承链时子版本在前、且不去重，所以顺序就是这里列出的顺序。
        for (String jarPath : data.libraries()) {
            Path source = Path.of(jarPath);
            if (!Files.isRegularFile(source)) {
                Logger.LOG.warning("Lunar 运行库缺失：" + source);
                continue;
            }
            String artifact = sanitize(source.getFileName().toString());
            progress.accept("正在安装 " + source.getFileName(), copied);
            Path target = classpathTarget(librariesDir, source);
            copied += copyIfNeeded(source, target);
            // 只声明**确实复制成功**的库：HMCL 的 GameLibrariesTask 发现库文件不在
            // libraries/ 下就会按 Maven 坐标去 libraries.minecraft.net 下载，而 org.dsh.lunar
            // 是插件私有坐标，官方库没有 → FileNotFoundException 404，启动直接被拦下。
            if (!Files.isRegularFile(target)) {
                Logger.LOG.warning("Lunar 运行库未能装进 libraries，跳过声明：" + artifact);
                continue;
            }
            libraryNames.add("org.dsh.lunar:" + artifact + ":1.0");
        }

        // 1b) 入口包装类：作为普通库装进 libraries/，游戏进程就能从 classpath 上找到它
        long wrapperBytes = installWrapper(librariesDir, progress);
        // the wrapper is always usable (it either was current or was just written), but only the
        // bytes actually written count towards the reported total
        boolean wrapper = true;
        libraryNames.add(WRAPPER_COORDINATE);
        copied += wrapperBytes;

        // 2) 生成版本 json（inheritsFrom 指向刚确保存在的原版基础版本）
        Path versionJson = versionDir.resolve(versionId + ".json");
        writeJsonAtomically(versionJson, buildVersionJson(versionId, data, libraryNames, wrapper, baseVersion,
                requiredJavaVersion(gameDirectory, baseVersion)));
        installIcon(versionDir, data.installation(), LunarOfflinePlugin.dataDirectory());

        // 2b) 这次安装改指了别的壳版本（旧格式的套娃壳 / 换了名字）→ 旧壳没人用就删掉，
        //     连同它自带的那份原版 jar 副本，避免留在版本列表里占地方。
        if (previousBase != null && previousBase.endsWith("-lunar-base")
                && !previousBase.equals(baseVersion)
                && !isShellReferenced(gameDirectory, previousBase, versionId))
            removeShellVersion(gameDirectory, previousBase);

        progress.accept("已写入版本 " + versionId, copied);
        return new Result(versionId, versionJson, libraryNames.size(), copied, data.gameVersion());
    }

    /// 给版本目录写入 Lunar 官方图标（`icon.png`），否则版本列表里显示默认的草方块。
    ///
    /// <p>图标直接从客户端自己的 `lunar.jar` 里取（`assets/lunar/logo/`），不联网；取不到就
    /// 不写，安装照常完成——图标只是装饰。</p>
    private static void installIcon(Path versionDir, Path clientDirectory, Path cacheDirectory) {
        try {
            if (LunarIcon.install(versionDir.resolve("icon.png"), clientDirectory, cacheDirectory))
                Logger.LOG.info("已写入 Lunar 官方版本图标");
        } catch (Throwable t) {
            Logger.LOG.warning("Lunar 图标写入失败：" + t);
        }
    }

    /// 确保 `versions/&lt;gameVersion&gt;` 存在且是**原版** json，返回可用作 inheritsFrom 的版本 id。
    ///
    /// <p>三种情况：</p>
    /// <ol>
    ///   <li>基础版本不存在 → 从 Mojang 官方清单下载原版 json（不下载 jar 与库，启动器启动
    ///       Lunar 版本时会按继承链补齐缺失部分）；</li>
    ///   <li>已存在且是原版（mainClass 是原版入口） → 原样返回；</li>
    ///   <li>已存在但是 Fabric/Forge 等修改版 → 不动用户的版本（那可能是用户自己装的），
    ///       改为写一份 `&lt;id&gt;-vanilla` 副本供 Lunar 继承。</li>
    /// </ol>
    private static String ensureVanillaBaseVersion(Path gameDirectory, String gameVersion,
                                                   BiConsumer<String, Long> progress) throws IOException {
        Path baseJson = gameDirectory.resolve("versions").resolve(gameVersion).resolve(gameVersion + ".json");
        if (Files.isRegularFile(baseJson)) {
            if (isVanillaJson(baseJson))
                return gameVersion;
            // 用户已有的同名版本是修改版：为 Lunar 准备一份独立的原版副本
            String vanillaId = gameVersion + "-vanilla";
            Path vanillaJson = gameDirectory.resolve("versions").resolve(vanillaId).resolve(vanillaId + ".json");
            if (!Files.isRegularFile(vanillaJson) || !isVanillaJson(vanillaJson))
                downloadVanillaJson(vanillaId, gameVersion, vanillaJson, progress);
            return vanillaId;
        }
        downloadVanillaJson(gameVersion, gameVersion, baseJson, progress);
        return gameVersion;
    }

    /// 判断版本 json 是否为原版：mainClass 是原版入口（或继承原版但没有 mod-loader 库）。
    private static boolean isVanillaJson(Path json) {
        try {
            JsonObject version = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String mainClass = version.has("mainClass") && version.get("mainClass").isJsonPrimitive()
                    ? version.get("mainClass").getAsString() : "";
            // 原版客户端的主类；Fabric(Knot)、Forge(fml)、OptiFine 等都会换成别的
            if (mainClass.equals(VANILLA_MAIN_CLASS))
                return true;
            // 继承链上的伪装：mainClass 换了但没有任何 loader 库，也视作原版变体
            boolean hasLoader = false;
            if (version.has("libraries") && version.get("libraries").isJsonArray()) {
                for (JsonElement element : version.getAsJsonArray("libraries")) {
                    if (!element.isJsonObject())
                        continue;
                    JsonElement name = element.getAsJsonObject().get("name");
                    if (name != null && name.isJsonPrimitive()
                            && LOADER_LIBRARY_PATTERN.matcher(name.getAsString()).find()) {
                        hasLoader = true;
                        break;
                    }
                }
            }
            return !hasLoader && mainClass.startsWith("net.minecraft.client.main");
        } catch (Exception e) {
            return false; // 解析不了就当作非原版处理，走保守路径
        }
    }

    /// 把 `inheritsFrom` 之类的 id 还原成真正的 Minecraft 版本号：剥掉壳版本与 `-vanilla` 副本的后缀。
    private static String normalizeBaseGameVersion(String id) {
        String result = id == null ? "" : id;
        while (result.endsWith("-lunar-base"))
            result = result.substring(0, result.length() - "-lunar-base".length());
        if (result.endsWith("-vanilla"))
            result = result.substring(0, result.length() - "-vanilla".length());
        return result;
    }

    /// 基于原版 json 生成“零库”壳版本并返回它的版本 id。
    ///
    /// <p>壳版本 `&lt;游戏版本&gt;-lunar-base` 与原版 json 内容相同，但删掉了整个 `libraries` 数组——
    /// 这样 HMCL 合并继承链时不会把任何原版库带上 classpath，Lunar 的游戏类完全由 ichor 提供
    /// （与官方 Celestial 的启动命令一致：`-cp` 只有 Lunar 自己的 jar）。</p>
    ///
    /// <p>壳版本用 `jar` 字段**引用**原版版本目录里的 jar（Forge 系版本就是这么写的），而不是自带
    /// 一份原版客户端 jar：HMCL 的 `getVersionJar` 于是解析到 `versions/&lt;原版id&gt;/&lt;原版id&gt;.jar`，
    /// 磁盘上只留一份。旧格式（自带 jar 副本）的壳版本会就地升级，并把那份副本删掉。</p>
    ///
    /// <p>壳版本名固定取**游戏版本**，而不是 `-vanilla` 副本的 id，这样同一个游戏版本永远只有一个
    /// 壳，不会出现 `1.8.9-vanilla-lunar-base` 这种套娃名字。</p>
    private static String ensureShellBaseVersion(Path gameDirectory, String vanillaId, String gameVersion,
                                                 BiConsumer<String, Long> progress) throws IOException {
        String shellBase = normalizeBaseGameVersion(gameVersion);
        String shellId = (shellBase.isBlank() ? vanillaId : shellBase) + "-lunar-base";
        Path shellJson = gameDirectory.resolve("versions").resolve(shellId).resolve(shellId + ".json");
        Path vanillaJson = gameDirectory.resolve("versions").resolve(vanillaId).resolve(vanillaId + ".json");
        if (!Files.isRegularFile(vanillaJson))
            return vanillaId; // 没有源就退回原链（调用方已保证一般不会走到这）

        // 原版 json 更新过（比如换了下载源）就重建壳版本；旧格式（没有 jar 引用 / patch 里还带库）
        // 也要重建 —— 后者会让 Windows 命令行超长（CreateProcess error=206）
        if (Files.isRegularFile(shellJson)
                && Files.getLastModifiedTime(shellJson).toMillis() >= Files.getLastModifiedTime(vanillaJson).toMillis()
                && isCleanShell(shellJson, vanillaId))
            return shellId;

        progress.accept("正在准备 Lunar 专用基础版本", 0L);
        writeShellVersion(gameDirectory, shellId, vanillaId);
        progress.accept("已准备 Lunar 专用基础版本 " + shellId, 0L);
        return shellId;
    }

    /// 写出壳版本 json：复制原版 json、去掉 `libraries`/`logging`、把 `jar` 指向原版版本，
    /// 并删掉旧格式壳版本自带的那份原版 jar 副本。
    private static void writeShellVersion(Path gameDirectory, String shellId, String sourceVanillaId) throws IOException {
        Path source = gameDirectory.resolve("versions").resolve(sourceVanillaId).resolve(sourceVanillaId + ".json");
        JsonObject vanilla = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject shell = vanilla.deepCopy();
        shell.addProperty("id", shellId);
        shell.remove("libraries");
        // logging 配置引用的库也在 libraries 里，一并去掉（Lunar 用自己的日志配置）
        shell.remove("logging");
        // 关键：HMCL 格式的原版 json 把库挂在 `patches` 里（`patches[0].libraries`），只删顶层
        // `libraries` 是不够的 —— 那些原版库仍然会进 classpath，既被 Lunar 自己声明的那套盖住
        // （纯浪费），又会让 Windows 命令行超长：路径一多就 `CreateProcess error=206,
        // 文件名或扩展名太长`（Lunar-26.3 实测 176 条 / 19699 字符）。Lunar 的 ichor 自带游戏类，
        // 需要的库由 versions.conf 声明（插件会装好），所以这里把 patch 里的库也清掉。
        if (shell.has("patches") && shell.get("patches").isJsonArray())
            for (JsonElement patch : shell.getAsJsonArray("patches"))
                if (patch.isJsonObject())
                    patch.getAsJsonObject().remove("libraries");
        // 关键：不再自带一份原版客户端 jar，而是引用原版版本目录里的那一份
        shell.addProperty("jar", sourceVanillaId);
        // 关键：壳版本是 Lunar 的继承底座，不是给用户启动的「游戏」——否则它带着原版 mainClass 与
        // 资产索引，会在启动器版本列表里显示成第二个原版。`hidden` 只影响列表过滤
        // （`Version.isHidden()` / `HMCLGameRepository.getDisplayVersions()`），继承解析照常可用。
        shell.addProperty("hidden", true);

        Path shellDir = gameDirectory.resolve("versions").resolve(shellId);
        Files.createDirectories(shellDir);
        writeJsonAtomically(shellDir.resolve(shellId + ".json"), shell.toString());
        deleteStaleShellJar(shellDir, shellId);
    }

    /// 壳版本是否已经是我们想要的形态：引用原版 jar、**任何地方都不带 libraries**、且已隐藏。
    private static boolean isCleanShell(Path shellJson, String vanillaId) {
        try {
            JsonObject shell = JsonParser.parseString(Files.readString(shellJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement jar = shell.get("jar");
            boolean references = jar != null && jar.isJsonPrimitive() && vanillaId.equals(jar.getAsString());
            JsonElement hidden = shell.get("hidden");
            boolean isHidden = hidden != null && hidden.isJsonPrimitive() && hidden.getAsBoolean();
            return references && isHidden && libraryCount(shell) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /// 数一个版本 json 里声明了多少库（顶层 + 所有 patch）。
    private static int libraryCount(JsonObject version) {
        int count = 0;
        if (version.has("libraries") && version.get("libraries").isJsonArray())
            count += version.getAsJsonArray("libraries").size();
        if (version.has("patches") && version.get("patches").isJsonArray())
            for (JsonElement patch : version.getAsJsonArray("patches"))
                if (patch.isJsonObject()
                        && patch.getAsJsonObject().has("libraries")
                        && patch.getAsJsonObject().get("libraries").isJsonArray())
                    count += patch.getAsJsonObject().getAsJsonArray("libraries").size();
        return count;
    }

    /// 旧格式的壳版本会在自己的目录里放一份原版客户端 jar；改成引用后那份副本纯粹是浪费，删掉。
    private static void deleteStaleShellJar(Path shellDir, String shellId) {
        Path stale = shellDir.resolve(shellId + ".jar");
        try {
            if (Files.isRegularFile(stale)) {
                Files.delete(stale);
                Logger.LOG.info("已删除壳版本自带的原版 jar 副本：" + stale);
            }
        } catch (IOException e) {
            Logger.LOG.warning("删除壳版本 jar 副本失败：" + e);
        }
    }

    /// 就地把已存在的旧格式壳版本升级为“引用原版 jar”的形式（重装或启动时顺带完成，不必重装）。
    private static void upgradeShellVersion(Path gameDirectory, String shellId) {
        try {
            Path shellJson = gameDirectory.resolve("versions").resolve(shellId).resolve(shellId + ".json");
            if (!Files.isRegularFile(shellJson))
                return;

            String sourceVanillaId = shellId.substring(0, shellId.length() - "-lunar-base".length());
            Path sourceJson = gameDirectory.resolve("versions").resolve(sourceVanillaId)
                    .resolve(sourceVanillaId + ".json");
            if (!Files.isRegularFile(sourceJson)) {
                // 老壳可能是拿 `<版本>-vanilla` 副本生成的，而那份副本后来被删了：退回真实游戏版本
                String gameVersion = sourceVanillaId.endsWith("-vanilla")
                        ? sourceVanillaId.substring(0, sourceVanillaId.length() - "-vanilla".length())
                        : sourceVanillaId;
                Path gameJson = gameDirectory.resolve("versions").resolve(gameVersion)
                        .resolve(gameVersion + ".json");
                if (!Files.isRegularFile(gameJson))
                    return;
                sourceVanillaId = gameVersion;
            }

            if (isCleanShell(shellJson, sourceVanillaId))
                return;

            writeShellVersion(gameDirectory, shellId, sourceVanillaId);
            Logger.LOG.info("已把壳版本 " + shellId + " 升级为引用原版 " + sourceVanillaId + " 的 jar（清掉原版库）");
        } catch (Exception e) {
            Logger.LOG.warning("升级壳版本失败：" + e);
        }
    }

    /// 读取版本 json 的 `inheritsFrom`（不存在或读不出来时返回 null）。
    private static @Nullable String readInheritsFrom(Path versionJson) {
        try {
            if (!Files.isRegularFile(versionJson))
                return null;
            JsonObject version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement inheritsFrom = version.get("inheritsFrom");
            return inheritsFrom != null && inheritsFrom.isJsonPrimitive() ? inheritsFrom.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /// 是否还有版本继承自这个壳版本（`exceptVersionId` 用于忽略正在重写的那个版本）。
    private static boolean isShellReferenced(Path gameDirectory, String shellId, @Nullable String exceptVersionId) {
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory))
            return false;

        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                Path fileName = directory.getFileName();
                if (fileName == null)
                    continue;
                String versionId = fileName.toString();
                if (versionId.equals(exceptVersionId) || versionId.equals(shellId))
                    continue;
                if (shellId.equals(readInheritsFrom(directory.resolve(versionId + ".json"))))
                    return true;
            }
        } catch (IOException e) {
            Logger.LOG.warning("检查壳版本引用失败：" + e);
            return true; // 查不清就别删
        }
        return false;
    }

    /// 删除一个我们自己生成的壳版本目录（带后缀与路径校验，避免误删用户版本）。
    private static void removeShellVersion(Path gameDirectory, String shellId) {
        if (!shellId.endsWith("-lunar-base"))
            return;

        Path versionsDirectory = gameDirectory.resolve("versions").toAbsolutePath().normalize();
        Path directory = versionsDirectory.resolve(shellId).normalize();
        if (!directory.getParent().equals(versionsDirectory) || !Files.isDirectory(directory))
            return;

        // 只删「零库、没有 inheritsFrom」的版本，确保它确实是我们的壳版本
        JsonObject shell = null;
        try {
            Path json = directory.resolve(shellId + ".json");
            if (Files.isRegularFile(json))
                shell = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            // 读不出来就不动它
        }
        if (shell != null && (shell.has("libraries") || shell.has("inheritsFrom")))
            return;

        try (var stream = Files.walk(directory)) {
            List<Path> paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths)
                Files.deleteIfExists(path);
            Logger.LOG.info("已删除不再被引用的壳版本 " + shellId);
        } catch (IOException e) {
            Logger.LOG.warning("删除壳版本 " + shellId + " 失败：" + e);
        }
    }

    /// 从 Mojang 官方清单下载原版版本 json。
    private static void downloadVanillaJson(String versionId, String gameVersion, Path target,
                                            BiConsumer<String, Long> progress) throws IOException {
        progress.accept("正在获取原版 " + gameVersion + " 版本信息", 0L);
        String index = httpGet(VANILLA_MANIFEST_URL);
        JsonObject manifest = JsonParser.parseString(index).getAsJsonObject();
        if (!manifest.has("versions") || !manifest.get("versions").isJsonArray())
            throw new IOException("Mojang 版本清单格式异常");
        String url = null;
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            if (!element.isJsonObject())
                continue;
            JsonObject entry = element.getAsJsonObject();
            JsonElement id = entry.get("id");
            if (id != null && id.isJsonPrimitive() && gameVersion.equals(id.getAsString())) {
                JsonElement u = entry.get("url");
                if (u != null && u.isJsonPrimitive())
                    url = u.getAsString();
                break;
            }
        }
        if (url == null)
            throw new IOException("Mojang 官方清单里没有版本 " + gameVersion
                    + "。请确认游戏版本号是否正确（例如 1.21.1 而不是 1.21）。");

        String json = httpGet(url);
        JsonObject version = JsonParser.parseString(json).getAsJsonObject();
        // 写成我们的版本 id，并保证 id 字段一致（HMCL 以目录名/文件名为准，但 id 保持一致更稳）
        version.addProperty("id", versionId);
        // `&lt;id&gt;-vanilla` 是我们为「用户已有同名修改版」准备的副本，纯粹是 Lunar 的继承底座。
        // 若不加 hidden，它会以原版的样子出现在版本列表里 —— 用户就会看到两个原版。
        // 真正的同名原版（versionId == gameVersion）保持可见，用户的游戏不会平白消失。
        if (!versionId.equals(gameVersion))
            version.addProperty("hidden", true);
        Files.createDirectories(target.getParent());
        writeJsonAtomically(target, version.toString());
        progress.accept("已准备原版基础版本 " + versionId, 0L);
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "HMCL-ServerManager-Plugin/1.0");
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("下载失败 HTTP " + code + "：" + url);
        }
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /// 把插件里带的入口包装 jar 释放到 `libraries/` 下。
    ///
    /// @return 是否可用（插件资源里没有这个 jar 时返回 false，调用方退回用 Genesis 作主类）
    /// Installs the entry wrapper jar into the launcher's `libraries/`.
    ///
    /// @return how many bytes were actually written; `0` when the wrapper was already current,
    ///         so reinstalling does not report a copy that never happened
    private static long installWrapper(Path librariesDir, BiConsumer<String, Long> progress) throws IOException {
        try (InputStream in = LunarVersionInstaller.class.getResourceAsStream(WRAPPER_RESOURCE)) {
            if (in == null)
                throw new IOException("插件包不完整：缺少资源 " + WRAPPER_RESOURCE
                        + "（请用 build.ps1 重新打包）");
            byte[] bytes = in.readAllBytes();
            Path target = librariesDir.resolve(WRAPPER_LIBRARY_PATH);
            if (Files.isRegularFile(target) && Files.size(target) == bytes.length)
                return 0;
            Files.createDirectories(target.getParent());
            progress.accept("正在安装启动包装", 0L);
            Files.write(target, bytes);
            return bytes.length;
        }
    }

    /// 生成 HMCL/Minecraft 标准的版本 json。
    ///
    /// <p>参数分两半：Lunar 专有参数（绝对路径，指向客户端目录）写进 `arguments.game`；
    /// 账号、游戏目录、资源索引等**不写**——基础版本的 `minecraftArguments`/`arguments.game`
    /// 已经由启动器拼进来了，重复写只会让 Genesis 的 joptsimple 报“同一个选项出现多次”。</p>
    ///
    /// <p><b>`-cp ${classpath}` 必须由我们自己写上。</b>HMCL 只有在版本 json **没有**
    /// `arguments.jvm` 时才补上自己的默认 JVM 参数（其中含 `-cp ${classpath}`，见
    /// `Arguments.DEFAULT_JVM_ARGUMENTS`）；一旦我们提供了 `arguments.jvm`，HMCL 就原样使用这一份，
    /// 缺了 `-cp` 的后果是 JVM 拿到空 classpath → `ClassNotFoundException: ...Genesis`。
    /// （老版本 json 用的是 `minecraftArguments`，所以那时是启动器补的。）</p>
    ///
    /// @param wrapper     入口包装类是否可用（不可用时退回把 Genesis 当作主类）
    /// @param baseVersion inheritsFrom 指向的基础版本（保证是原版，见 [#ensureVanillaBaseVersion]）
    private static String buildVersionJson(String versionId, LaunchData data, List<String> libraryNames,
                                           boolean wrapper, String baseVersion, JsonObject requiredJava) {
        String installation = data.installation().toAbsolutePath().toString().replace('\\', '/');
        String nativesDir = installation + "/natives";

        JsonArray libraries = new JsonArray();
        for (String name : libraryNames) {
            JsonObject library = new JsonObject();
            library.addProperty("name", name);
            libraries.add(library);
        }

        JsonArray jvm = new JsonArray();
        boolean sawLibraryPath = false;
        for (String arg : data.vmArgs()) {
            if (isGarbageCollectorOption(arg)) {
                // GC 由启动器挑：它默认塞一套 `-XX:+UseG1GC`（只有在已收集的参数里看到 GC 选择才跳过），
                // 而版本 json 的参数是在那之后追加的——Lunar 清单里的 `-XX:+UseZGC` 会和它同时生效，
                // JVM 直接拒绝启动：`Error occurred during initialization of VM /
                // Multiple garbage collectors selected`。
                Logger.LOG.info("已忽略 Lunar 清单里的 GC 参数（GC 交给启动器）：" + arg);
                continue;
            }
            if (arg.startsWith("-Djava.library.path=")) {
                // Lunar 要求 natives 指向它自己的目录
                jvm.add("-Djava.library.path=" + nativesDir);
                sawLibraryPath = true;
            } else {
                jvm.add(arg);
            }
        }
        if (!sawLibraryPath)
            jvm.add("-Djava.library.path=" + nativesDir);
        // 入口包装类靠这个属性知道该给 Genesis 传哪个游戏版本（命令行里的 --version 不可信）
        jvm.add("-D" + GAME_VERSION_PROPERTY + "=" + data.gameVersion());
        // 启动器会把这个占位符展开成「版本所有库 + 该版本的主 jar」（DefaultLauncher 里注入）
        jvm.add("-cp");
        jvm.add("${classpath}");

        JsonArray game = new JsonArray();
        // Lunar（genesis）专有参数——与原版 Celestial 启动命令逐项一致
        game.add("--ichorClassPath");
        game.add(String.join(",", data.classpath().stream()
                .map(p -> p.replace('\\', '/')).toList()));
        game.add("--ichorExternalFiles");
        // 只写文件名：Genesis 把这一项当作「允许的外部文件」清单，用它与候选文件的
        // `Path.getFileName()` 做等值比较，绝对路径永远匹配不上（ReplayMod 这类按前缀查找的
        // 外部文件会以 `Could not find external file replaymod` 失败）。这里统一取文件名，
        // 老版本装出来的 json 里即使存着绝对路径，也会在下次启动/修复时被就地纠正。
        game.add(String.join(",", data.ichorpath().stream()
                .map(LunarVersionInstaller::externalFileName)
                .toList()));
        game.add("--webosrDir");
        game.add(nativesDir);
        game.add("--classpathDir");
        game.add(installation);
        game.add("--workingDirectory");
        game.add(installation);
        game.add("--texturesDir");
        game.add("textures");
        game.add("--uiDir");
        game.add(installation + "/ui");
        game.add("--installationId");
        // 稳定 id：官方启动器的 installation-id（同机时）或插件数据目录生成的那个。
        // 游戏 gameipc 握手会回传它，包装类据此校验连接合法性。
        game.add(LunarWrapperUpgrader.stableInstallationId());
        game.add("--hwid");
        game.add("PUBLIC-HWID");
        game.add("--canaryToken");
        game.add("control");
        game.add("--launchId");
        game.add(UUID.randomUUID().toString());
        game.add("--launcherVersion");
        game.add("3.5.2");
        game.add("--ipcPort");
        game.add("0");

        // 账号与游戏参数交给启动器：基础版本的 `minecraftArguments`（老式）或 `arguments.game`
        // （新式）会提供 `--username/--uuid/--accessToken/--gameDir/--assetsDir/--assetIndex/
        // --userType/--userProperties`，`--width/--height` 由启动器在设置了分辨率时补上。
        //
        // 唯一的问题是 `--version`：基础版本给的是实例名（例如 `Lunar-1.21.11`），而 Genesis
        // 要求它是合法游戏版本号，且只允许出现一次。这两点都由入口包装类
        // （`-Ddsh.lunar.gameVersion` + GenesisLauncher）在启动时统一处理，所以这里不写。

        JsonObject arguments = new JsonObject();
        arguments.add("jvm", jvm);
        arguments.add("game", game);

        JsonObject version = new JsonObject();
        version.addProperty("id", versionId);
        version.addProperty("inheritsFrom", baseVersion);
        version.addProperty("mainClass", wrapper ? WRAPPER_MAIN_CLASS : data.mainClass());
        version.add("javaVersion", requiredJava);
        version.add("libraries", libraries);
        version.add("arguments", arguments);
        return version.toString();
    }

    /// 是否是 GC 选择参数（`-XX:+UseZGC` / `-XX:-UseG1GC` 之类）。
    ///
    /// <p>这类参数会与启动器自动加上的 GC 参数冲突，必须只保留一份。</p>
    private static boolean isGarbageCollectorOption(String argument) {
        return argument != null && argument.matches("-XX:[+-]Use[A-Za-z0-9]*GC");
    }

    /// 修掉「声明了但 `libraries/` 下没有文件」的 Lunar 私有库。
    ///
    /// <p>HMCL 的 `GameLibrariesTask` 只看文件在不在：不在就按 Maven 坐标去
    /// `libraries.minecraft.net` 下载。`org.dsh.lunar` 是插件私有坐标，官方库没有，于是
    /// `FileNotFoundException` 404，启动被拦在「未能下载游戏依赖」那一步。</p>
    ///
    /// <p>早期版本会把 Lunar 的 classpath jar 只留在客户端目录、没复制进 `libraries/`
    /// （例如 `lunar-platform-mappings-v1_8.jar` 停在客户端根目录）。这里按文件名去客户端目录
    /// 回捞；确实找不到源文件的，才把那条声明删掉。</p>
    ///
    /// @return 是否改动了 json
    private static boolean restoreMissingClasspathLibraries(JsonObject version, Path gameDirectory) {
        try {
            JsonElement argumentsElement = version.get("arguments");
            if (argumentsElement == null || !argumentsElement.isJsonObject())
                return false;
            JsonObject arguments = argumentsElement.getAsJsonObject();
            JsonElement gameElement = arguments.get("game");
            if (gameElement == null || !gameElement.isJsonArray())
                return false;

            String installationValue = argumentValue(gameElement.getAsJsonArray(), "--classpathDir");
            if (installationValue == null || installationValue.isBlank())
                return false;

            JsonElement librariesElement = version.get("libraries");
            if (librariesElement == null || !librariesElement.isJsonArray())
                return false;

            Path installation = Path.of(installationValue);
            Path librariesDir = gameDirectory.resolve("libraries");
            JsonArray libraries = librariesElement.getAsJsonArray();

            // 客户端目录下所有 jar 的文件名 → 路径（用于按名字回捞）
            Map<String, Path> available = new HashMap<>();
            if (Files.isDirectory(installation)) {
                try (var stream = Files.walk(installation, 3)) {
                    for (Path jar : stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".jar"))
                            .toList()) {
                        available.putIfAbsent(jar.getFileName().toString(), jar);
                    }
                } catch (IOException e) {
                    Logger.LOG.warning("扫描客户端目录失败：" + e);
                }
            }

            JsonArray kept = new JsonArray();
            boolean changed = false;
            for (JsonElement element : libraries) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("name")) {
                    kept.add(element);
                    continue;
                }
                String name = element.getAsJsonObject().get("name").getAsString();
                if (!name.startsWith("org.dsh.lunar:") || libraryInstalled(librariesDir, name)) {
                    kept.add(element);
                    continue;
                }

                // 缺失 → 按坐标里的 artifact 名去客户端目录找同名 jar
                String[] parts = name.split(":");
                String artifact = parts.length > 1 ? parts[1] : null;
                Path source = null;
                if (artifact != null) {
                    for (Map.Entry<String, Path> entry : available.entrySet()) {
                        if (sanitize(entry.getKey()).equals(artifact)) {
                            source = entry.getValue();
                            break;
                        }
                    }
                }

                if (source == null) {
                    Logger.LOG.warning("移除失效的 Lunar 库声明（客户端目录也找不到源文件）：" + name);
                    changed = true;
                    continue;
                }

                try {
                    copyIfNeeded(source, classpathTarget(librariesDir, source));
                } catch (IOException e) {
                    Logger.LOG.warning("回捞 " + source.getFileName() + " 失败：" + e.getMessage());
                }
                if (libraryInstalled(librariesDir, name)) {
                    Logger.LOG.info("已从客户端目录回捞 Lunar 库：" + source.getFileName());
                    kept.add(element);
                } else {
                    Logger.LOG.warning("回捞后仍不可用，移除声明：" + name);
                    changed = true;
                }
            }

            if (!changed)
                return false;
            version.add("libraries", kept);
            return true;
        } catch (Exception e) {
            Logger.LOG.warning("回捞 Lunar classpath 库失败：" + e);
            return false;
        }
    }


    /// 补齐 Lunar 自己声明的运行库，并把它们插在 Lunar 自己的 jar 之后、原版库之前。
    ///
    /// <p>老版本装出来的 json 里只有 Lunar 的 classpath jar，运行库全靠继承来的**原版**那一套
    /// （1.12.2 是 fastutil 7.1.0、netty 4.1.9、log4j 2.8.1 …）。Lunar 的代码按自己那一套编译，
    /// 于是启动崩在 `NoSuchMethodError: 'void it.unimi.dsi.fastutil.ints.IntArraySet.&lt;init&gt;(...)'`。
    /// 这里按 `versions.conf` 把缺的运行库下回来、装进 `libraries/`，并插到 Lunar 自己的 jar 之后
    /// （不能更靠前：Lunar jar 里自带的 shaded 新版类会被旧库盖掉）、继承来的原版库之前
    /// （HMCL 合并继承链时子版本在前且不去重，同名的库由我们提供的版本生效）。</p>
    ///
    /// @return 是否改动了 json
    private static boolean ensureRuntimeLibraries(JsonObject version, Path versionJson, Path gameDirectory) {
        try {
            JsonElement argumentsElement = version.get("arguments");
            if (argumentsElement == null || !argumentsElement.isJsonObject())
                return false;
            JsonObject arguments = argumentsElement.getAsJsonObject();

            JsonElement gameElement = arguments.get("game");
            String installationValue = gameElement != null && gameElement.isJsonArray()
                    ? argumentValue(gameElement.getAsJsonArray(), "--classpathDir") : null;
            if (installationValue == null || installationValue.isBlank())
                return false;

            String gameVersion = gameVersionOf(version, arguments);
            if (gameVersion == null)
                return false;

            Path installation = Path.of(installationValue);
            List<String> declared = LunarLibraries.declared(installation, gameVersion);
            if (declared.isEmpty())
                return false;

            JsonElement librariesElement = version.get("libraries");
            JsonArray libraries = librariesElement != null && librariesElement.isJsonArray()
                    ? librariesElement.getAsJsonArray() : new JsonArray();
            Set<String> existing = new HashSet<>();
            for (JsonElement element : libraries)
                if (element.isJsonObject() && element.getAsJsonObject().has("name"))
                    existing.add(element.getAsJsonObject().get("name").getAsString());

            List<Path> jars = LunarLibraries.download(installation, declared, () -> false);
            Path librariesDir = gameDirectory.resolve("libraries");

            // 统一重排成：Lunar 自己的 jar → Lunar 运行库 → 其它库（顺序理由见 install 里的说明）。
            // 这样「缺运行库」和「顺序不对」两种毛病都能一次修好。
            Set<String> classpathCoordinates = new HashSet<>();
            JsonElement gameArray = arguments.get("game");
            if (gameArray != null && gameArray.isJsonArray())
                for (String jarPath : splitArgument(argumentValue(gameArray.getAsJsonArray(), "--ichorClassPath"))) {
                    Path fileName = Path.of(jarPath).getFileName();
                    if (fileName != null)
                        classpathCoordinates.add("org.dsh.lunar:" + sanitize(fileName.toString()) + ":1.0");
                }

            boolean changed = false;
            Set<String> placed = new HashSet<>();
            JsonArray merged = new JsonArray();
            for (JsonElement element : libraries) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("name"))
                    continue;
                String name = element.getAsJsonObject().get("name").getAsString();
                if (classpathCoordinates.contains(name)) {
                    merged.add(element);
                    placed.add(name);
                }
            }
            for (Path jar : jars) {
                Path fileName = jar.getFileName();
                if (fileName == null)
                    continue;
                String coordinate = "org.dsh.lunar:" + sanitize(fileName.toString()) + ":1.0";
                Path target = classpathTarget(librariesDir, jar);
                if (!existing.contains(coordinate))
                    changed = true;
                copyIfNeeded(jar, target);
                // 复制没落地就别声明：否则启动器会认为缺库，去 libraries.minecraft.net 下载
                // org.dsh.lunar（私有坐标）→ FileNotFoundException 404，启动失败
                if (!Files.isRegularFile(target)) {
                    Logger.LOG.warning("Lunar 运行库未能装进 libraries，跳过声明：" + fileName);
                    continue;
                }
                if (!existing.contains(coordinate))
                    Logger.LOG.info("已补齐 Lunar 运行库：" + fileName);
                JsonObject library = new JsonObject();
                library.addProperty("name", coordinate);
                merged.add(library);
                placed.add(coordinate);
            }
            for (JsonElement element : libraries) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("name"))
                    continue;
                String name = element.getAsJsonObject().get("name").getAsString();
                if (placed.contains(name))
                    continue;
                // 丢掉「已声明但 libraries/ 下没有文件」的 Lunar 私有库：留着它启动必 404。
                // 只针对我们自己的坐标，用户/原版的库原样保留。
                if (name.startsWith("org.dsh.lunar:") && !libraryInstalled(librariesDir, name)) {
                    Logger.LOG.warning("移除失效的 Lunar 库声明（文件已不存在）：" + name);
                    changed = true;
                    continue;
                }
                merged.add(element);
            }

            if (!changed && libraries.toString().equals(merged.toString()))
                return false;
            version.add("libraries", merged);
            return true;
        } catch (Exception e) {
            Logger.LOG.warning("补齐 Lunar 运行库失败：" + e);
            return false;
        }
    }

    /// 这个版本对应的游戏版本号：优先用入口包装类拿到的 `-Ddsh.lunar.gameVersion`，
    /// 其次从 `inheritsFrom` 去掉壳/副本后缀。
    private static @Nullable String gameVersionOf(JsonObject version, JsonObject arguments) {
        String prefix = "-D" + GAME_VERSION_PROPERTY + "=";
        JsonElement jvmElement = arguments.get("jvm");
        if (jvmElement != null && jvmElement.isJsonArray())
            for (JsonElement element : jvmElement.getAsJsonArray())
                if (element.isJsonPrimitive() && element.getAsString().startsWith(prefix))
                    return element.getAsString().substring(prefix.length());

        JsonElement inheritsFrom = version.get("inheritsFrom");
        if (inheritsFrom == null || !inheritsFrom.isJsonPrimitive())
            return null;
        String normalized = normalizeBaseGameVersion(inheritsFrom.getAsString());
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    /// 把一个外部文件条目归一成文件名（`--ichorExternalFiles` 只接受文件名）。
    ///
    /// <p>Genesis 用这组字符串与 `--classpathDir` 里候选文件的 `Path.getFileName()` 做等值比较，
    /// 所以条目必须是纯文件名；对老版本 json 里遗留的绝对路径，这里就地截取文件名，纠正它。</p>
    private static String externalFileName(String entry) {
        int slash = Math.max(entry.lastIndexOf('/'), entry.lastIndexOf('\\'));
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    /// 把 `--ichorExternalFiles` 后面那一条参数就地改写成纯文件名清单。
    ///
    /// @return 是否改动了它
    private static boolean normalizeExternalFiles(JsonArray game) {
        for (int index = 0; index + 1 < game.size(); index++) {
            JsonElement option = game.get(index);
            if (!option.isJsonPrimitive() || !"--ichorExternalFiles".equals(option.getAsString()))
                continue;

            JsonElement value = game.get(index + 1);
            if (!value.isJsonPrimitive())
                return false;

            List<String> names = new ArrayList<>();
            for (String entry : value.getAsString().split(","))
                if (!entry.isBlank())
                    names.add(externalFileName(entry.trim()));
            String normalized = String.join(",", names);
            if (normalized.equals(value.getAsString()))
                return false;
            game.set(index + 1, new JsonPrimitive(normalized));
            Logger.LOG.info("已把 --ichorExternalFiles 归一成文件名（Genesis 只认文件名）");
            return true;
        }
        return false;
    }

    /// `javaVersion` 节点：启动器据此校验/挑选 Java。
    private static JsonObject javaVersion() {
        JsonObject javaVersion = new JsonObject();
        javaVersion.addProperty("component", REQUIRED_JAVA.component());
        javaVersion.addProperty("majorVersion", REQUIRED_JAVA.majorVersion());
        return javaVersion;
    }

    /// 这份 Lunar 版本应当声明的 Java 要求：至少 [REQUIRED_JAVA]，且不低于基础版本自己的要求。
    ///
    /// <p>原版 26.x 的 json 声明 `javaVersion.majorVersion = 25`，它的 `arguments.jvm` 里还带一条
    /// Java 24 才认识的 `--sun-misc-unsafe-memory-access=allow`；这条参数会随继承链合并进来。
    /// 如果我们只声明 21，启动器就会挑 Java 21，再把那条参数交给 JVM ——
    /// `Unrecognized option: --sun-misc-unsafe-memory-access=allow`，进程秒退（崩因显示为“未知”）。</p>
    private static JsonObject requiredJavaVersion(Path gameDirectory, String baseVersionId) {
        JsonObject fromBase = readJavaVersion(gameDirectory, baseVersionId);
        if (fromBase != null
                && fromBase.has("majorVersion")
                && fromBase.get("majorVersion").isJsonPrimitive()
                && fromBase.get("majorVersion").getAsInt() > REQUIRED_JAVA.majorVersion())
            return fromBase;
        return javaVersion();
    }

    /// 读取某个版本 json 里的 `javaVersion` 节点（没有或读不出来时返回 null）。
    private static @Nullable JsonObject readJavaVersion(Path gameDirectory, @Nullable String versionId) {
        if (versionId == null || versionId.isBlank())
            return null;

        try {
            Path json = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
            if (!Files.isRegularFile(json))
                return null;
            JsonObject version = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement javaVersion = version.get("javaVersion");
            return javaVersion != null && javaVersion.isJsonObject() ? javaVersion.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /// 用安装目录里已有的客户端文件**重建**版本 json（不重新下载）。
    ///
    /// <p>早期版本生成的 json 里 `arguments.game` 与启动器会给的基础版本参数重复（`--version`
    /// 出现两次会让 Genesis 抛 `MultipleArgumentsForOptionException`），光靠打补丁修不干净，
    /// 所以这里从 json 里读回安装目录、classpath、外部文件与 JVM 参数，用当前方案重新生成一份。</p>
    ///
    /// @return 安装结果
    public static Result regenerate(Path versionJson) throws IOException {
        JsonObject version;
        try {
            version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("这个文件不是有效的版本 json：" + versionJson, e);
        }

        String versionId = version.has("id") ? version.get("id").getAsString() : null;
        String gameVersion = version.has("inheritsFrom") ? version.get("inheritsFrom").getAsString() : null;
        String mainClass = version.has("mainClass") ? version.get("mainClass").getAsString() : null;
        if (versionId == null || gameVersion == null || mainClass == null)
            throw new IOException("版本 json 缺少 id/inheritsFrom/mainClass，无法重建：" + versionJson);

        JsonArray gameArguments = version.getAsJsonObject("arguments").getAsJsonArray("game");
        String installationValue = argumentValue(gameArguments, "--classpathDir");
        if (installationValue == null || installationValue.isBlank())
            throw new IOException("版本 json 里没有 --classpathDir，无法定位客户端目录：" + versionJson);

        List<String> classpath = splitArgument(argumentValue(gameArguments, "--ichorClassPath"));
        List<String> ichorpath = splitArgument(argumentValue(gameArguments, "--ichorExternalFiles"));
        if (classpath.isEmpty())
            throw new IOException("版本 json 里没有 --ichorClassPath，无法重建：" + versionJson);

        List<String> vmArgs = new ArrayList<>();
        JsonArray jvm = version.has("arguments") ? version.getAsJsonObject("arguments").getAsJsonArray("jvm") : null;
        if (jvm != null) {
            for (JsonElement element : jvm) {
                if (!element.isJsonPrimitive())
                    continue;
                String argument = element.getAsString();
                // -cp/-Djava.library.path 由生成器自己写，这里过滤掉，剩下的就是清单里的 JVM 参数
                if ("-cp".equals(argument) || "${classpath}".equals(argument)
                        || argument.startsWith("-Djava.library.path=")
                        || argument.startsWith("-D" + GAME_VERSION_PROPERTY + "="))
                    continue;
                vmArgs.add(argument);
            }
        }

        Path gameDirectory = versionJson.toAbsolutePath().getParent().getParent().getParent();
        Path installation = Path.of(installationValue);
        LaunchData data = new LaunchData(installation, mainClass, gameVersion, vmArgs, classpath, ichorpath,
                LunarLibraries.installed(installation));
        return install(gameDirectory, versionId, data, (step, bytes) -> {
        });
    }

    /// 取 `--flag value` 形式参数里的值。
    private static String argumentValue(JsonArray arguments, String flag) {
        if (arguments == null)
            return null;
        for (int i = 0; i + 1 < arguments.size(); i++)
            if (arguments.get(i).isJsonPrimitive() && flag.equals(arguments.get(i).getAsString()))
                return arguments.get(i + 1).getAsString();
        return null;
    }

    /// 逗号分隔的参数值 → 列表。
    private static List<String> splitArgument(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank())
            return result;
        for (String part : value.split(","))
            if (!part.isBlank())
                result.add(part.trim());
        return result;
    }

    /// 给已经装好的版本补上启动要求：`javaVersion`、`-cp ${classpath}` 与规范化的外部文件清单。
    ///
    /// <p>早期生成的版本 json 有三个坑，都会让「用 HMCL 启动」直接失败：</p>
    /// <ol>
    ///   <li>没有 `javaVersion` → 启动器按基础版本（1.8.9）推断成 Java 8，而 Lunar 的参数里有
    ///       `--add-modules`，Java 8 直接起不来（崩因显示为“未知”）；</li>
    ///   <li>`arguments.jvm` 里没有 `-cp ${classpath}` → 启动器认为这份 json 自带完整 JVM 参数，
    ///       于是不再补默认的 `-cp`，JVM 拿到空 classpath → `ClassNotFoundException: ...Genesis`；</li>
    ///   <li>`--ichorExternalFiles` 里写的是绝对路径 → Genesis 只认文件名，按前缀查找外部文件的
    ///       模块（1.12.2-forge 的 ReplayMod）会以 `Could not find external file replaymod` 崩掉。</li>
    /// </ol>
    /// <p>这里在启动前顺手补上，老版本不用重新下载 150 MB 也能修好。</p>
    ///
    /// @return 是否改动了文件（调用方改完要刷新版本列表）
    public static boolean ensureLaunchRequirements(Path versionJson) throws IOException {
        if (!Files.isRegularFile(versionJson))
            return false;

        JsonObject version;
        try {
            version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return false; // 解析不了就不是我们生成的版本，别乱动
        }

        boolean changed = false;

        // 1) Java 版本要求：至少 21，且不低于基础版本自己的要求（原版 26.x 要 25，
        //    否则启动器会挑 Java 21 去跑带 `--sun-misc-unsafe-memory-access` 的命令行 → 秒退）
        Path gameDirectory = versionJson.toAbsolutePath().getParent().getParent().getParent();
        String baseVersionId = version.has("inheritsFrom") && version.get("inheritsFrom").isJsonPrimitive()
                ? version.get("inheritsFrom").getAsString() : null;
        JsonObject required = requiredJavaVersion(gameDirectory, baseVersionId);
        if (version.has("javaVersion") && version.get("javaVersion").isJsonObject()) {
            JsonObject existing = version.getAsJsonObject("javaVersion");
            if (!existing.has("majorVersion")
                    || existing.get("majorVersion").getAsInt() < required.get("majorVersion").getAsInt()) {
                version.add("javaVersion", required);
                changed = true;
            }
        } else {
            version.add("javaVersion", required);
            changed = true;
        }

        // 1b) 去掉 Lunar 清单带进来的 GC 选择参数：启动器会自己塞一套 G1 参数，两份 GC 同时出现
        //     会让 JVM 拒绝启动（Multiple garbage collectors selected）
        if (version.has("arguments") && version.get("arguments").isJsonObject()) {
            JsonObject arguments = version.getAsJsonObject("arguments");
            if (arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
                JsonArray jvm = arguments.getAsJsonArray("jvm");
                JsonArray filtered = new JsonArray();
                boolean removedGc = false;
                for (JsonElement element : jvm) {
                    if (element.isJsonPrimitive() && isGarbageCollectorOption(element.getAsString())) {
                        removedGc = true;
                        Logger.LOG.info("已移除与启动器冲突的 GC 参数：" + element.getAsString());
                        continue;
                    }
                    filtered.add(element);
                }
                if (removedGc) {
                    arguments.add("jvm", filtered);
                    changed = true;
                }
            }
        }

        // 1c) `--ichorExternalFiles` 只接受文件名：Genesis 用这组字符串与 `--classpathDir` 下候选文件的
        //     `Path.getFileName()` 做等值比较，绝对路径永远匹配不上，按前缀查找外部文件的模块
        //     （1.12.2-forge 的 ReplayMod）会直接抛 `Could not find external file replaymod`。
        //     早期版本的 json 里存的是绝对路径，这里就地纠正，不用重装客户端。
        if (version.has("arguments") && version.get("arguments").isJsonObject()) {
            JsonObject arguments = version.getAsJsonObject("arguments");
            if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                JsonArray game = arguments.getAsJsonArray("game");
                if (normalizeExternalFiles(game)) {
                    arguments.add("game", game);
                    changed = true;
                }
            }
        }

        // 1d) Lunar 自己声明的运行库必须排在 libraries 最前面。早期版本装出来的 json 里没有它们，
        //     继承链带来的原版库（1.12.2 的 fastutil 7.1.0 等）就会先生效，启动崩在
        //     `NoSuchMethodError: IntArraySet.<init>(IntSet)` 这类地方。这里就地补齐，不用重装。
        if (ensureRuntimeLibraries(version, versionJson, gameDirectory))
            changed = true;

        // 1e) classpath 声明里缺失的 Lunar 库：早期版本把 jar 留在了客户端目录（没复制进
        //     `libraries/`），启动器发现文件不在就去 `libraries.minecraft.net` 下载这个私有坐标，
        //     必然 404（`未能下载游戏依赖 org.dsh.lunar:...`）。这里从客户端目录回捞，
        //     实在找不到才删掉声明——留着它启动一定失败。
        if (restoreMissingClasspathLibraries(version, gameDirectory))
            changed = true;

        // 2) classpath：json 里没有 -cp ${classpath} 就补在 -Djava.library.path 之后
        if (version.has("arguments") && version.get("arguments").isJsonObject()) {
            JsonObject arguments = version.getAsJsonObject("arguments");
            if (arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
                JsonArray jvm = arguments.getAsJsonArray("jvm");
                boolean hasClasspath = false;
                for (JsonElement element : jvm)
                    if (element.isJsonPrimitive() && "${classpath}".equals(element.getAsString()))
                        hasClasspath = true;
                if (!hasClasspath) {
                    JsonArray repaired = new JsonArray();
                    boolean inserted = false;
                    for (JsonElement element : jvm) {
                        repaired.add(element);
                        if (!inserted && element.isJsonPrimitive()
                                && element.getAsString().startsWith("-Djava.library.path=")) {
                            repaired.add("-cp");
                            repaired.add("${classpath}");
                            inserted = true;
                        }
                    }
                    if (!inserted) {
                        repaired.add("-cp");
                        repaired.add("${classpath}");
                    }
                    arguments.add("jvm", repaired);
                    changed = true;
                }
            }
        }

        // 3) 基础版本必须是原版：inheritsFrom 指向 Fabric/Forge 版会把官方（final 的）
        //    fabric-loader 带上 classpath，与 Lunar 打过补丁的版本冲突
        //    （IncompatibleClassChangeError: cannot inherit from final class FabricLoaderImpl）。
        String gameVersion = version.has("inheritsFrom") && version.get("inheritsFrom").isJsonPrimitive()
                ? version.get("inheritsFrom").getAsString() : null;
        if (gameVersion != null && !gameVersion.isBlank() && !gameVersion.endsWith("-lunar-base")) {
            try {
                String vanillaId = gameVersion;
                Path baseJson = gameDirectory.resolve("versions").resolve(vanillaId)
                        .resolve(vanillaId + ".json");
                if (Files.isRegularFile(baseJson) && !isVanillaJson(baseJson)) {
                    // 基础版本是 Fabric/Forge 修改版 → 准备原版副本，不动用户的版本
                    vanillaId = gameVersion + "-vanilla";
                    Path vanillaJson = gameDirectory.resolve("versions").resolve(vanillaId)
                            .resolve(vanillaId + ".json");
                    if (!Files.isRegularFile(vanillaJson) || !isVanillaJson(vanillaJson))
                        downloadVanillaJson(vanillaId, gameVersion, vanillaJson, (step, bytes) -> { });
                } else if (!Files.isRegularFile(baseJson)) {
                    // 基础版本不存在 → 下载原版
                    downloadVanillaJson(vanillaId, gameVersion, baseJson, (step, bytes) -> { });
                }
                // 一律改指零库壳版本：原版库（尤其 1.8.9 的 gson 2.2.4 / guava 17 等老库）
                // 会与 Lunar 内置版本冲突（NoSuchMethodError），游戏类必须全由 ichor 提供
                String shellId = ensureShellBaseVersion(gameDirectory, vanillaId, gameVersion, (s, b) -> { });
                if (!shellId.equals(gameVersion)) {
                    version.addProperty("inheritsFrom", shellId);
                    changed = true;
                }
            } catch (IOException e) {
                Logger.LOG.warning("修复 Lunar 基础版本失败：" + e);
            }
        } else if (gameVersion != null && gameVersion.endsWith("-lunar-base")) {
            // 已经指向壳版本：旧格式的壳自带一份原版客户端 jar，就地升级成引用原版 jar 的形式
            upgradeShellVersion(gameDirectory, gameVersion);
        }

        if (!changed)
            return false;

        writeJsonAtomically(versionJson, version.toString());
        return true;
    }

    /// 把游戏目录里所有旧格式的壳版本升级为“引用原版 jar”的形式，并清掉没人引用的孤壳。
    ///
    /// <p>旧格式的壳版本在自己目录里放了一份原版客户端 jar，等于每个 Lunar 版本多占一份游戏本体
    /// （1.8.9 约 8 MB，新版本 20-30 MB）；早期版本还可能生成 `...-vanilla-lunar-base` 这类套娃
    /// 壳。这里就地改写它们的 json、删掉那份副本，并把不再被任何版本继承的壳删掉——用户不用重装。</p>
    public static void upgradeInstalledShellVersions(Path gameDirectory) {
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory))
            return;

        List<String> shells = new ArrayList<>();
        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                Path fileName = directory.getFileName();
                if (fileName != null && fileName.toString().endsWith("-lunar-base"))
                    shells.add(fileName.toString());
            }
        } catch (IOException e) {
            Logger.LOG.warning("扫描壳版本失败：" + e);
            return;
        }

        for (String shellId : shells) {
            upgradeShellVersion(gameDirectory, shellId);
            if (!isShellReferenced(gameDirectory, shellId, null))
                removeShellVersion(gameDirectory, shellId);
        }
    }

    /// 给插件生成的 `&lt;版本&gt;-vanilla` 副本补上 `hidden`（老安装的副本没有这个字段，
    /// 会以「第二个原版」的样子留在启动器版本列表里）。用户自己的同名原版不受影响。
    public static void hideInstalledVanillaCopies(Path gameDirectory) {
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory))
            return;

        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                Path fileName = directory.getFileName();
                if (fileName == null || !fileName.toString().endsWith("-vanilla"))
                    continue;

                String versionId = fileName.toString();
                Path versionJson = directory.resolve(versionId + ".json");
                if (!Files.isRegularFile(versionJson))
                    continue;

                try {
                    JsonObject version = JsonParser.parseString(
                            Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonElement hidden = version.get("hidden");
                    if (hidden != null && hidden.isJsonPrimitive() && hidden.getAsBoolean())
                        continue;

                    version.addProperty("hidden", true);
                    writeJsonAtomically(versionJson, version.toString());
                    Logger.LOG.info("已把原版副本 " + versionId + " 标记为隐藏（不再作为第二个原版显示）");
                } catch (Exception e) {
                    Logger.LOG.warning("隐藏原版副本 " + versionId + " 失败：" + e);
                }
            }
        } catch (IOException e) {
            Logger.LOG.warning("扫描原版副本失败：" + e);
        }
    }

    /// 启动前体检：列出这个 Lunar 版本当前存在的已知问题（**只读**，不改任何文件）。
    /// <p>每条都是给人看的中文说明；[#repair] 能把其中绝大多数直接修掉。
    /// [LunarRepair] 用它决定「要不要修」，修复页用它显示问题清单。</p>
    public static List<String> inspect(Path gameDirectory, String versionId) {
        List<String> problems = new ArrayList<>();
        Path versionJson = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(versionJson)) {
            problems.add("版本 json 不存在（可能已被删除，需要重新安装）");
            return problems;
        }

        JsonObject version;
        try {
            version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            problems.add("版本 json 读不出来或解析失败（需要重新安装）");
            return problems;
        }

        JsonObject arguments = version.has("arguments") && version.get("arguments").isJsonObject()
                ? version.getAsJsonObject("arguments") : new JsonObject();
        JsonArray game = arguments.has("game") && arguments.get("game").isJsonArray()
                ? arguments.getAsJsonArray("game") : new JsonArray();
        JsonArray jvm = arguments.has("jvm") && arguments.get("jvm").isJsonArray()
                ? arguments.getAsJsonArray("jvm") : new JsonArray();

        // 1) 外部文件必须是文件名（Genesis 拿它和候选文件的 Path.getFileName() 做等值比较）
        for (String entry : splitArgument(argumentValue(game, "--ichorExternalFiles")))
            if (entry.contains("/") || entry.contains("\\")) {
                problems.add("--ichorExternalFiles 里是绝对路径（Genesis 只认文件名，"
                        + "ReplayMod 之类的模块会报 Could not find external file）");
                break;
            }

        // 2) -cp ${classpath}
        boolean hasClasspath = false;
        for (JsonElement element : jvm)
            if (element.isJsonPrimitive() && "${classpath}".equals(element.getAsString()))
                hasClasspath = true;
        if (!hasClasspath)
            problems.add("JVM 参数里没有 -cp ${classpath}（JVM 拿不到 classpath，"
                    + "会 ClassNotFoundException: com.moonsworth.lunar.genesis.Genesis）");

        // 3) GC 参数与启动器冲突
        for (JsonElement element : jvm)
            if (element.isJsonPrimitive() && isGarbageCollectorOption(element.getAsString())) {
                problems.add("JVM 参数里有 Lunar 清单自带的 GC 选项 " + element.getAsString()
                        + "（与启动器的 GC 参数冲突：Multiple garbage collectors selected）");
                break;
            }

        // 4) Java 要求
        String baseVersionId = version.has("inheritsFrom") && version.get("inheritsFrom").isJsonPrimitive()
                ? version.get("inheritsFrom").getAsString() : null;
        JsonObject required = requiredJavaVersion(gameDirectory, baseVersionId);
        int requiredMajor = required.has("majorVersion") ? required.get("majorVersion").getAsInt() : 21;
        int declaredMajor = version.has("javaVersion") && version.get("javaVersion").isJsonObject()
                && version.getAsJsonObject("javaVersion").has("majorVersion")
                ? version.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 0;
        if (declaredMajor < requiredMajor)
            problems.add("声明的 Java 版本过低（" + declaredMajor + " < " + requiredMajor
                    + "，启动器会挑错 Java 而秒退）");

        // 5) 基础版本必须是原版
        if (baseVersionId != null && !baseVersionId.endsWith("-lunar-base")) {
            Path baseJson = gameDirectory.resolve("versions").resolve(baseVersionId)
                    .resolve(baseVersionId + ".json");
            if (Files.isRegularFile(baseJson) && !isVanillaJson(baseJson))
                problems.add("继承的基础版本 " + baseVersionId + " 是修改版（会把官方 fabric-loader 带进 "
                        + "classpath：cannot inherit from final class FabricLoaderImpl）");
        }

        // 6) Lunar 运行库：既要下全，也要排在 Lunar 自己的 jar 之后、原版库之前
        String installationValue = argumentValue(game, "--classpathDir");
        String gameVersion = gameVersionOf(version, arguments);
        if (installationValue != null && gameVersion != null) {
            Path installation = Path.of(installationValue);
            if (Files.isDirectory(installation)) {
                List<String> declared = LunarLibraries.declared(installation, gameVersion);
                List<Path> jars = LunarLibraries.installed(installation).stream().map(Path::of).toList();
                if (!declared.isEmpty() && jars.size() < declared.size())
                    problems.add("Lunar 运行库缺 " + (declared.size() - jars.size())
                            + " 个（继承来的原版库会先生效：NoSuchMethodError / NoClassDefFoundError）");
                if (!jars.isEmpty() && !runtimeLibrariesPlacedCorrectly(version, game, jars))
                    problems.add("Lunar 运行库没有排在 Lunar 自己的 jar 之后"
                            + "（会被原版旧库或 jar 里自带的 shaded 类互相盖住）");
            }
        }

        // 7) libraries 里的 classpath 副本是否与客户端目录一致
        Path librariesDir = gameDirectory.resolve("libraries");
        for (String jarPath : splitArgument(argumentValue(game, "--ichorClassPath"))) {
            Path source = Path.of(jarPath);
            if (!Files.isRegularFile(source))
                continue;
            Path target = classpathTarget(librariesDir, source);
            if (!Files.isRegularFile(target)) {
                problems.add("libraries 里缺少 classpath 副本 " + target.getFileName() + "（需要重新安装）");
                break;
            }
            try {
                if (Files.size(target) != Files.size(source)) {
                    problems.add("libraries 里的 classpath 副本与客户端目录不一致"
                            + "（Mixin 会崩在 @Mixin target ... was not found）");
                    break;
                }
            } catch (IOException ignored) {
                // 读不到大小就跳过这一项
            }
        }

        // 8) 声明了但 libraries 下没有文件的 Lunar 私有库：启动器会去 libraries.minecraft.net
        //    下载 org.dsh.lunar 坐标 → FileNotFoundException 404，启动直接被拦下
        int missingDeclared = 0;
        String firstMissing = null;
        if (version.has("libraries") && version.get("libraries").isJsonArray()) {
            for (JsonElement element : version.getAsJsonArray("libraries")) {
                if (!element.isJsonObject())
                    continue;
                JsonElement nameElement = element.getAsJsonObject().get("name");
                if (nameElement == null || !nameElement.isJsonPrimitive())
                    continue;
                String name = nameElement.getAsString();
                if (!name.startsWith("org.dsh.lunar:"))
                    continue;
                if (!libraryInstalled(librariesDir, name)) {
                    if (firstMissing == null)
                        firstMissing = name;
                    missingDeclared++;
                }
            }
        }
        if (missingDeclared > 0)
            problems.add("有 " + missingDeclared + " 个 Lunar 库声明了但文件不在 libraries 下（例如 "
                    + firstMissing + "）：启动器会去官方库下载这个私有坐标而 404，启动失败");

        return problems;
    }

    /// 把这个版本的已知问题就地修掉：改版本 json、补齐运行库、重新对齐 classpath 副本。
    ///
    /// @return 做过的事（空表示体检没问题，什么都没改）
    public static List<String> repair(Path gameDirectory, String versionId) {
        List<String> problems = inspect(gameDirectory, versionId);
        if (problems.isEmpty())
            return List.of();

        List<String> actions = new ArrayList<>();
        Path versionJson = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        try {
            if (ensureLaunchRequirements(versionJson))
                actions.add("已修正启动参数（外部文件名 / 运行库 / Java 要求 / GC 参数 / 基础版本）");
        } catch (Exception e) {
            Logger.LOG.warning("修正 " + versionId + " 的启动参数失败：" + e);
        }
        int synced = refreshClasspathFor(gameDirectory, versionId);
        if (synced > 0)
            actions.add("已重新对齐 " + synced + " 个 classpath 副本");
        return actions;
    }

    /// 运行库是否排在「Lunar 自己的 jar 之后」。
    ///
    /// <p>顺序由安装时决定（见 [#install]），这里只做校验：最后一条 Lunar jar 的位置必须早于
    /// 第一条运行库。</p>
    private static boolean runtimeLibrariesPlacedCorrectly(JsonObject version, JsonArray game, List<Path> jars) {
        if (!version.has("libraries") || !version.get("libraries").isJsonArray())
            return false;

        Set<String> classpathCoordinates = new HashSet<>();
        for (String jarPath : splitArgument(argumentValue(game, "--ichorClassPath"))) {
            Path fileName = Path.of(jarPath).getFileName();
            if (fileName != null)
                classpathCoordinates.add("org.dsh.lunar:" + sanitize(fileName.toString()) + ":1.0");
        }
        Set<String> runtimeCoordinates = new HashSet<>();
        for (Path jar : jars) {
            Path fileName = jar.getFileName();
            if (fileName != null)
                runtimeCoordinates.add("org.dsh.lunar:" + sanitize(fileName.toString()) + ":1.0");
        }

        int lastClasspath = -1;
        int firstRuntime = -1;
        JsonArray libraries = version.getAsJsonArray("libraries");
        for (int index = 0; index < libraries.size(); index++) {
            JsonElement element = libraries.get(index);
            if (!element.isJsonObject() || !element.getAsJsonObject().has("name"))
                continue;
            String name = element.getAsJsonObject().get("name").getAsString();
            if (classpathCoordinates.contains(name))
                lastClasspath = index;
            else if (firstRuntime < 0 && runtimeCoordinates.contains(name))
                firstRuntime = index;
        }
        return lastClasspath >= 0 && firstRuntime > lastClasspath;
    }

    /// 修正已安装 Lunar 版本的启动要求：Java 版本过低（原版 26.x 要 25，之前一律声明 21 会让启动器
    /// 用 Java 21 执行带 `--sun-misc-unsafe-memory-access` 的命令行而秒退），以及 Lunar 清单带进来的
    /// GC 参数与启动器自带 GC 冲突（`Multiple garbage collectors selected`）。都就地修好，不用重装。
    public static void repairInstalledVersions(Path gameDirectory) {
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory))
            return;

        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                Path fileName = directory.getFileName();
                if (fileName == null || !fileName.toString().startsWith("Lunar-"))
                    continue;

                Path versionJson = directory.resolve(fileName + ".json");
                try {
                    if (ensureLaunchRequirements(versionJson))
                        Logger.LOG.info("已修复 " + fileName + " 的启动要求");
                } catch (Exception e) {
                    Logger.LOG.warning("修复 " + fileName + " 的启动要求失败：" + e);
                }
            }
        } catch (IOException e) {
            Logger.LOG.warning("扫描已安装 Lunar 版本失败：" + e);
        }
    }

    /// 启动前对齐某一个 Lunar 版本的 classpath 副本。
    ///
    /// <p>必须在这里做：Lunar 客户端运行时会自我更新客户端目录里的 SNAPSHOT jar，而版本 json 的
    /// classpath 用的是 `libraries` 里的副本。若不在 JVM 打开这些 jar **之前**同步好，新 mixin jar
    /// 配旧核心 jar 就会让 Mixin 初始化崩掉（`@Mixin target com.mojang.math.Matrix3f was not found`）。
    /// 由 [LunarOfflinePlugin#onBeforeLaunch] 在每次启动前调用。
    ///
    /// @return 实际重新复制了几个文件（0 表示本来就一致）
    public static int refreshClasspathFor(Path gameDirectory, String versionId) {
        Path versionJson = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(versionJson))
            return 0;

        try {
            return refreshClasspath(versionJson, gameDirectory);
        } catch (Exception e) {
            Logger.LOG.warning("启动前同步 " + versionId + " 的 classpath 失败：" + e);
            return 0;
        }
    }

    /// 把每个已安装 Lunar 版本的客户端 jar 重新同步到 `libraries/org/dsh/lunar/...`。
    ///
    /// <p>版本 json 的 classpath 走的是 `libraries` 里的副本，而 Lunar 客户端自己的 SNAPSHOT jar 会
    /// 在客户端目录里自我更新。只要某一次更新只复制了一部分（例如那些大 jar 当时正被运行中的客户端
    /// 占用，复制失败），两份就会新旧混用：Mixin 配置与核心 jar 不匹配，启动时直接崩在
    /// `@Mixin target com.mojang.math.Matrix3f was not found` 这类错误上。这里在插件启用时重新对齐，
    /// 用户不必重装；个别文件仍被占用时只记录告警，不影响其它版本。</p>
    public static void refreshInstalledClasspaths(Path gameDirectory) {
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory))
            return;

        try (var stream = Files.list(versionsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                Path fileName = directory.getFileName();
                if (fileName == null || !fileName.toString().startsWith("Lunar-"))
                    continue;
                refreshClasspathFor(gameDirectory, fileName.toString());
            }
        } catch (IOException e) {
            Logger.LOG.warning("扫描已安装 Lunar 版本失败：" + e);
        }
    }

    /// 对齐单个版本的 classpath 副本。
    ///
    /// @return 实际重新复制了几个文件
    private static int refreshClasspath(Path versionJson, Path gameDirectory) throws IOException {
        JsonObject version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (!version.has("arguments") || !version.get("arguments").isJsonObject())
            return 0;

        int synced = 0;
        JsonArray game = version.getAsJsonObject("arguments").getAsJsonArray("game");
        for (String jarPath : splitArgument(argumentValue(game, "--ichorClassPath"))) {
            Path source = Path.of(jarPath);
            if (!Files.isRegularFile(source))
                continue;

            Path target = classpathTarget(gameDirectory.resolve("libraries"), source);
            long size;
            try {
                size = Files.size(source);
            } catch (IOException e) {
                continue;
            }
            if (Files.isRegularFile(target) && Files.size(target) == size)
                continue;
            if (!Files.isRegularFile(target) && !Files.isDirectory(target.getParent()))
                continue; // libraries 里没有这个坐标，说明不是我们装的

            try {
                copyIfNeeded(source, target);
                synced++;
                Logger.LOG.info("已同步 Lunar classpath 副本：" + target.getFileName());
            } catch (IOException e) {
                Logger.LOG.warning("无法更新 " + target.getFileName()
                        + "（可能有一个 Lunar 客户端正在运行）：" + e.getMessage());
            }
        }
        return synced;
    }

    /// 客户端目录里的 jar 在 `libraries/` 下对应的坐标路径。
    private static Path classpathTarget(Path librariesDir, Path jar) {
        Path fileName = jar.getFileName();
        String artifact = sanitize(fileName == null ? "" : fileName.toString());
        return librariesDir.resolve("org/dsh/lunar").resolve(artifact).resolve("1.0")
                .resolve(artifact + "-1.0.jar");
    }

    /// 一个 `group:artifact:version[:classifier]` 坐标在 `libraries/` 下是否真有 jar 文件。
    ///
    /// <p>HMCL 判断「库缺失」就是看这个路径在不在（`GameLibrariesTask.shouldDownloadLibrary`），
    /// 缺了就去 Maven 仓库下载 —— 对 `org.dsh.lunar` 这种私有坐标必然 404，所以声明前必须核对。</p>
    private static boolean libraryInstalled(Path librariesDir, String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3)
            return false;
        Path jar = librariesDir
                .resolve(parts[0].replace('.', '/'))
                .resolve(parts[1])
                .resolve(parts[2])
                .resolve(parts[1] + "-" + parts[2]
                        + (parts.length > 3 && !parts[3].isBlank() ? "-" + parts[3] : "") + ".jar");
        return Files.isRegularFile(jar);
    }

    /// 把坐标里的字符规范成合法的 maven 片段。
    private static String sanitize(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar"))
            name = name.substring(0, name.length() - 4);
        return name.replaceAll("[^a-z0-9._-]", "-");
    }

    /// 原子写版本 json：先写同目录的临时文件，再改名覆盖。
    ///
    /// <p>启动器的版本仓库随时可能在另一个线程里读这些 json —— 插件修版本时，它往往正好在刷新版本
    /// 列表。`Files.writeString` 会先把文件截断，读到半个文件的启动器会当成坏 json 直接跳过
    /// （`Malformed version json`），或者因为壳版本没解析出来而报
    /// `ignores version ... because it inherits from a nonexistent version`，版本就从列表里消失了。
    /// 改成「临时文件 + 原子改名」后，读到的要么是旧内容、要么是新内容，不会读到半个。</p>
    private static void writeJsonAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// 目标已存在且大小一致时跳过，返回实际复制的字节数。
    private static long copyIfNeeded(Path source, Path target) throws IOException {
        long size = Files.size(source);
        if (Files.isRegularFile(target) && Files.size(target) == size)
            return 0;
        Files.createDirectories(target.getParent());
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 运行中的 Lunar 客户端会占住 classpath 上的 jar —— 只复制了一半的 classpath 会让
            // Mixin 初始化崩掉，所以这里必须把原因说清楚，而不是留下一个「半新半旧」的安装
            throw new IOException("无法更新 " + target.getFileName()
                    + "（可能有一个 Lunar 客户端正在运行，请先关闭它再重试）", e);
        }
        return size;
    }

    private static String required(JsonObject object, String key) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull())
            throw new IOException("启动数据缺少字段：" + key);
        return object.get(key).getAsString();
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> result = new ArrayList<>();
        if (object.has(key) && object.get(key).isJsonArray())
            for (var element : object.getAsJsonArray(key))
                if (element.isJsonPrimitive())
                    result.add(element.getAsString());
        return result;
    }
}
