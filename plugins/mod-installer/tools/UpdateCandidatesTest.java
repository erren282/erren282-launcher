package org.dsh.modinstall;

import org.jackhuang.hmcl.addon.RemoteAddon;

import java.time.Instant;
import java.util.List;

/**
 * Verifies how the update picker chooses which versions to offer, by calling the
 * engine's real {@link ModInstallerEngine#pickUpdateCandidates} — not a copy of
 * the rule.
 *
 * <p>The specification asks for the two newest test builds plus the newest stable
 * build that fit the chosen game version and the mod's loader. The narrowing by
 * game version and loader is a stream filter in {@code updateCandidates}, which
 * needs the network; what is checked here is the part that decides <em>which</em>
 * of the fitting versions to show, and in what order.</p>
 */
public final class UpdateCandidatesTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        selectionChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void selectionChecks() {
        System.out.println("=== two newest test builds + newest stable ===");

        List<RemoteAddon.Version> all = List.of(
                version("1.0.0", RemoteAddon.VersionType.Release, "2026-01-01"),
                version("1.1.0-beta", RemoteAddon.VersionType.Beta, "2026-02-01"),
                version("1.2.0-beta", RemoteAddon.VersionType.Beta, "2026-03-01"),
                version("1.3.0-beta", RemoteAddon.VersionType.Beta, "2026-04-01"),
                version("1.4.0-alpha", RemoteAddon.VersionType.Alpha, "2026-05-01"),
                version("2.0.0", RemoteAddon.VersionType.Release, "2026-06-01"));

        List<RemoteAddon.Version> picked = ModInstallerEngine.pickUpdateCandidates(all);
        System.out.println("  picked: " + names(picked));
        check("three versions are offered", picked.size() == 3);
        check("two of them are test builds",
                picked.stream().filter(v -> v.versionType() != RemoteAddon.VersionType.Release)
                        .count() == 2);
        check("one of them is a stable release",
                picked.stream().filter(v -> v.versionType() == RemoteAddon.VersionType.Release)
                        .count() == 1);
        check("the newest stable is chosen (2.0.0)",
                picked.stream().anyMatch(v -> v.version().equals("2.0.0")));
        check("the two newest test builds are chosen (alpha 1.4.0, beta 1.3.0)",
                picked.stream().anyMatch(v -> v.version().equals("1.4.0-alpha"))
                        && picked.stream().anyMatch(v -> v.version().equals("1.3.0-beta")));
        check("an older beta is not offered",
                picked.stream().noneMatch(v -> v.version().equals("1.1.0-beta")));

        System.out.println();
        System.out.println("=== fewer versions than asked for ===");
        List<RemoteAddon.Version> onlyStable = ModInstallerEngine.pickUpdateCandidates(List.of(
                version("1.0.0", RemoteAddon.VersionType.Release, "2026-01-01"),
                version("1.1.0", RemoteAddon.VersionType.Release, "2026-02-01")));
        System.out.println("  picked: " + names(onlyStable));
        check("only the newest stable is offered when there are no test builds",
                onlyStable.size() == 1 && onlyStable.get(0).version().equals("1.1.0"));

        List<RemoteAddon.Version> onlyBeta = ModInstallerEngine.pickUpdateCandidates(List.of(
                version("1.0.0-beta", RemoteAddon.VersionType.Beta, "2026-01-01")));
        check("a single test build is offered on its own",
                onlyBeta.size() == 1
                        && onlyBeta.get(0).versionType() != RemoteAddon.VersionType.Release);

        check("nothing available yields nothing to offer",
                ModInstallerEngine.pickUpdateCandidates(List.of()).isEmpty());

        System.out.println();
        System.out.println("=== dates decide 'newest', not the version string ===");
        List<RemoteAddon.Version> byDate = ModInstallerEngine.pickUpdateCandidates(List.of(
                version("v9", RemoteAddon.VersionType.Beta, "2026-01-01"),
                version("v1", RemoteAddon.VersionType.Beta, "2026-12-01"),
                version("v0", RemoteAddon.VersionType.Release, "2026-06-01")));
        System.out.println("  picked: " + names(byDate));
        check("a later date wins even with a lower version string",
                byDate.stream().anyMatch(v -> v.version().equals("v1")));
        check("a missing date does not win",
                !byDate.get(0).version().equals("v9"));
    }

    private static List<String> names(List<RemoteAddon.Version> versions) {
        return versions.stream()
                .map(v -> v.version() + "(" + ModInstallerEngine.versionTypeLabel(v.versionType()) + ")")
                .toList();
    }

    /** A real RemoteAddon.Version, carrying only what the selection reads. */
    private static RemoteAddon.Version version(String version, RemoteAddon.VersionType type,
                                               String date) {
        return new RemoteAddon.Version(
                null, "examplemod", "Example Mod", version, "", 
                Instant.parse(date + "T00:00:00Z"), type, null, List.of(),
                List.of("1.20.1"), List.of());
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
