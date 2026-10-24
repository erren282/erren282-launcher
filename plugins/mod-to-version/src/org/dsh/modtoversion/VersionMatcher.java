package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which installed game versions an addon release can be installed into.
 *
 * <p>Kept free of JavaFX so the rules can be tested directly. Everything here works on
 * the launcher's own {@link RemoteAddon.Version}, whose {@code gameVersions} and
 * {@code loaders} are what the repository reports for a release.</p>
 *
 * <h2>Loaders only constrain mods</h2>
 * <p>A mod release names the loaders it needs ({@code fabric}, {@code forge}, …), and
 * installing a Fabric mod into a Forge instance would produce a mod the game refuses to
 * load — so those are matched. A resource pack or shader release reports loaders too,
 * but only ever ones the launcher cannot detect ({@code minecraft}, {@code optifine},
 * {@code iris}, {@code canvas}); requiring a match there would hide every row, so for
 * them only the game version is checked. See {@link AddonKind#loaderSensitive()}.</p>
 */
final class VersionMatcher {

    /// An installed instance, with what it takes to decide whether an addon fits.
    ///
    /// @param id          the instance id, e.g. {@code 1.20.4-Fabric}
    /// @param gameVersion the Minecraft version it runs, or null when unknown
    /// @param loaders     the mod loaders detected in it, possibly empty
    /// @param lunarClient whether this instance is a Lunar client
    record Installed(String id, String gameVersion, Set<ModLoaderType> loaders,
                     boolean lunarClient) {

        /// Compatibility constructor for callers that predate Lunar detection.
        Installed(String id, String gameVersion, Set<ModLoaderType> loaders) {
            this(id, gameVersion, loaders, false);
        }

        /// The row title: the instance id.
        String describe() {
            return id;
        }

        /// The row subtitle: game version and loaders, skipping repeats.
        String subtitle() {
            List<String> parts = new ArrayList<>();
            if (gameVersion != null && !gameVersion.isBlank() && !gameVersion.equals(id)) {
                parts.add("游戏版本 " + gameVersion);
            } else if (gameVersion == null || gameVersion.isBlank()) {
                parts.add("游戏版本未知");
            }
            if (lunarClient) {
                // Say "Lunar" rather than the Fabric it is built on: the user picked a
                // Lunar instance and that is what the row is about.
                parts.add("Lunar 客户端");
            } else if (loaders != null && !loaders.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (ModLoaderType loader : loaders) {
                    names.add(nameOf(loader));
                }
                parts.add(String.join("/", names));
            } else {
                parts.add("未检测到加载器");
            }
            return String.join("  ·  ", parts);
        }
    }

    private VersionMatcher() {
    }

    /// Human-readable loader name.
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

    /// The installed versions a release can go into.
    ///
    /// <p>A version qualifies when this release lists its game version, and — for a mod
    /// — the instance carries one of the loaders the release declares. A release that
    /// declares no loaders is offered everywhere its game version matches, rather than
    /// being hidden.</p>
    ///
    /// @param kind the download section, deciding whether loaders are matched
    static List<Installed> fitting(List<Installed> installed, RemoteAddon.Version release,
                                  AddonKind kind) {
        List<Installed> result = new ArrayList<>();
        if (release == null || installed == null) {
            return result;
        }
        for (Installed candidate : installed) {
            if (accepts(candidate, release, kind)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /// True when one installed version can take one release.
    static boolean accepts(Installed candidate, RemoteAddon.Version release, AddonKind kind) {
        if (candidate == null || release == null) {
            return false;
        }
        if (!gameVersionMatches(candidate, release)) {
            return false;
        }
        // A resource pack, shader or world is loaded regardless of the mod loader the
        // instance runs, so its declared loaders are not a requirement.
        if (kind == null || !kind.loaderSensitive()) {
            return true;
        }
        List<ModLoaderType> requiredLoaders = release.loaders();
        if (requiredLoaders == null || requiredLoaders.isEmpty()) {
            return true;
        }
        Set<ModLoaderType> installedLoaders = candidate.loaders();
        if (installedLoaders == null || installedLoaders.isEmpty()) {
            return false;
        }
        for (ModLoaderType loader : requiredLoaders) {
            if (installedLoaders.contains(loader)) {
                return true;
            }
        }
        return false;
    }

    /// True when the release supports the instance's game version.
    ///
    /// <p>A release that lists no game versions is not gated at all: the repository
    /// sometimes omits them, and hiding it everywhere would be worse than offering it.</p>
    private static boolean gameVersionMatches(Installed candidate, RemoteAddon.Version release) {
        List<String> supported = release.gameVersions();
        if (supported == null || supported.isEmpty()) {
            return true;
        }
        String gameVersion = candidate.gameVersion();
        if (gameVersion == null || gameVersion.isBlank()) {
            // The instance's game version could not be determined: do not claim a match
            // that cannot be verified.
            return false;
        }
        return supported.contains(gameVersion);
    }

    /// The release to install into {@code target}, out of the releases the addon offers.
    ///
    /// <p>The user asked for one specific release — the one the dialog is showing — so
    /// that one is preferred. When it does not fit the chosen version, the newest
    /// release that does fit is used instead, which keeps the click useful rather than
    /// failing. For a mod that is how a Forge instance gets the Forge build of an addon
    /// whose Fabric build the dialog was showing.</p>
    static RemoteAddon.Version releaseFor(List<RemoteAddon.Version> available,
                                          RemoteAddon.Version shown, Installed target,
                                          AddonKind kind) {
        if (shown != null && accepts(target, shown, kind)) {
            return shown;
        }
        RemoteAddon.Version best = null;
        if (available != null) {
            for (RemoteAddon.Version candidate : available) {
                if (!accepts(target, candidate, kind)) {
                    continue;
                }
                if (best == null || isNewer(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /// Newest by publish date; a missing date never wins over a known one.
    private static boolean isNewer(RemoteAddon.Version candidate, RemoteAddon.Version current) {
        java.time.Instant a = candidate.datePublished();
        java.time.Instant b = current.datePublished();
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return a.isAfter(b);
    }
}
