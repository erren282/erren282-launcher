package org.dsh.modinstall;

import javafx.scene.image.Image;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.FileDownloadTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The work behind the dialog: which installed versions the dropped mods fit,
 * which prerequisites are missing, and how to install or update them.
 *
 * <p>Deliberately holds no JavaFX state so the rules stay testable; the dialog
 * calls into this and reports the outcome.</p>
 */
final class ModInstallerEngine {

    /** An installed game version, with what it takes to put a mod into it. */
    record Installed(String id, String gameVersion, Set<ModLoaderType> loaders, Path modsDirectory) {

        /** The list row's title: the instance id. */
        String describe() {
            return id;
        }

        /**
         * The list row's second line: the game version and the loaders, skipping
         * the parts that would merely repeat the id.
         */
        String subtitle() {
            List<String> parts = new ArrayList<>();
            if (gameVersion != null && !gameVersion.isBlank() && !gameVersion.equals(id)) {
                parts.add("游戏版本 " + gameVersion);
            }
            if (!loaders.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (ModLoaderType loader : loaders) {
                    names.add(nameOf(loader));
                }
                parts.add(String.join("/", names));
            }
            return parts.isEmpty() ? "未检测到加载器" : String.join("  ·  ", parts);
        }
    }

    private ModInstallerEngine() {
    }

    /** Human-readable loader name, shared with the dialog. */
    static String nameOf(ModLoaderType loader) {
        if (loader == null) {
            return "未知加载器";
        }
        return switch (loader) {
            case FABRIC -> "Fabric";
            case QUILT -> "Quilt";
            case NEO_FORGE -> "NeoForge";
            case FORGE -> "Forge";
            case LITE_LOADER -> "LiteLoader";
            case LEGACY_FABRIC -> "Legacy Fabric";
            case CLEANROOM -> "Cleanroom";
            default -> "未知加载器";
        };
    }

    /** Every installed version, read the way the launcher reads them. */
    static List<Installed> installedVersions() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            return List.of();
        }
        List<Installed> result = new ArrayList<>();
        for (Version version : new ArrayList<>(repository.getVersions())) {
            String gameVersion = repository.getGameVersion(version).orElse(null);
            Set<ModLoaderType> loaders;
            try {
                loaders = LibraryAnalyzer.analyze(version, gameVersion).getModLoaders();
            } catch (Throwable t) {
                loaders = Set.of();
            }
            Path mods = repository.getBaseDirectory()
                    .resolve("versions").resolve(version.getId()).resolve("mods");
            result.add(new Installed(version.getId(), gameVersion, loaders, mods));
        }
        // Newest game version first: that is almost always the one the user wants.
        result.sort(Comparator.comparing(
                (Installed installed) -> installed.gameVersion() == null ? "" : installed.gameVersion(),
                McVersionRange::compare).reversed());
        return result;
    }

    /**
     * The installed versions every mod in the drop fits: the version must carry
     * the mod's loader and be inside the mod's declared game versions.
     */
    static List<Installed> matching(List<Installed> installed, List<ModJarInfo.Info> mods) {
        List<Installed> result = new ArrayList<>();
        for (Installed candidate : installed) {
            boolean fits = true;
            for (ModJarInfo.Info mod : mods) {
                if (!accepts(candidate, mod)) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** True when one installed version can take one mod. */
    static boolean accepts(Installed candidate, ModJarInfo.Info mod) {
        if (mod.loader() != ModLoaderType.UNKNOWN && !candidate.loaders().contains(mod.loader())) {
            return false;
        }
        String gameVersion = candidate.gameVersion();
        if (gameVersion == null || gameVersion.isBlank()) {
            // A version whose game version cannot be determined: offer it rather
            // than hide the user's install.
            return true;
        }
        return mod.supportsMcVersion(gameVersion);
    }

    /** The required mods of {@code mod} that are not present in {@code target}. */
    static List<ModJarInfo.Dependency> missingDependencies(Installed target,
                                                           List<ModJarInfo.Info> mods) {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            return List.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        List<ModJarInfo.Dependency> result = new ArrayList<>();
        try {
            ModManager manager = new ModManager(repository, target.id());
            manager.refresh();
            for (ModJarInfo.Info mod : mods) {
                for (ModJarInfo.Dependency dependency : mod.requiredMods()) {
                    ModLoaderType loader = mod.loader() == ModLoaderType.UNKNOWN
                            ? ModLoaderType.UNKNOWN : mod.loader();
                    boolean present = loader == ModLoaderType.UNKNOWN
                            ? manager.getLocalMod(dependency.id(), ModLoaderType.FORGE) != null
                            || manager.getLocalMod(dependency.id(), ModLoaderType.FABRIC) != null
                            || manager.getLocalMod(dependency.id(), ModLoaderType.NEO_FORGE) != null
                            : manager.hasMod(dependency.id(), loader);
                    // A mod that itself is part of this drop counts as provided.
                    boolean inDrop = mods.stream().anyMatch(other ->
                            dependency.id().equalsIgnoreCase(other.id()));
                    if (!present && !inDrop && missing.add(dependency.id())) {
                        result.add(dependency);
                    }
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        return result;
    }

    /** Copies the dropped files into the chosen version's mods folder. */
    static int install(Installed target, List<Path> files) {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            return 0;
        }
        int installed = 0;
        try {
            ModManager manager = new ModManager(repository, target.id());
            for (Path file : files) {
                try {
                    manager.addMod(file);
                    installed++;
                } catch (IOException | IllegalArgumentException e) {
                    // Keep going: one bad file must not lose the others.
                }
            }
        } catch (Throwable t) {
            return installed;
        }
        return installed;
    }

    /**
     * The versions to offer when the user chooses to update a dropped mod: the
     * two newest test builds plus the newest stable build, for the chosen game
     * version and the mod's loader.
     *
     * <p>"Test builds" are Modrinth's beta/alpha releases, "stable" is a release.
     * Returns fewer entries when the project does not have that many, and an empty
     * list when nothing fits.</p>
     */
    static List<RemoteAddon.Version> updateCandidates(ModJarInfo.Info mod, String gameVersion) {
        if (mod == null || mod.file() == null) {
            return List.of();
        }
        try {
            ModrinthRemoteAddonRepository repository = ModrinthRemoteAddonRepository.MODS;
            Optional<RemoteAddon.Version> current =
                    repository.getRemoteVersionByLocalFile(mod.file());
            if (current.isEmpty()) {
                return List.of();
            }
            DownloadProvider provider = downloadProvider();
            RemoteAddon addon = repository.getModById(provider, current.get().modid());
            List<RemoteAddon.Version> all = addon.data().loadVersions(repository, provider)
                    .filter(candidate -> candidate.file() != null)
                    .filter(candidate -> mod.loader() == ModLoaderType.UNKNOWN
                            || candidate.loaders().contains(mod.loader()))
                    .filter(candidate -> gameVersion == null || gameVersion.isBlank()
                            || candidate.gameVersions().contains(gameVersion))
                    .toList();

            Comparator<RemoteAddon.Version> newestFirst = Comparator
                    .comparing((RemoteAddon.Version v) -> v.datePublished() == null
                            ? java.time.Instant.EPOCH : v.datePublished())
                    .reversed();

            return pickUpdateCandidates(all);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Identifies a dropped mod on Modrinth by its SHA-1, for opening its page.
     *
     * @return the addon, or null when the file is not on Modrinth
     */
    static RemoteAddon resolveAddon(ModJarInfo.Info mod) {
        if (mod == null || mod.file() == null) {
            return null;
        }
        try {
            ModrinthRemoteAddonRepository repository = ModrinthRemoteAddonRepository.MODS;
            Optional<RemoteAddon.Version> current =
                    repository.getRemoteVersionByLocalFile(mod.file());
            if (current.isEmpty()) {
                return null;
            }
            return repository.getModById(downloadProvider(), current.get().modid());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Picks the versions to offer: the two newest test builds plus the newest
     * stable build.
     *
     * <p>Split out from {@link #updateCandidates} so the rule is testable without a
     * network call. Ordered by publish date rather than by version string, since
     * version strings are not comparable across projects ({@code v9} can predate
     * {@code v1}).</p>
     *
     * @param all every candidate that already fits the game version and the loader
     */
    static List<RemoteAddon.Version> pickUpdateCandidates(List<RemoteAddon.Version> all) {
        Comparator<RemoteAddon.Version> newestFirst = Comparator
                .comparing((RemoteAddon.Version v) -> v.datePublished() == null
                        ? java.time.Instant.EPOCH : v.datePublished())
                .reversed();
        List<RemoteAddon.Version> result = new ArrayList<>();
        all.stream()
                .filter(v -> v.versionType() != RemoteAddon.VersionType.Release)
                .sorted(newestFirst)
                .limit(2)
                .forEach(result::add);
        all.stream()
                .filter(v -> v.versionType() == RemoteAddon.VersionType.Release)
                .sorted(newestFirst)
                .limit(1)
                .forEach(result::add);
        return result;
    }
    /** A short label for a version type, shown next to a candidate. */
    static String versionTypeLabel(RemoteAddon.VersionType type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case Release -> "稳定版";
            case Beta -> "测试版";
            case Alpha -> "内测版";
        };
    }

    /** The download provider the launcher is configured to use. */
    static DownloadProvider downloadProvider() {
        return DownloadProviders.getDownloadProvider();
    }

    /**
     * The launcher's own icon for an installed version.
     *
     * <p>{@code HMCLGameRepository.getVersionIconImage} is what the launcher's
     * version list draws: the loader's logo when the user picked one, a custom
     * image when they set one, and the default otherwise. Reusing it keeps this
     * dialog's rows visually identical to the version list.</p>
     *
     * @return the icon, or null when it cannot be read
     */
    static Image versionIcon(String versionId) {
        try {
            HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            return repository == null ? null : repository.getVersionIconImage(versionId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Looks for a newer build of a dropped mod on Modrinth.
     *
     * <p>The dropped jar is identified by its SHA-1, which is the only way to map
     * a local file back to a remote project. CurseForge would need an API key
     * that this launcher build does not carry, so only Modrinth is consulted; when
     * the file is not there, no update card is shown and the drop installs as
     * usual.</p>
     *
     * @param gameVersion the game version the user is installing for
     */
    static Optional<RemoteAddon.Version> findUpdate(ModJarInfo.Info mod, String gameVersion) {
        if (mod == null || mod.file() == null) {
            return Optional.empty();
        }
        try {
            ModrinthRemoteAddonRepository repository = ModrinthRemoteAddonRepository.MODS;
            Optional<RemoteAddon.Version> current = repository.getRemoteVersionByLocalFile(mod.file());
            if (current.isEmpty()) {
                return Optional.empty();
            }
            RemoteAddon.Version local = current.get();
            DownloadProvider provider = downloadProvider();
            RemoteAddon addon = repository.getModById(provider, local.modid());
            return addon.data().loadVersions(repository, provider)
                    .filter(candidate -> candidate.datePublished() != null
                            && local.datePublished() != null
                            && candidate.datePublished().isAfter(local.datePublished()))
                    .filter(candidate -> mod.loader() == ModLoaderType.UNKNOWN
                            || candidate.loaders().contains(mod.loader()))
                    .filter(candidate -> gameVersion == null || gameVersion.isBlank()
                            || candidate.gameVersions().contains(gameVersion))
                    .max(Comparator.comparing(RemoteAddon.Version::datePublished));
        } catch (Throwable t) {
            // Offline, or the project is not on Modrinth: no update card.
            return Optional.empty();
        }
    }

    /** Downloads a remote mod file into a mods directory. */
    static FileDownloadTask downloadTask(RemoteAddon.Version version, Path modsDirectory)
            throws IOException {
        if (version.file() == null || version.file().url() == null) {
            throw new IOException("该版本没有可下载的文件");
        }
        return new FileDownloadTask(version.file().url(),
                modsDirectory.resolve(version.file().filename()),
                version.file().getIntegrityCheck());
    }

    /**
     * The newest remote build of a dependency that fits the target, for the
     * "install the prerequisites for me" path.
     */
    static Optional<RemoteAddon.Version> findDependency(String modId, Installed target,
                                                       ModLoaderType loader) {
        try {
            ModrinthRemoteAddonRepository repository = ModrinthRemoteAddonRepository.MODS;
            DownloadProvider provider = downloadProvider();
            RemoteAddon addon = repository.getModById(provider, modId);
            return addon.data().loadVersions(repository, provider)
                    .filter(candidate -> loader == ModLoaderType.UNKNOWN
                            || candidate.loaders().contains(loader))
                    .filter(candidate -> target.gameVersion() == null
                            || candidate.gameVersions().contains(target.gameVersion()))
                    .max(Comparator.comparing(RemoteAddon.Version::datePublished));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
