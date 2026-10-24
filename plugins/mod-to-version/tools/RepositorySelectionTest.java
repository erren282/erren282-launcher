package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;

import java.util.List;

/**
 * Verifies that the online-fallback repository list is built per download section, and only
 * from sources this launcher build can actually use.
 *
 * <h2>Why this exists</h2>
 * <p>The lookup that runs when the page's cached release list does not contain the release
 * has to ask the right backends. Two mistakes are easy and both were present:</p>
 *
 * <ul>
 *   <li><b>asking the mod repository for a resource pack</b> — {@code MODS} talks to the
 *       mod endpoint, so a pack looked up through it returns nothing and the fallback
 *       quietly fails for every section except mods;</li>
 *   <li><b>offering CurseForge when it has no API key</b> — many builds (including ones
 *       not produced by the official pipeline) compile in no key, so every CurseForge call
 *       is a guaranteed 403. Trying it anyway means a wasted request on every lookup.</li>
 * </ul>
 *
 * <p>Whether this build has a key is read from the launcher itself rather than hard-coded,
 * so these checks stay valid in either kind of build.</p>
 */
public final class RepositorySelectionTest {

    private static int passed;
    private static int failed;

    /// True when this build compiled in a CurseForge API key.
    private static final boolean CURSEFORGE_AVAILABLE =
            CurseForgeRemoteAddonRepository.isAvailable();

    public static void main(String[] args) {
        System.out.println("CurseForge available in this build: " + CURSEFORGE_AVAILABLE);
        sectionChecks();
        curseForgeGatingChecks();
        preferredOrderingChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// Each section must be offered its own repositories, never another section's.
    private static void sectionChecks() {
        System.out.println();
        System.out.println("=== each section gets its own repositories ===");

        List<RemoteAddonRepository> mods = ModToVersionEngine.builtInRepositories(AddonKind.MOD);
        List<RemoteAddonRepository> packs =
                ModToVersionEngine.builtInRepositories(AddonKind.RESOURCE_PACK);
        List<RemoteAddonRepository> shaders =
                ModToVersionEngine.builtInRepositories(AddonKind.SHADER_PACK);
        List<RemoteAddonRepository> worlds =
                ModToVersionEngine.builtInRepositories(AddonKind.WORLD);

        check("mods use the Modrinth mod repository",
                mods.contains(ModrinthRemoteAddonRepository.MODS));
        check("resource packs use the Modrinth resource pack repository",
                packs.contains(ModrinthRemoteAddonRepository.RESOURCE_PACKS));
        check("resource packs do not use the Modrinth mod repository",
                !packs.contains(ModrinthRemoteAddonRepository.MODS));
        check("shaders use the Modrinth shader repository",
                shaders.contains(ModrinthRemoteAddonRepository.SHADER_PACKS));
        check("shaders do not use the Modrinth mod repository",
                !shaders.contains(ModrinthRemoteAddonRepository.MODS));
        check("mods do not use the resource pack repository",
                !mods.contains(ModrinthRemoteAddonRepository.RESOURCE_PACKS));

        System.out.println();
        System.out.println("=== worlds are CurseForge-only ===");
        // Modrinth has no world section, so a build without a CurseForge key genuinely has
        // no built-in world backend. That is a launcher property, not a plugin bug.
        check("worlds never fall back to a Modrinth resource pack repository",
                !worlds.contains(ModrinthRemoteAddonRepository.RESOURCE_PACKS));
        check("worlds never fall back to the Modrinth mod repository",
                !worlds.contains(ModrinthRemoteAddonRepository.MODS));
        if (CURSEFORGE_AVAILABLE) {
            check("worlds use the CurseForge world repository",
                    worlds.contains(CurseForgeRemoteAddonRepository.WORLDS));
        } else {
            check("without a CurseForge key the world section has no built-in backend",
                    worlds.isEmpty());
        }

        System.out.println();
        System.out.println("=== no section lists the same repository twice ===");
        for (AddonKind kind : AddonKind.values()) {
            List<RemoteAddonRepository> repositories = ModToVersionEngine.builtInRepositories(kind);
            check(kind + " has no duplicates",
                    repositories.size() == repositories.stream().distinct().count());
        }
    }

    /// CurseForge may only appear when it can actually be called.
    private static void curseForgeGatingChecks() {
        System.out.println();
        System.out.println("=== CurseForge is offered only when usable ===");

        for (AddonKind kind : AddonKind.values()) {
            List<RemoteAddonRepository> repositories = ModToVersionEngine.builtInRepositories(kind);
            boolean offersCurseForge = repositories.stream()
                    .anyMatch(CurseForgeRemoteAddonRepository.class::isInstance);
            if (CURSEFORGE_AVAILABLE) {
                check(kind + " offers CurseForge when a key is compiled in", offersCurseForge);
            } else {
                check(kind + " does not offer CurseForge without a key", !offersCurseForge);
            }
        }

        System.out.println();
        System.out.println("=== Modrinth is always offered where it has a backend ===");
        // Modrinth needs no key, so it must never be dropped.
        check("the mod section always offers Modrinth",
                ModToVersionEngine.builtInRepositories(AddonKind.MOD)
                        .contains(ModrinthRemoteAddonRepository.MODS));
        check("the resource pack section always offers Modrinth",
                ModToVersionEngine.builtInRepositories(AddonKind.RESOURCE_PACK)
                        .contains(ModrinthRemoteAddonRepository.RESOURCE_PACKS));
        check("the shader section always offers Modrinth",
                ModToVersionEngine.builtInRepositories(AddonKind.SHADER_PACK)
                        .contains(ModrinthRemoteAddonRepository.SHADER_PACKS));
    }

    /// The page's own repository is tried first, and never listed twice.
    private static void preferredOrderingChecks() {
        System.out.println();
        System.out.println("=== the page's own repository is tried first ===");

        List<RemoteAddonRepository> candidates = ModToVersionEngine.repositoryCandidates(
                ModrinthRemoteAddonRepository.RESOURCE_PACKS, AddonKind.RESOURCE_PACK);
        check("the preferred repository comes first",
                !candidates.isEmpty()
                        && candidates.get(0) == ModrinthRemoteAddonRepository.RESOURCE_PACKS);
        check("it is not repeated further down",
                candidates.size() == candidates.stream().distinct().count());

        System.out.println();
        System.out.println("=== a null preferred repository is tolerated ===");
        List<RemoteAddonRepository> fallback = ModToVersionEngine.repositoryCandidates(
                null, AddonKind.MOD);
        check("the built-in repositories are still offered", !fallback.isEmpty());
        check("none of them is null", fallback.stream().noneMatch(java.util.Objects::isNull));

        System.out.println();
        System.out.println("=== the world section with a null preference ===");
        // This is the case that used to blow up: no key, no preference, nothing to try.
        List<RemoteAddonRepository> worlds = ModToVersionEngine.repositoryCandidates(
                null, AddonKind.WORLD);
        check("the list is usable rather than null",
                worlds != null);
        check("it contains nothing unusable", worlds.stream().noneMatch(java.util.Objects::isNull));
        if (!CURSEFORGE_AVAILABLE) {
            check("without a key there is nothing to try, which is reported by an empty list",
                    worlds.isEmpty());
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
