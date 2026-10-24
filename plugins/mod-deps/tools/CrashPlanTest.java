import org.dsh.moddeps.MissingModScanner;
import org.dsh.moddeps.ModDepsPlugin;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/// Reproduces the crash-to-prompt decision with the user's real state: the game crashed
/// because a prerequisite is unusable, the log pins an exact version, and a *different*
/// build of that mod is already installed. The plan must be non-empty — that is what makes
/// the launcher raise the "download the prerequisites?" dialog.
public final class CrashPlanTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Set<ModLoaderType> fabric = Set.of(ModLoaderType.FABRIC);
        String log = """
                [17:47:41] [main/ERROR]: Incompatible mods found!
                net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
                   - Fix: add [add:sodium 0.9.2+mc26.3] to Mods
                Immediate reason: [{"depends sodium @ [>=0.9.2]"}]
                """;

        var missing = MissingModScanner.scan(log, fabric);
        check(missing.size() == 1 && "sodium".equals(missing.get(0).id())
                        && "0.9.2+mc26.3".equals(missing.get(0).pinnedVersion()),
                "crash log yields the missing prerequisite + pinned version: " + missing);

        // Shapes copied from the live Modrinth API: version_number is "mc26.3-0.9.2-fabric"
        // while the file is "sodium-fabric-0.9.2+mc26.3.jar".
        List<RemoteAddon.Version> remote = List.of(
                version("Sodium 0.9.3-alpha.1 for Fabric 26.3", "mc26.3-0.9.3-alpha.1-fabric",
                        "sodium-fabric-0.9.3-alpha.1+mc26.3.jar"),
                version("Sodium 0.9.2 for Fabric 26.3", "mc26.3-0.9.2-fabric",
                        "sodium-fabric-0.9.2+mc26.3.jar"),
                version("Sodium 0.9.2 for Fabric 26.1.2", "mc26.1.2-0.9.2-fabric",
                        "sodium-fabric-0.9.2+mc26.1.2.jar"));

        // The user's real mods dir: sodium is present, but only the newer alpha build.
        Path mods = Files.createTempDirectory("crash-plan-mods");
        Files.writeString(mods.resolve("sodium-fabric-0.9.3-alpha.1+mc26.3.jar"), "installed");
        Files.writeString(mods.resolve("iris-fabric-1.11.7+mc26.3.jar"), "installed");

        List<String> lines = new java.util.ArrayList<>();
        ModDepsPlugin.CrashPlan plan = ModDepsPlugin.planForCrash(
                missing, "26.3", fabric, mods, id -> remote, lines::add);

        System.out.println("   plan log: " + lines);
        check(!plan.downloadable().isEmpty(),
                "a downloadable version is offered (the prompt will be shown)");
        check(plan.downloadable().size() == 1
                        && "sodium-fabric-0.9.2+mc26.3.jar"
                                .equals(plan.downloadable().get(0).version().file().filename())
                        && "sodium".equals(plan.downloadable().get(0).id()),
                "the log's pinned build for this game version is picked, not the installed alpha: "
                        + plan.downloadable());
        check(plan.unresolved().isEmpty(), "nothing left unresolved");

        // The exact file already present is genuinely satisfied and must not be re-downloaded.
        Path mods2 = Files.createTempDirectory("crash-plan-mods2");
        Files.writeString(mods2.resolve("sodium-fabric-0.9.2+mc26.3.jar"), "installed");
        ModDepsPlugin.CrashPlan satisfied = ModDepsPlugin.planForCrash(
                missing, "26.3", fabric, mods2, id -> remote, line -> { });
        check(satisfied.downloadable().isEmpty(), "same file already present is not re-downloaded");

        // Version switch: the log names the build to switch away from, so it must be reported
        // and the old file has to be found for disabling (by id, or by file name as a fallback).
        var switchLog = MissingModScanner.scan(
                "Fix: replace [remove:sodium 0.9.3-alpha.1] with [add:sodium 0.9.2+mc26.3] in Mods", fabric);
        check("0.9.3-alpha.1".equals(switchLog.get(0).installedVersion()),
                "switch log reports the installed version: " + switchLog);

        check("sodium-fabric-".equals(ModDepsPlugin.modStem("sodium-fabric-0.9.3-alpha.1+mc26.3.jar"))
                        && "sodium-extra-".equals(ModDepsPlugin.modStem("sodium-extra-0.6.0.jar"))
                        && "modmenu-".equals(ModDepsPlugin.modStem("modmenu-21.0.0.jar")),
                "file-name stem stops before the version");

        Path mods3 = Files.createTempDirectory("crash-plan-mods3");
        Files.writeString(mods3.resolve("sodium-fabric-0.9.3-alpha.1+mc26.3.jar"), "old");
        Files.writeString(mods3.resolve("sodium-extra-0.6.0.jar"), "unrelated");
        Files.writeString(mods3.resolve("iris-fabric-1.11.7+mc26.3.jar"), "unrelated");
        ModDepsPlugin.CrashPlan switchPlan = ModDepsPlugin.planForCrash(
                switchLog, "26.3", fabric, mods3, id -> remote, line -> { });
        var stale = ModDepsPlugin.outdatedByFilename(mods3, switchPlan.downloadable(), List.of());
        check(stale.size() == 1 && "sodium-fabric-0.9.3-alpha.1+mc26.3.jar"
                        .equals(stale.get(0).getFileName().toString()),
                "only the outdated build of the same mod is switched out: " + stale);

        // Unknown mod: reported so the prompt can tell the user to install it by hand.
        var unknown = MissingModScanner.scan("Mod 'x' requires 'not-a-real-mod'", fabric);
        ModDepsPlugin.CrashPlan unresolved = ModDepsPlugin.planForCrash(
                unknown, "26.3", fabric, mods2, id -> List.of(), line -> { });
        check(unresolved.downloadable().isEmpty() && unresolved.unresolved().contains("not-a-real-mod"),
                "unresolvable id is reported for manual install: " + unresolved.unresolved());

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static RemoteAddon.Version version(String name, String version, String filename) {
        return new RemoteAddon.Version(null, "sodium", name, version, "", Instant.now(), null,
                new RemoteAddon.File(java.util.Map.of(), "https://example.invalid/" + filename, filename),
                List.of(), List.of("26.3"), List.of(ModLoaderType.FABRIC));
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}
