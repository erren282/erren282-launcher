package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies which installed versions a release is offered for, per download section.
 *
 * <p>This is the rule that decides whether the new button can install something the game
 * would actually use, so it is tested directly rather than through the UI. The section
 * matters: a mod is filtered by loader, but a resource pack, shader or world is not,
 * because the loaders those advertise ({@code minecraft}, {@code optifine}, {@code iris})
 * are ones the launcher never reports.</p>
 */
public final class VersionMatcherTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        modChecks();
        resourcePackAndShaderChecks();
        worldChecks();
        releaseSelectionChecks();
        subtitleChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void modChecks() {
        System.out.println("=== a mod is filtered by game version and loader ===");

        RemoteAddon.Version fabric1204 = release("Sodium 0.5.8", List.of("1.20.4"),
                List.of(ModLoaderType.FABRIC));
        List<VersionMatcher.Installed> fits = VersionMatcher.fitting(installed(), fabric1204,
                AddonKind.MOD);
        System.out.println("  offered: " + ids(fits));
        check("only the matching game version and loader is offered",
                fits.size() == 1 && fits.get(0).id().equals("1.20.4-Fabric"));
        check("a Forge instance on the same game version is not offered",
                fits.stream().noneMatch(t -> t.id().equals("1.20.4-Forge")));
        check("an older game version is not offered",
                fits.stream().noneMatch(t -> t.id().equals("1.19.2-Fabric")));

        System.out.println();
        System.out.println("=== a mod that lists several game versions ===");
        RemoteAddon.Version multi = release("JEI", List.of("1.20.4", "1.19.2"),
                List.of(ModLoaderType.FABRIC));
        List<VersionMatcher.Installed> many = VersionMatcher.fitting(installed(), multi,
                AddonKind.MOD);
        System.out.println("  offered: " + ids(many));
        check("every listed game version is offered", many.size() == 2);

        System.out.println();
        System.out.println("=== a mod with no declared loaders ===");
        // Some mods do not declare loaders; hiding them everywhere would be worse than
        // offering them where the game version matches.
        RemoteAddon.Version anyLoader = release("Config Lib", List.of("1.20.4"), List.of());
        List<VersionMatcher.Installed> anyResult = VersionMatcher.fitting(installed(), anyLoader,
                AddonKind.MOD);
        System.out.println("  offered: " + ids(anyResult));
        check("it is offered for both the Fabric and Forge builds of that version",
                anyResult.size() == 2);

        System.out.println();
        System.out.println("=== the vanilla instance ===");
        check("an instance with no loader does not take a Fabric mod",
                !VersionMatcher.accepts(installed().get(3), fabric1204, AddonKind.MOD));
        check("an instance on a different game version does not take it either",
                !VersionMatcher.accepts(installed().get(3), anyLoader, AddonKind.MOD));
        VersionMatcher.Installed vanilla1204 =
                new VersionMatcher.Installed("1.20.4", "1.20.4", Set.of());
        check("a vanilla instance on the right game version takes a loader-agnostic mod",
                VersionMatcher.accepts(vanilla1204, anyLoader, AddonKind.MOD));
        check("a vanilla instance does not take a mod that needs a loader",
                !VersionMatcher.accepts(vanilla1204, fabric1204, AddonKind.MOD));
    }

    /// The new behaviour: these sections must not be hidden by loader metadata.
    private static void resourcePackAndShaderChecks() {
        System.out.println();
        System.out.println("=== a resource pack ignores the loader ===");
        // CurseForge reports game versions and "loaders" in the same list; a resource pack
        // typically says "minecraft" and "1.20.4", which the launcher cannot detect.
        RemoteAddon.Version pack = release("Faithful 32x", List.of("1.20.4"),
                List.of());
        List<VersionMatcher.Installed> packFits = VersionMatcher.fitting(installed(), pack,
                AddonKind.RESOURCE_PACK);
        System.out.println("  offered: " + ids(packFits));
        check("every instance on that game version is offered",
                packFits.size() == 2);
        check("the Fabric instance is included",
                packFits.stream().anyMatch(t -> t.id().equals("1.20.4-Fabric")));
        check("the Forge instance is included too",
                packFits.stream().anyMatch(t -> t.id().equals("1.20.4-Forge")));

        System.out.println();
        System.out.println("=== a shader ignores the loader ===");
        RemoteAddon.Version shader = release("Complementary", List.of("1.20.4", "1.19.2"),
                List.of());
        List<VersionMatcher.Installed> shaderFits = VersionMatcher.fitting(installed(), shader,
                AddonKind.SHADER_PACK);
        System.out.println("  offered: " + ids(shaderFits));
        check("three instances are offered", shaderFits.size() == 3);

        System.out.println();
        System.out.println("=== a pack that declares an undetectable loader is still offered ===");
        // This is the case that mattered: if loaders were matched for resource packs,
        // every row would disappear because no instance reports "minecraft".
        RemoteAddon.Version packWithLoader = release("Faithful 32x", List.of("1.20.4"),
                List.of(ModLoaderType.UNKNOWN));
        List<VersionMatcher.Installed> withLoader = VersionMatcher.fitting(installed(),
                packWithLoader, AddonKind.RESOURCE_PACK);
        check("an unknown declared loader does not hide the rows",
                withLoader.size() == 2);
        // The same release as a mod would be rejected, which is why the section decides.
        check("the same release as a mod would be rejected",
                VersionMatcher.fitting(installed(), packWithLoader, AddonKind.MOD).isEmpty());
    }

    private static void worldChecks() {
        System.out.println();
        System.out.println("=== a world is filtered by game version only ===");
        RemoteAddon.Version world = release("SkyBlock Adventure", List.of("1.20.4"), List.of());
        List<VersionMatcher.Installed> worldFits = VersionMatcher.fitting(installed(), world,
                AddonKind.WORLD);
        System.out.println("  offered: " + ids(worldFits));
        check("both instances on that game version are offered", worldFits.size() == 2);

        System.out.println();
        System.out.println("=== a world with no game versions is offered everywhere ===");
        // Some world archives carry no version metadata; refusing to offer them anywhere
        // would make the feature look broken.
        RemoteAddon.Version anyVersion = release("Mystery World", List.of(), List.of());
        List<VersionMatcher.Installed> all = VersionMatcher.fitting(installed(), anyVersion,
                AddonKind.WORLD);
        System.out.println("  offered: " + ids(all));
        check("every installed world version is offered", all.size() == 4);

        System.out.println();
        System.out.println("=== an unknown game version is not claimed as a match ===");
        VersionMatcher.Installed unknown =
                new VersionMatcher.Installed("mystery", null, Set.of());
        check("a world for a specific version is not offered to an unknown instance",
                !VersionMatcher.accepts(unknown, world, AddonKind.WORLD));
        check("but a version-agnostic world still is",
                VersionMatcher.accepts(unknown, anyVersion, AddonKind.WORLD));
    }

    private static void releaseSelectionChecks() {
        System.out.println();
        System.out.println("=== which release is installed ===");

        RemoteAddon.Version shown = release("Sodium 0.5.8", List.of("1.20.4"),
                List.of(ModLoaderType.FABRIC), "2026-05-01");
        RemoteAddon.Version newer = release("Sodium 0.6.0", List.of("1.20.4"),
                List.of(ModLoaderType.FABRIC), "2026-06-01");
        RemoteAddon.Version forgeBuild = release("Sodium 0.5.8 Forge", List.of("1.20.4"),
                List.of(ModLoaderType.FORGE), "2026-05-01");

        VersionMatcher.Installed fabricTarget =
                new VersionMatcher.Installed("1.20.4-Fabric", "1.20.4", Set.of(ModLoaderType.FABRIC));

        check("the release the dialog shows is the one installed",
                VersionMatcher.releaseFor(List.of(shown, newer), shown, fabricTarget,
                        AddonKind.MOD) == shown);

        System.out.println();
        System.out.println("=== falling back when the shown release does not fit ===");
        VersionMatcher.Installed forgeTarget =
                new VersionMatcher.Installed("1.20.4-Forge", "1.20.4", Set.of(ModLoaderType.FORGE));
        RemoteAddon.Version picked = VersionMatcher.releaseFor(
                List.of(shown, forgeBuild), shown, forgeTarget, AddonKind.MOD);
        check("the matching Forge build is used instead of the Fabric one",
                picked == forgeBuild);

        System.out.println();
        System.out.println("=== nothing fit ===");
        VersionMatcher.Installed old =
                new VersionMatcher.Installed("1.7.10-Forge", "1.7.10", Set.of(ModLoaderType.FORGE));
        check("no release is returned when none fits",
                VersionMatcher.releaseFor(List.of(shown, newer, forgeBuild), shown, old,
                        AddonKind.MOD) == null);
        check("the shown release is still used when the list is empty",
                VersionMatcher.releaseFor(List.of(), shown, fabricTarget, AddonKind.MOD) == shown);
        check("a null release list yields nothing when the shown release does not fit",
                VersionMatcher.releaseFor(null, shown, forgeTarget, AddonKind.MOD) == null);

        System.out.println();
        System.out.println("=== the newest fitting release wins a fallback ===");
        RemoteAddon.Version olderForge = release("Sodium 0.4.0 Forge", List.of("1.20.4"),
                List.of(ModLoaderType.FORGE), "2026-01-01");
        RemoteAddon.Version pickedNewest = VersionMatcher.releaseFor(
                List.of(olderForge, forgeBuild), shown, forgeTarget, AddonKind.MOD);
        check("the newest fitting release is chosen", pickedNewest == forgeBuild);

        System.out.println();
        System.out.println("=== a pack keeps the single release it was shown ===");
        // With no loader requirement there is nothing to fall back to, so the release the
        // dialog showed must be installed as-is.
        RemoteAddon.Version pack = release("Faithful 32x", List.of("1.20.4"), List.of(),
                "2026-05-01");
        check("the shown pack is installed into a Forge instance",
                VersionMatcher.releaseFor(List.of(pack), pack, forgeTarget,
                        AddonKind.RESOURCE_PACK) == pack);
    }

    private static void subtitleChecks() {
        System.out.println();
        System.out.println("=== how a version row reads ===");
        VersionMatcher.Installed target =
                new VersionMatcher.Installed("1.20.4-Fabric", "1.20.4", Set.of(ModLoaderType.FABRIC));
        System.out.println("  subtitle: " + target.subtitle());
        check("the subtitle names the game version", target.subtitle().contains("1.20.4"));
        check("the subtitle names the loader", target.subtitle().contains("Fabric"));

        VersionMatcher.Installed unnamed =
                new VersionMatcher.Installed("1.20.4", "1.20.4", Set.of());
        check("the game version is not repeated when it equals the id",
                !unnamed.subtitle().contains("游戏版本"));
        check("a loaderless version says so",
                unnamed.subtitle().contains("未检测到加载器"));

        VersionMatcher.Installed unknown =
                new VersionMatcher.Installed("mystery", null, Set.of());
        check("an unknown game version says so",
                unknown.subtitle().contains("游戏版本未知"));
    }

    // ------------------------------------------------------------------ helpers

    private static List<VersionMatcher.Installed> installed() {
        return List.of(
                new VersionMatcher.Installed("1.20.4-Fabric", "1.20.4", Set.of(ModLoaderType.FABRIC)),
                new VersionMatcher.Installed("1.20.4-Forge", "1.20.4", Set.of(ModLoaderType.FORGE)),
                new VersionMatcher.Installed("1.19.2-Fabric", "1.19.2", Set.of(ModLoaderType.FABRIC)),
                new VersionMatcher.Installed("1.20.1-Vanilla", "1.20.1", Set.of()));
    }

    private static List<String> ids(List<VersionMatcher.Installed> instances) {
        return instances.stream().map(VersionMatcher.Installed::id).toList();
    }

    private static RemoteAddon.Version release(String name, List<String> gameVersions,
                                               List<ModLoaderType> loaders) {
        return release(name, gameVersions, loaders, "2026-05-01");
    }

    private static RemoteAddon.Version release(String name, List<String> gameVersions,
                                               List<ModLoaderType> loaders, String date) {
        return new RemoteAddon.Version(
                null, "example", name, name, "",
                Instant.parse(date + "T00:00:00Z"),
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/x.zip", "x.zip"),
                List.of(), gameVersions, loaders);
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
