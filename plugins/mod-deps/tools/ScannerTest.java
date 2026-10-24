import org.dsh.moddeps.MissingModScanner;
import org.dsh.moddeps.ModDepsPlugin;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.util.Set;

public final class ScannerTest {

    private static int failures = 0;

    public static void main(String[] args) {
        Set<ModLoaderType> fabric = Set.of(ModLoaderType.FABRIC);
        Set<ModLoaderType> forge = Set.of(ModLoaderType.FORGE);

        String fabricLog1 = "Mod 'sodium-extra' requires 'sodium' at 0.5+";
        var r1 = MissingModScanner.scan(fabricLog1, fabric);
        check(r1.stream().anyMatch(m -> m.id().equals("sodium")), "fabric requires-single: " + r1);

        String fabricLog2 = "mod \"sodiumextra\" (sodium-extra) requires mod \"sodium\"";
        check(MissingModScanner.scan(fabricLog2, fabric).stream().anyMatch(m -> m.id().equals("sodium")),
                "fabric quoted requires");

        String fabricLog3 = "Unresolved dependency: mod \"fabric-api\" is missing, required by \"jei\"";
        check(MissingModScanner.scan(fabricLog3, fabric).stream().anyMatch(m -> m.id().equals("fabric-api")),
                "fabric is-missing");

        String forgeLog = "Missing or unsupported mandatory dependencies:\n\t- jei:[1.20.1,)\tFOUNDRY\n\t- emi:1.0.0\tREQUIRED";
        var rf = MissingModScanner.scan(forgeLog, forge);
        check(rf.stream().anyMatch(m -> m.id().equals("jei")) && rf.stream().anyMatch(m -> m.id().equals("emi")),
                "forge dependency block: " + rf);

        String forgeLog2 = "Mod File: somefile_forge.jar is missing mods.toml file";
        check(MissingModScanner.scan(forgeLog2, forge).stream().anyMatch(m -> m.id().equals("somefile")),
                "forge missing-file slug");

        String noise = "Loading Minecraft 1.20.1 with Forge 47.2.0\nFailed to load library log4j.jar\nMod 'minecraft' requires 'java'";
        check(MissingModScanner.scan(noise, forge).isEmpty(), "noise filtered");

        String dup = "Mod 'a-mod' requires 'b-mod'\nMod 'a-mod' requires 'b-mod' at 2.0";
        check(MissingModScanner.scan(dup, fabric).size() == 1, "duplicate ids deduped");

        // Version switches: the log names both the installed and the required version.
        String switchFix = "Fix: replace [remove:sodium 0.9.3-alpha.1] with [add:sodium 0.9.2+mc26.3] in Mods";
        var sf = MissingModScanner.scan(switchFix, fabric);
        check(sf.size() == 1 && "sodium".equals(sf.get(0).id())
                        && "0.9.2+mc26.3".equals(sf.get(0).pinnedVersion())
                        && "0.9.3-alpha.1".equals(sf.get(0).installedVersion()),
                "fabric replace fix carries both versions: " + sf);

        String switchProse = "Replace mod 'Sodium' (sodium) 0.9.3-alpha.1 with version 0.9.2+mc26.3 of 'sodium', or remove it";
        var sp = MissingModScanner.scan(switchProse, fabric);
        check(sp.stream().anyMatch(m -> "sodium".equals(m.id())
                        && "0.9.2+mc26.3".equals(m.pinnedVersion())
                        && "0.9.3-alpha.1".equals(m.installedVersion())),
                "fabric replace prose carries both versions: " + sp);

        // Verbatim from a real 1.21.11 crash: iris requires sodium 0.8.x but sodium 0.8.14
        // declares it breaks iris, so Fabric asks to replace 0.8.14 with 0.8.3.
        String realLog = "Immediate reason: [HARD_DEP iris 1.10.5+mc1.21.11 {depends sodium @ [0.8.x]}, "
                + "NEG_HARD_DEP sodium 0.8.14+mc1.21.11 {breaks iris @ [<=1.10.7]}, ROOT_FORCELOAD_SINGLE iris 1.10.5+mc1.21.11]\n"
                + "Fix: add [], remove [], replace [[sodium 0.8.14+mc1.21.11] -> add:sodium 0.8.3 ([[0.8.3,0.9-)])]";
        var rl = MissingModScanner.scan(realLog, fabric);
        check(rl.size() == 1 && "sodium".equals(rl.get(0).id())
                        && "0.8.3".equals(rl.get(0).pinnedVersion())
                        && "0.8.14+mc1.21.11".equals(rl.get(0).installedVersion()),
                "real 1.21.11 replace fix yields id + pinned + installed: " + rl);

        String updateFix = "Fix: update [update:sodium 0.9.2+mc26.3] in Mods";
        check(MissingModScanner.scan(updateFix, fabric).stream()
                        .anyMatch(m -> "sodium".equals(m.id()) && "0.9.2+mc26.3".equals(m.pinnedVersion())),
                "fabric update fix names the version to switch to");

        String requiresVersion = "Mod 'sodium-extra' (sodium-extra) 0.6.0 requires version 0.6.0 or later of sodium, which is missing!";
        var rv = MissingModScanner.scan(requiresVersion, fabric);
        check(rv.stream().anyMatch(m -> "sodium".equals(m.id())),
                "requires-version form names the required mod: " + rv);
        check(rv.stream().noneMatch(m -> "sodium-extra".equals(m.id())),
                "the mod doing the requiring is not mistaken for the missing one: " + rv);

        // The version the crash log pins must be recognised even though remote version
        // numbers spell the build suffix differently (0.9.2+mc26.3 vs 0.9.2).
        check(ModDepsPlugin.pinnedMatches("0.9.2+mc26.3", "sodium-fabric-0.9.2+mc26.3.jar", "0.9.2+mc26.3"),
                "pinned: exact build suffix");
        // Modrinth's real shapes: version_number carries the mc prefix, the file carries the suffix.
        check(ModDepsPlugin.pinnedMatches("mc26.3-0.9.2-fabric", "sodium-fabric-0.9.2+mc26.3.jar", "0.9.2+mc26.3"),
                "pinned: Modrinth version_number + filename");
        check(!ModDepsPlugin.pinnedMatches("mc26.1.2-0.9.2-fabric", "sodium-fabric-0.9.2+mc26.1.2.jar", "0.9.2+mc26.3"),
                "pinned: same mod version for another game version is rejected");
        check(!ModDepsPlugin.pinnedMatches("0.9.3-alpha.1+mc26.3", "sodium-fabric-0.9.3-alpha.1+mc26.3.jar", "0.9.2+mc26.3"),
                "pinned: a different release does not match");
        check(ModDepsPlugin.pinnedMatches("1.0.0", "jei-1.0.0.jar", "1.0.0"),
                "pinned: a pin without a build suffix matches on the version alone");
        check(!ModDepsPlugin.pinnedMatches("0.9.2-beta.1", "sodium-0.9.2-beta.1.jar", "0.9.2+mc26.3"),
                "pinned: unverifiable build suffix is not treated as the pinned release");
        check(!ModDepsPlugin.pinnedMatches("1.0.0", "other-1.0.0.jar", ""),
                "pinned: empty pin never matches");

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}
