package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the launcher's installed versions and the mod's releases, so the dialog
 * only has to deal with JavaFX.
 */
final class ModToVersionEngine {

    private ModToVersionEngine() {
    }

    /** Every installed version, with the game version and loaders it carries. */
    static List<VersionMatcher.Installed> installedVersions() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            return List.of();
        }
        List<VersionMatcher.Installed> result = new ArrayList<>();
        for (Version version : new ArrayList<>(repository.getVersions())) {
            // Hidden versions are internal scaffolding (the Lunar "zero-library" base the
            // Lunar client plugin creates, and its `<id>-vanilla` copy). The launcher's own
            // version list filters them out via isHidden(); the picker has to do the same,
            // otherwise the user is offered a base that is not meant to be played —
            // it showed up as a second `26.2-lunar-base` row next to the real version.
            if (version.isHidden()) {
                continue;
            }
            String gameVersion = repository.getGameVersion(version).orElse(null);
            Set<ModLoaderType> loaders;
            try {
                loaders = LibraryAnalyzer.analyze(version, gameVersion).getModLoaders();
            } catch (Throwable t) {
                loaders = Set.of();
            }
            // The Lunar client plugin builds its versions on Fabric, but
            // LibraryAnalyzer does not know about it: the version json's main class is the
            // plugin's entry wrapper, so no loader is detected and every Lunar instance
            // read "未检测到加载器" — which in turn rejected every Fabric mod, because the
            // matcher needs a loader the release asks for. Treat Lunar as Fabric: that is
            // exactly what its ichor loads mods with.
            if (isLunarClient(version)) {
                Set<ModLoaderType> merged = new java.util.LinkedHashSet<>(loaders);
                merged.add(ModLoaderType.FABRIC);
                loaders = merged;
            }
            result.add(new VersionMatcher.Installed(version.getId(), gameVersion, loaders,
                    isLunarClient(version)));
        }
        // Newest game version first: that is usually the one wanted.
        result.sort(Comparator.comparing(
                (VersionMatcher.Installed installed) -> installed.gameVersion() == null
                        ? "" : installed.gameVersion(),
                ModToVersionEngine::compareVersions).reversed());
        return result;
    }

    /// Whether this installed version is a Lunar client built by the lunar-offline plugin.
    ///
    /// <p>Detected by the entry wrapper's main class, which is what the plugin writes for
    /// every Lunar version (`org.dsh.lunarofl.wrapper.GenesisLauncher`), falling back to the
    /// inheritance chain for older installs that pointed straight at Genesis.</p>
    private static boolean isLunarClient(Version version) {
        if (version == null) {
            return false;
        }
        String mainClass = version.getMainClass();
        if (mainClass != null) {
            if (mainClass.equals("org.dsh.lunarofl.wrapper.GenesisLauncher")
                    || mainClass.startsWith("com.moonsworth.lunar")) {
                return true;
            }
        }
        String id = version.getId();
        String inheritsFrom = version.getInheritsFrom();
        return (id != null && id.startsWith("Lunar-"))
                || (inheritsFrom != null && inheritsFrom.endsWith("-lunar-base"));
    }

    /** The download provider the launcher is configured to use. */
    static DownloadProvider downloadProvider() {
        return DownloadProviders.getDownloadProvider();
    }

    /**
     * Resolves which release the dialog is showing, preferring data already in
     * memory over a network call.
     *
     * <p>Order matters for responsiveness:</p>
     * <ol>
     *   <li>the releases the page has already loaded (instant);</li>
     *   <li>only if those do not contain the name, the network.</li>
     * </ol>
     *
     * @param cached  releases already held by the launcher's page, may be empty
     * @param addon   the mod, used for the network fallback
     * @param name    the release name read from the dialog heading
     * @return the release, or empty when it cannot be found
     */
    static Optional<RemoteAddon.Version> resolveRelease(List<RemoteAddon.Version> cached,
                                                        RemoteAddon addon, String name,
                                                        AddonKind kind) {
        Optional<RemoteAddon.Version> fromCache = findReleaseByName(cached, name);
        if (fromCache.isPresent()) {
            return fromCache;
        }
        return findReleaseByName(releasesOf(addon, null, kind), name);
    }

    /**
     * Every release the mod offers.
     *
     * <p>A repository has to be supplied: {@code loadVersions} delegates straight to
     * it ({@code modRepository.getRemoteVersionsById}), and the addon record itself
     * carries only its {@code repoType}, not the repository object. The caller may
     * pass the page's own; when it has none (the page does not expose it), the
     * built-in repositories are tried in turn. A mod that exists on one repository
     * only is then still resolved, and a failure on the wrong one is not fatal.</p>
     *
     * <p>This hits the network, so it is only used when the page's cache did not
     * already have what was needed.</p>
     *
     * @return the releases, or an empty list when they cannot be read
     */
    static List<RemoteAddon.Version> releasesOf(RemoteAddon addon,
                                                RemoteAddonRepository preferred,
                                                AddonKind kind) {
        if (addon == null || addon.data() == null) {
            return List.of();
        }
        for (RemoteAddonRepository repository : repositoryCandidates(preferred, kind)) {
            try {
                List<RemoteAddon.Version> versions = addon.data()
                        .loadVersions(repository, downloadProvider()).toList();
                if (!versions.isEmpty()) {
                    return versions;
                }
            } catch (Throwable ignored) {
                // Wrong repository for this addon, or offline: try the next one.
            }
        }
        return List.of();
    }

    /// The repositories to try for a section, in order: the caller's own first, then the
    /// launcher's built-in repositories for that section.
    ///
    /// <p>Two things this has to get right:</p>
    /// <ul>
    ///   <li><b>the section matters</b> — a resource pack lives in the resource pack
    ///       repository, not the mod one. Looking it up through `MODS` asks the wrong
    ///       endpoint and gets nothing back, which used to make the online fallback fail
    ///       for every section except mods;</li>
    ///   <li><b>CurseForge may be unavailable</b> — it needs an API key compiled into the
    ///       launcher, and plenty of builds (including releases not built by the official
    ///       pipeline) have none. Offering it anyway only costs a guaranteed failure and a
    ///       timeout before the next candidate is tried.</li>
    /// </ul>
    ///
    /// @param preferred the repository the page itself is using, or null
    /// @param kind      the section being resolved
    static List<RemoteAddonRepository> repositoryCandidates(
            RemoteAddonRepository preferred, AddonKind kind) {
        List<RemoteAddonRepository> candidates = new ArrayList<>();
        if (preferred != null) {
            candidates.add(preferred);
        }
        for (RemoteAddonRepository builtIn : builtInRepositories(kind)) {
            if (builtIn != null && !candidates.contains(builtIn)) {
                candidates.add(builtIn);
            }
        }
        return candidates;
    }

    /// The built-in repositories a section can be served from, skipping any that cannot be
    /// used in this build.
    ///
    /// @param kind the section being resolved
    /// @return the usable repositories, possibly empty (a world in a build without a
    ///         CurseForge key has no built-in backend at all)
    static List<RemoteAddonRepository> builtInRepositories(AddonKind kind) {
        List<RemoteAddonRepository> repositories = new ArrayList<>();
        switch (kind) {
            case RESOURCE_PACK -> {
                repositories.add(ModrinthRemoteAddonRepository.RESOURCE_PACKS);
                if (CurseForgeRemoteAddonRepository.isAvailable()) {
                    repositories.add(CurseForgeRemoteAddonRepository.RESOURCE_PACKS);
                }
            }
            case SHADER_PACK -> {
                repositories.add(ModrinthRemoteAddonRepository.SHADER_PACKS);
                if (CurseForgeRemoteAddonRepository.isAvailable()) {
                    repositories.add(CurseForgeRemoteAddonRepository.SHADERS);
                }
            }
            case WORLD -> {
                // Worlds are CurseForge-only: Modrinth has no world section.
                if (CurseForgeRemoteAddonRepository.isAvailable()) {
                    repositories.add(CurseForgeRemoteAddonRepository.WORLDS);
                }
            }
            default -> {
                repositories.add(ModrinthRemoteAddonRepository.MODS);
                if (CurseForgeRemoteAddonRepository.isAvailable()) {
                    repositories.add(CurseForgeRemoteAddonRepository.MODS);
                }
            }
        }
        return repositories;
    }

    /**
     * Finds the release the dialog is showing, by its display name.
     *
     * <p>The dialog's heading carries the release name rather than an identifier,
     * so the name is what has to be matched. Names are not guaranteed unique; the
     * first exact match wins, which is the release the launcher itself listed.</p>
     */
    static Optional<RemoteAddon.Version> findReleaseByName(List<RemoteAddon.Version> releases,
                                                           String name) {
        if (releases == null || name == null) {
            return Optional.empty();
        }
        for (RemoteAddon.Version release : releases) {
            if (name.equals(release.name())) {
                return Optional.of(release);
            }
        }
        return Optional.empty();
    }

    /**
     * The icon the launcher shows for an installed version.
     *
     * <p>This is the same image the version list uses — the loader's logo, a custom
     * icon if the user set one, or the default grass block. Returns null when it
     * cannot be read, so callers can fall back to a plain icon.</p>
     */
    static javafx.scene.image.Image versionIcon(String versionId) {
        try {
            HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            return repository == null ? null : repository.getVersionIconImage(versionId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Numeric comparison of dotted version strings, so 1.20.10 > 1.20.9. */
    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = component(a, i);
            int y = component(b, i);
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int component(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < parts[index].length(); i++) {
            char c = parts[index].charAt(i);
            if (!Character.isDigit(c)) {
                break;
            }
            digits.append(c);
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
