package org.dsh.moddeps;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.plugin.GameCrashContext;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginAddonEvent;
import org.jackhuang.hmcl.plugin.PluginAddonListener;
import org.jackhuang.hmcl.plugin.PluginAddonService;
import org.jackhuang.hmcl.plugin.PluginCrashListener;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.TaskCancellationAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModDepsPlugin extends HmclPlugin {

    private final PluginAddonListener listener = this::onAddonDownloaded;
    private final PluginCrashListener crashListener = this::onGameCrash;

    @Override
    public void onEnable() {
        PluginAddonService.registerListener(listener);
        PluginMenuService.registerCrashListener(crashListener);
        getContext().logInfo("前置 Mod 自动下载已启用");
    }

    @Override
    public void onDisable() {
        PluginMenuService.unregisterCrashListener(crashListener);
        PluginAddonService.unregisterListener(listener);
        getContext().logInfo("前置 Mod 自动下载已停用");
    }

    // trigger 1: after mod download completes
    private void onAddonDownloaded(PluginAddonEvent event) {
        Path directory = event.directory();
        if (directory.getFileName() == null || !"mods".equals(directory.getFileName().toString())) return;
        String instanceId = event.instanceId();
        if (instanceId == null) return;

        HMCLGameRepository repository = event.repository();
        String gameVersion;
        Set<ModLoaderType> loaders;
        try {
            Version v = repository.getResolvedPreservingPatchesVersion(instanceId);
            gameVersion = repository.getGameVersion(v).orElse(null);
            if (gameVersion == null) return;
            loaders = LibraryAnalyzer.analyze(v, gameVersion).getModLoaders();
        } catch (Exception e) {
            getContext().logError("读取实例信息失败", e);
            return;
        }
        if (loaders.isEmpty()) return;

        DownloadProvider dp = event.downloadProvider();
        RemoteAddon.Version root = event.addonVersion();
        Schedulers.io().execute(() -> {
            List<RemoteAddon.Version> plan;
            try {
                plan = DependencyResolver.resolve(DependencyResolver.remoteSource(dp), root, gameVersion, loaders,
                        installedCheck(directory, repository, instanceId), m -> getContext().logWarning(m));
            } catch (Throwable e) {
                getContext().logError("解析前置失败", e);
                return;
            }
            if (plan.isEmpty()) {
                getContext().logInfo(root.name() + " 没有需要补装的前置");
                return;
            }
            String names = plan.stream().map(RemoteAddon.Version::name).reduce((a, b) -> a + "\u3001 " + b).orElse("");
            getContext().logInfo(root.name() + " 缺失 " + plan.size() + " 个前置: " + names);
            Schedulers.javafx().execute(() -> Controllers.confirm(
                    "下载「" + root.name() + "」时检测到 " + plan.size() + " 个缺失的前置 Mod：\n" + names
                            + "\n\n是否自动下载这些前置？",
                    "补全前置 Mod",
                    () -> showDownloadDialog(dp, directory, plan, List.of()), null));
        });
    }

    // trigger 2: game crash
    private void onGameCrash(GameCrashContext context) {
        Path modsDir = context.getModsDirectory();
        DefaultGameRepository repo = context.getRepository();
        String instanceId = context.getVersionId();

        String gameVersion;
        Set<ModLoaderType> loaders;
        try {
            Version v = repo.getResolvedPreservingPatchesVersion(instanceId);
            gameVersion = repo.getGameVersion(v).orElse(null);
            if (gameVersion == null) return;
            loaders = LibraryAnalyzer.analyze(v, gameVersion).getModLoaders();
        } catch (Throwable e) {
            getContext().logWarning("崩溃扫描：读取实例信息失败 - " + e);
            return;
        }
        if (loaders.isEmpty()) return;

        String latest = context.getLatestLog();
        String console = context.getLog();
        StringBuilder sb = new StringBuilder();
        if (latest != null && !latest.isBlank()) sb.append(latest).append("\n");
        if (console != null && !console.isBlank()) sb.append(console);
        String logText = sb.toString();
        if (logText.isBlank()) {
            getContext().logInfo("崩溃扫描：没有可读的日志文本");
            return;
        }

        List<MissingModScanner.MissingMod> missing = MissingModScanner.scan(logText, loaders);
        if (missing.isEmpty()) {
            getContext().logInfo("崩溃扫描：日志中没有发现缺失前置的特征");
            return;
        }
        // No filename-based pre-filter here: the log is authoritative evidence that the
        // prerequisite is unusable, so the user is always asked. Whether the exact file is
        // already present is decided per resolved version below.
        getContext().logInfo("崩溃扫描：疑似缺失前置 " + missing);
        getContext().logInfo("崩溃扫描：开始解析 " + missing.size() + " 个缺失前置");

        Schedulers.io().execute(() -> {
            try {
                DownloadProvider dp = DownloadProviders.getDownloadProvider();
                getContext().logInfo("崩溃扫描：DownloadProvider 获取成功");
                CrashPlan crashPlan = planForCrash(missing, gameVersion, loaders, modsDir,
                        id -> {
                            try {
                                return fetchVersions(id, dp);
                            } catch (Throwable e) {
                                getContext().logWarning("崩溃扫描：解析 " + id + " 失败 - " + e);
                                return List.of();
                            }
                        },
                        m -> getContext().logInfo("崩溃扫描：" + m));
                List<Planned> planned = crashPlan.downloadable();
                List<String> unresolved = crashPlan.unresolved();
                List<RemoteAddon.Version> plan = planned.stream().map(Planned::version).toList();
                // A different build of the same mod is installed; installing the required one
                // alongside it would crash Fabric with a duplicate mod, so disable the old one.
                Map<String, List<Path>> installedById = localModsById(repo, instanceId);
                List<Path> outdated = new ArrayList<>();
                for (Planned p : planned) {
                    for (Path installed : installedById.getOrDefault(p.id().toLowerCase(Locale.ROOT), List.of())) {
                        if (!installed.getFileName().toString().equals(p.version().file().filename()))
                            outdated.add(installed);
                    }
                }
                // A jar whose metadata the launcher cannot parse still has to be switched out,
                // otherwise the freshly downloaded build lands next to it as a duplicate mod.
                outdated.addAll(outdatedByFilename(modsDir, planned, outdated));
                if (plan.isEmpty()) {
                    getContext().logInfo("崩溃扫描：没有可自动下载的前置");
                    Schedulers.javafx().execute(() -> Controllers.dialog(
                            "检测到缺失前置 Mod：" + missing.stream().map(MissingModScanner.MissingMod::id).reduce((a, b) -> a + "\u3001 " + b).orElse("")
                                    + "\n\n但自动下载失败（可能是网络原因或无法匹配版本）。\n请手动从 Mod 下载页安装这些前置，然后重新启动游戏。",
                            "缺少前置 Mod", MessageDialogPane.MessageType.WARNING));
                    return;
                }
                List<RemoteAddon.Version> finalPlan = plan;
                List<Planned> finalPlanned = List.copyOf(planned);
                List<String> finalUnresolved = List.copyOf(unresolved);
                List<Path> finalOutdated = List.copyOf(outdated);
                Map<String, MissingModScanner.MissingMod> byId = new HashMap<>();
                for (MissingModScanner.MissingMod mm : missing) byId.put(mm.id().toLowerCase(Locale.ROOT), mm);
                Schedulers.javafx().execute(() -> {
                    StringBuilder changes = new StringBuilder();
                    for (Planned p : finalPlanned) {
                        MissingModScanner.MissingMod mm = byId.get(p.id().toLowerCase(Locale.ROOT));
                        String installed = mm == null ? null : mm.installedVersion();
                        if (changes.length() > 0) changes.append('\n');
                        changes.append(installed != null
                                ? p.version().name() + "（从 " + installed + " 切换到 " + p.version().version() + "）"
                                : p.version().name());
                    }
                    String note = finalUnresolved.isEmpty() ? ""
                            : "\n\n以下前置暂时无法自动匹配版本，请到「下载」页面手动搜索安装："
                            + String.join("\u3001 ", finalUnresolved);
                    String replace = finalOutdated.isEmpty() ? ""
                            : "\n\n将要停用的旧版本（重命名为 .disabled，可手动恢复）："
                            + finalOutdated.stream().map(p -> p.getFileName().toString())
                                    .reduce((a, b) -> a + "\u3001 " + b).orElse("");
                    Controllers.confirm(
                            "游戏因模组版本不匹配或缺少前置而崩溃。将自动按报错切换/补装以下模组：\n"
                                    + changes + replace + note
                                    + "\n\n是否立即执行？",
                            "按报错修复模组版本",
                            () -> showDownloadDialog(dp, modsDir, finalPlan, finalOutdated), null);
                });
            } catch (Throwable e) {
                getContext().logError("崩溃扫描：解析环节异常", e);
                Schedulers.javafx().execute(() -> Controllers.dialog(
                        "自动解析缺失前置时出错：" + e.getMessage() + "\n请手动从 Mod 下载页安装缺失的前置。",
                        "缺少前置 Mod", MessageDialogPane.MessageType.WARNING));
            }
        });
    }

    /// Matches a candidate release against the version the game asked for. Fabric's
    /// `add:<id> <version>` hint carries a build suffix (`0.9.2+mc26.3`) that remote version
    /// numbers spell differently, so compare the base version and also accept a filename
    /// that carries it — otherwise we silently install a different build than the one the
    /// log demands.
    public static boolean pinnedMatches(String version, String filename, String pinned) {
        if (pinned == null || pinned.isBlank()) return false;
        String[] parts = pinned.split("\\+", 2);
        String base = parts[0].trim().toLowerCase(Locale.ROOT);
        String build = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String ver = version == null ? "" : version.toLowerCase(Locale.ROOT);

        // Modrinth numbers a release "mc26.3-0.9.2-fabric" while its file is
        // "sodium-fabric-0.9.2+mc26.3.jar", so the base version has to be looked for in both.
        boolean baseHit = !base.isEmpty()
                && (name.contains(base) || ver.equals(base) || ver.startsWith(base)
                    || ver.contains("-" + base) || ver.contains("+" + base));
        if (!baseHit) return false;
        // The build suffix is the game version; requiring it keeps us from installing the
        // right mod version for the wrong Minecraft release.
        return build.isEmpty() || name.contains(build) || ver.contains(build);
    }

    /// A prerequisite to download, together with the mod id the crash log named.
    public record Planned(String id, RemoteAddon.Version version) {
    }

    /// What a crash's missing prerequisites resolve to: what can be downloaded right now and
    /// what the user has to install by hand. A non-empty [downloadable] is what makes the
    /// launcher raise the "download the prerequisites?" prompt.
    public record CrashPlan(List<Planned> downloadable, List<String> unresolved) {
    }

    /// Decides the download plan for a missing-prerequisite crash. The fetcher is injected so
    /// this stays testable without the launcher or the network.
    public static CrashPlan planForCrash(List<MissingModScanner.MissingMod> missing, String gameVersion,
                                         Set<ModLoaderType> loaders, Path modsDir,
                                         Function<String, List<RemoteAddon.Version>> fetch,
                                         Consumer<String> log) {
        List<Planned> plan = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (MissingModScanner.MissingMod mm : missing) {
            log.accept("正在解析 " + mm.id());
            List<RemoteAddon.Version> versions;
            try {
                versions = fetch.apply(mm.id());
            } catch (Throwable e) {
                log.accept("解析 " + mm.id() + " 失败 - " + e);
                unresolved.add(mm.id());
                continue;
            }
            Optional<RemoteAddon.Version> picked = pickVersion(versions, mm.pinnedVersion(), gameVersion, loaders);
            if (picked.isEmpty()) {
                log.accept("未找到 " + mm.id() + " 的适配版本");
                unresolved.add(mm.id());
                continue;
            }
            RemoteAddon.Version version = picked.get();
            log.accept("解析到 " + mm.id() + " -> " + version.name());
            // Only "this very file is already here" counts as satisfied: the game just said the
            // installed build is unusable, so an installed mod id must not silence the prompt.
            if (modFileExists(modsDir, version.file().filename()))
                log.accept(mm.id() + " 同名文件已存在，跳过");
            else
                plan.add(new Planned(mm.id(), version));
        }
        return new CrashPlan(plan, unresolved);
    }

    /// Picks the release to install: the version the log pinned when it exists, else the newest
    /// one that matches the running game version and loader.
    public static Optional<RemoteAddon.Version> pickVersion(List<RemoteAddon.Version> versions, String pinnedVersion,
                                                            String gameVersion, Set<ModLoaderType> loaders) {
        if (versions == null || versions.isEmpty()) return Optional.empty();
        // Modrinth ships one file per loader (sodium-fabric vs sodium-neoforge), so the
        // environment filter comes first: the pin says *which version*, not which loader.
        List<RemoteAddon.Version> compatible = versions.stream()
                .filter(v -> v.gameVersions().isEmpty() || v.gameVersions().contains(gameVersion))
                .filter(v -> v.loaders().isEmpty() || v.loaders().stream().anyMatch(loaders::contains))
                .toList();
        // Nothing matches the running environment: still honour the pin rather than giving up,
        // because the user is asked before anything is downloaded.
        List<RemoteAddon.Version> pool = compatible.isEmpty() ? versions : compatible;
        if (pinnedVersion != null && !pinnedVersion.isBlank()) {
            Optional<RemoteAddon.Version> pinned = pool.stream()
                    .filter(v -> pinnedMatches(v.version(), v.file().filename(), pinnedVersion))
                    .max(Comparator.comparing(RemoteAddon.Version::datePublished));
            if (pinned.isPresent()) return pinned;
        }
        return pool.stream().max(Comparator.comparing(RemoteAddon.Version::datePublished));
    }

    private List<RemoteAddon.Version> fetchVersions(String id, DownloadProvider dp) throws IOException {
        List<RemoteAddon.Version> versions = null;
        try { versions = ModrinthRemoteAddonRepository.MODS.getRemoteVersionsById(dp, id).toList(); } catch (Throwable ignored) {}
        if (versions == null || versions.isEmpty()) {
            try { versions = CurseForgeRemoteAddonRepository.MODS.getRemoteVersionsById(dp, id).toList(); } catch (Throwable ignored) {}
        }
        if (versions == null || versions.isEmpty()) {
            try {
                var result = CurseForgeRemoteAddonRepository.MODS.search(dp, null, null, 0, 5, id,
                        RemoteAddonRepository.SortType.POPULARITY, RemoteAddonRepository.SortOrder.DESC);
                Optional<RemoteAddon> match = result.getResults()
                        .filter(a -> a.slug() != null && a.slug().equalsIgnoreCase(id))
                        .findFirst().or(() -> result.getResults().findFirst());
                if (match.isPresent()) {
                    RemoteAddon addon = match.get();
                    String repoId = addon.data().loadVersions(CurseForgeRemoteAddonRepository.MODS, dp)
                            .map(RemoteAddon.Version::modid)
                            .filter(m -> m != null && !m.isBlank())
                            .findFirst().orElseGet(() -> addon.slug());
                    versions = CurseForgeRemoteAddonRepository.MODS.getRemoteVersionsById(dp, repoId).toList();
                }
            } catch (Throwable ignored) {}
        }
        return versions == null ? List.of() : versions;
    }

    private BiPredicate<String, RemoteAddon.Version> installedCheck(Path directory, DefaultGameRepository repository, String instanceId) {
        Set<String> installedModIds = new HashSet<>();
        try {
            ModManager mm = new ModManager(repository, instanceId);
            for (LocalModFile f : mm.getLocalFiles()) {
                if (f.getId() != null) installedModIds.add(f.getId().toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            getContext().logWarning("读取已安装 Mod 列表失败，仅按文件名判断: " + e);
        }
        return (slug, version) -> modFileExists(directory, version.file().filename())
                || (slug != null && installedModIds.contains(slug.toLowerCase(Locale.ROOT)));
    }

    private static boolean modFileExists(Path directory, String fileName) {
        return Files.exists(directory.resolve(fileName)) || Files.exists(directory.resolve(fileName + ".disabled"));
    }

    /// Older builds of a planned download found by file name, for jars whose metadata the
    /// launcher cannot read. The stem is everything before the first version-looking token,
    /// so "sodium-fabric-0.9.3-alpha.1+mc26.3.jar" is recognised as "sodium-fabric-*" and
    /// switched out when "sodium-fabric-0.9.2+mc26.3.jar" is installed.
    public static List<Path> outdatedByFilename(Path modsDir, List<Planned> planned, List<Path> alreadyFound) {
        List<Path> extra = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return extra;
        for (Planned p : planned) {
            String stem = modStem(p.version().file().filename());
            if (stem.isEmpty()) continue;
            try (var stream = Files.list(modsDir)) {
                for (Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    // Equal stems, not a prefix test: "sodium-" must not swallow "sodium-extra-".
                    if (!modStem(name).equals(stem)
                            || !name.toLowerCase(Locale.ROOT).contains(".jar")
                            || name.equals(p.version().file().filename())
                            || alreadyFound.contains(file)
                            || extra.contains(file))
                        continue;
                    extra.add(file);
                }
            } catch (IOException e) {
                // listing failed; the id-based check above is all we have
            }
        }
        return extra;
    }

    /// "sodium-fabric-0.9.3-alpha.1+mc26.3.jar" -> "sodium-fabric-"; empty when the name has
    /// no version-looking token.
    public static String modStem(String filename) {
        if (filename == null) return "";
        String name = filename.toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("^(.*?)[-_.](?=\\d)").matcher(name);
        return matcher.find() ? matcher.group(1) + "-" : "";
    }

    /// Installed mod files grouped by mod id (lower-cased), so an outdated build can be
    /// disabled before the required one lands next to it.
    private Map<String, List<Path>> localModsById(DefaultGameRepository repository, String instanceId) {
        Map<String, List<Path>> byId = new HashMap<>();
        try {
            for (LocalModFile f : new ModManager(repository, instanceId).getLocalFiles()) {
                if (f.getId() == null) continue;
                byId.computeIfAbsent(f.getId().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(f.getFile());
            }
        } catch (Exception e) {
            getContext().logWarning("读取已安装 Mod 列表失败: " + e);
        }
        return byId;
    }

    private void showDownloadDialog(DownloadProvider dp, Path directory, List<RemoteAddon.Version> plan,
                                    List<Path> outdated) {
        if (outdated.isEmpty()) {
            startDownloads(dp, directory, plan, List.of());
            return;
        }
        Schedulers.io().execute(() -> {
            List<String> disabled = new ArrayList<>();
            for (Path file : outdated) {
                try {
                    Files.move(file, file.resolveSibling(file.getFileName().toString() + ".disabled"),
                            StandardCopyOption.REPLACE_EXISTING);
                    disabled.add(file.getFileName().toString());
                } catch (Exception e) {
                    getContext().logWarning("停用旧版本失败 " + file + " - " + e);
                }
            }
            getContext().logInfo("崩溃扫描：已停用旧版本 " + disabled);
            Schedulers.javafx().execute(() -> startDownloads(dp, directory, plan, disabled));
        });
    }

    private void startDownloads(DownloadProvider dp, Path directory, List<RemoteAddon.Version> plan,
                                List<String> disabled) {
        List<FileDownloadTask> tasks = new ArrayList<>();
        for (RemoteAddon.Version version : plan) {
            FileDownloadTask task = new FileDownloadTask(
                    dp.injectURLWithCandidates(version.file().url()),
                    directory.resolve(version.file().filename()),
                    version.file().getIntegrityCheck());
            task.setName(version.name());
            tasks.add(task);
        }
        Task<List<Void>> all = Task.allOf(tasks);
        all.setName("下载前置 Mod");
        Task<Void> task = all.whenComplete(Schedulers.javafx(), exception -> {
            if (exception == null) {
                String suffix = disabled.isEmpty() ? "" : "（已停用旧版本 " + String.join("、", disabled) + "）";
                Controllers.showToast("已自动下载 " + plan.size() + " 个前置 Mod" + suffix);
            } else if (exception instanceof CancellationException) {
                Controllers.showToast("已取消下载前置 Mod");
            } else {
                Controllers.dialog("下载前置 Mod 失败: " + exception.getMessage(), "前置 Mod",
                        MessageDialogPane.MessageType.ERROR);
            }
        });
        Controllers.taskDialog(task, "下载前置 Mod", TaskCancellationAction.NORMAL);
    }
}
