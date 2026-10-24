package org.dsh.modinstall;

import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Verifies the mod-jar reader and the version matching it depends on.
 *
 * <p>Every case builds a real jar in a temp directory and reads it back through
 * {@link ModJarInfo}, so the loader dispatch, the TOML subset and the version
 * range logic are all exercised the way they are in the launcher rather than
 * against hand-made objects.</p>
 */
public final class ModJarInfoTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mod-install-test");

        tomlChecks();
        rangeChecks();
        fabricChecks(dir);
        forgeChecks(dir);
        neoForgeChecks(dir);
        quiltChecks(dir);
        legacyForgeChecks(dir);
        unknownChecks(dir);

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    // -------------------------------------------------------------------- TOML

    private static void tomlChecks() {
        System.out.println("=== minimal TOML reader ===");
        String text = """
                # a comment
                modLoader="javafml"
                loaderVersion="[47,)"

                [[mods]]
                modId="examplemod"
                version="1.2.3"
                displayName="Example Mod"   # trailing comment
                logoFile="logo.png"
                description='Single quoted'

                [[mods]]
                modId="secondmod"
                version="0.1"

                [[dependencies.examplemod]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"

                [[dependencies.examplemod]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"

                [[dependencies.examplemod]]
                modId="jei"
                mandatory=false
                versionRange="[15,)"
                """;
        Map<String, Object> toml = Toml.parse(text);
        check("top-level string parsed", "javafml".equals(toml.get("modLoader")));
        Object mods = toml.get("mods");
        check("[[mods]] produced a list of two", mods instanceof List<?> list && list.size() == 2);
        Object dependencies = toml.get("dependencies");
        check("dependencies table exists", dependencies instanceof Map);
        if (dependencies instanceof Map<?, ?> map) {
            Object entries = map.get("examplemod");
            check("three dependency entries", entries instanceof List<?> l && l.size() == 3);
            if (entries instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> first) {
                check("dependency fields parsed",
                        "minecraft".equals(first.get("modId"))
                                && Boolean.TRUE.equals(first.get("mandatory"))
                                && "[1.20.1,1.21)".equals(first.get("versionRange")));
            }
        }
        String multiline = """
                [[mods]]
                modId="m"
                authors=["A", "B",
                         "C"]
                """;
        Map<String, Object> parsed = Toml.parse(multiline);
        Object first = ((List<?>) parsed.get("mods")).get(0);
        if (first instanceof Map<?, ?> table) {
            check("multi-line inline array parsed",
                    table.get("authors") instanceof List<?> l && l.size() == 3);
        }
    }

    // --------------------------------------------------------- version ranges

    private static void rangeChecks() {
        System.out.println();
        System.out.println("=== version matching ===");
        check("exact match", McVersionRange.matchesSpec("1.20.1", "1.20.1"));
        check("exact mismatch", !McVersionRange.matchesSpec("1.20.1", "1.20.2"));
        check(">= accepts newer", McVersionRange.matchesSpec(">=1.20.1", "1.21"));
        check(">= rejects older", !McVersionRange.matchesSpec(">=1.20.1", "1.20"));
        check("conjunction both hold", McVersionRange.matchesSpec(">=1.20.1 <1.21", "1.20.4"));
        check("conjunction upper bound rejects", !McVersionRange.matchesSpec(">=1.20.1 <1.21", "1.21"));
        check("wildcard accepts anything", McVersionRange.matchesSpec("*", "1.7.10"));
        check("maven inclusive/exclusive", McVersionRange.matchesSpec("[1.20.1,1.21)", "1.20.9"));
        check("maven upper bound is exclusive", !McVersionRange.matchesSpec("[1.20.1,1.21)", "1.21"));
        check("maven open upper bound", McVersionRange.matchesSpec("[1.20.1,]", "1.99"));
        check("maven open lower bound", McVersionRange.matchesSpec("(,1.21]", "1.7.10"));
        check("tilde stays inside the minor", McVersionRange.matchesSpec("~1.20.1", "1.20.6"));
        check("tilde stops at the next minor", !McVersionRange.matchesSpec("~1.20.1", "1.21"));
        // The reason the comparison is numeric per component.
        check("1.20.10 is newer than 1.20.9",
                McVersionRange.compare("1.20.10", "1.20.9") > 0);
        check("1.9 is older than 1.10", McVersionRange.compare("1.9", "1.10") < 0);
        check("an empty spec list accepts everything", McVersionRange.matches(List.of(), "1.20.1"));
    }

    // ------------------------------------------------------------------ Fabric

    private static void fabricChecks(Path dir) throws Exception {
        System.out.println();
        System.out.println("=== Fabric ===");
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "examplemod",
                  "name": "Example Mod",
                  "version": "1.2.3",
                  "description": "An example",
                  "authors": ["Alice", "Bob"],
                  "icon": {"64": "assets/examplemod/icon64.png", "128": "assets/examplemod/icon128.png"},
                  "depends": {
                    "fabricloader": ">=0.14.0",
                    "minecraft": ">=1.20.1 <1.21",
                    "java": ">=17",
                    "fabric-api": "*",
                    "cloth-config": ">=11.0.0"
                  }
                }
                """;
        Path jar = jar(dir, "examplemod-1.2.3.jar", Map.of(
                "fabric.mod.json", json,
                "assets/examplemod/icon128.png", "PNGDATA"));

        ModJarInfo.Info info = ModJarInfo.read(jar);
        check("loader is Fabric", info.loader() == ModLoaderType.FABRIC);
        check("id/name/version", "examplemod".equals(info.id())
                && "Example Mod".equals(info.name()) && "1.2.3".equals(info.version()));
        check("authors joined", "Alice, Bob".equals(info.authors()));
        check("icon path taken from metadata",
                "assets/examplemod/icon128.png".equals(info.iconPath()));
        System.out.println("  game version specs: " + info.mcVersionSpecs());
        // Fabric puts the whole conjunction in one string (">=1.20.1 <1.21"), so
        // the list holds a single specifier; what matters is that it is honoured.
        check("minecraft range captured as a spec", !info.mcVersionSpecs().isEmpty());
        check("the conjunction is applied", info.supportsMcVersion("1.20.4"));
        check("a 1.21 game version is rejected", !info.supportsMcVersion("1.21"));
        List<ModJarInfo.Dependency> required = info.requiredMods();
        System.out.println("  required mods: " + required.stream().map(ModJarInfo.Dependency::id).toList());
        check("only real mods are required (no loader/java/minecraft)",
                required.size() == 2
                        && required.stream().anyMatch(d -> d.id().equals("fabric-api"))
                        && required.stream().anyMatch(d -> d.id().equals("cloth-config")));
        check("icon bytes can be read", ModJarInfo.readIcon(info) != null);
    }

    // ------------------------------------------------------------------- Forge

    private static void forgeChecks(Path dir) throws Exception {
        System.out.println();
        System.out.println("=== Forge (mods.toml) ===");
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"

                [[mods]]
                modId="forgeexample"
                version="2.0.0"
                displayName="Forge Example"
                authors="Carol"
                logoFile="biglogo.png"
                description="Forge test mod"

                [[dependencies.forgeexample]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                ordering="NONE"
                side="BOTH"

                [[dependencies.forgeexample]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"
                ordering="NONE"
                side="BOTH"

                [[dependencies.forgeexample]]
                modId="jei"
                mandatory=true
                versionRange="[15,)"
                ordering="AFTER"
                side="BOTH"

                [[dependencies.forgeexample]]
                modId="optionalmod"
                mandatory=false
                versionRange="[1,)"
                ordering="NONE"
                side="BOTH"
                """;
        Path jar = jar(dir, "forgeexample-2.0.0.jar", Map.of(
                "META-INF/mods.toml", toml,
                "biglogo.png", "PNGDATA"));

        ModJarInfo.Info info = ModJarInfo.read(jar);
        check("loader is Forge", info.loader() == ModLoaderType.FORGE);
        check("id/name/version from TOML", "forgeexample".equals(info.id())
                && "Forge Example".equals(info.name()) && "2.0.0".equals(info.version()));
        System.out.println("  game version specs: " + info.mcVersionSpecs());
        check("minecraft range captured", info.supportsMcVersion("1.20.6")
                && !info.supportsMcVersion("1.21"));
        List<ModJarInfo.Dependency> required = info.requiredMods();
        System.out.println("  required mods: " + required.stream().map(ModJarInfo.Dependency::id).toList());
        check("mandatory mod dependency kept, optional dropped",
                required.size() == 1 && required.get(0).id().equals("jei"));
        check("the loader itself is not treated as a mod dependency",
                required.stream().noneMatch(d -> d.id().equals("forge")));
        check("Forge icon read", ModJarInfo.readIcon(info) != null);
    }

    private static void neoForgeChecks(Path dir) throws Exception {
        System.out.println();
        System.out.println("=== NeoForge (neoforge.mods.toml) ===");
        String toml = """
                modLoader="javafml"
                loaderVersion="[1,)"

                [[mods]]
                modId="neoexample"
                version="3.1"
                displayName="Neo Example"

                [[dependencies.neoexample]]
                modId="neoforge"
                mandatory=true
                versionRange="[20.4,)"

                [[dependencies.neoexample]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.4,1.21)"
                """;
        Path jar = jar(dir, "neoexample-3.1.jar", Map.of("META-INF/neoforge.mods.toml", toml));
        ModJarInfo.Info info = ModJarInfo.read(jar);
        check("loader is NeoForge", info.loader() == ModLoaderType.NEO_FORGE);
        check("neoforge is not reported as a required mod", info.requiredMods().isEmpty());
        check("game version range respected", info.supportsMcVersion("1.20.4")
                && !info.supportsMcVersion("1.21"));
        check("loader name shown as NeoForge", "NeoForge".equals(info.loaderName()));
    }

    private static void quiltChecks(Path dir) throws Exception {
        System.out.println();
        System.out.println("=== Quilt ===");
        String json = """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "group": "org.example",
                    "id": "quiltexample",
                    "version": "0.5.0",
                    "metadata": {
                      "name": "Quilt Example",
                      "description": "Quilt test",
                      "icon": "iconq.png"
                    },
                    "depends": [
                      {"id": "quilt_loader", "versions": ">=0.19.0"},
                      {"id": "minecraft", "versions": ">=1.20"},
                      {"id": "quilted_fabric_api", "versions": ">=7.0.0"}
                    ]
                  }
                }
                """;
        Path jar = jar(dir, "quiltexample-0.5.0.jar", Map.of(
                "quilt.mod.json", json, "iconq.png", "PNGDATA"));
        ModJarInfo.Info info = ModJarInfo.read(jar);
        check("loader is Quilt", info.loader() == ModLoaderType.QUILT);
        check("id/name/version", "quiltexample".equals(info.id())
                && "Quilt Example".equals(info.name()) && "0.5.0".equals(info.version()));
        check("game version range respected", info.supportsMcVersion("1.20.4")
                && !info.supportsMcVersion("1.19.4"));
        List<ModJarInfo.Dependency> required = info.requiredMods();
        check("quilt loader filtered out, real dependency kept",
                required.size() == 1 && required.get(0).id().equals("quilted_fabric_api"));
    }

    private static void legacyForgeChecks(Path dir) throws Exception {
        System.out.println();
        System.out.println("=== legacy Forge (mcmod.info) ===");
        String json = """
                [{
                  "modid": "legacyexample",
                  "name": "Legacy Example",
                  "version": "1.0",
                  "mcversion": "1.12.2",
                  "authorList": ["Dave"],
                  "logoFile": "legacylogo.png",
                  "requiredMods": ["forge@[14.23.5.2847,)", "mixinbooter"]
                }]
                """;
        Path jar = jar(dir, "legacyexample-1.0.jar", Map.of(
                "mcmod.info", json, "legacylogo.png", "PNGDATA"));
        ModJarInfo.Info info = ModJarInfo.read(jar);
        check("loader is Forge", info.loader() == ModLoaderType.FORGE);
        check("id/name/version", "legacyexample".equals(info.id())
                && "Legacy Example".equals(info.name()) && "1.0".equals(info.version()));
        check("single game version pinned", info.supportsMcVersion("1.12.2")
                && !info.supportsMcVersion("1.20.1"));
        List<ModJarInfo.Dependency> required = info.requiredMods();
        System.out.println("  required mods: " + required.stream().map(ModJarInfo.Dependency::id).toList());
        check("forge filtered, mixinbooter kept",
                required.size() == 1 && required.get(0).id().equals("mixinbooter"));
    }

    private static void unknownChecks(Path dir) throws Exception {
        System.out.println();
        System.out.println("=== a jar with no metadata ===");
        Path jar = jar(dir, "mysterymod-0.1.jar", Map.of("some/Class.class", "bytes"));
        ModJarInfo.Info info = ModJarInfo.read(jar);
        check("loader is unknown", info.loader() == ModLoaderType.UNKNOWN);
        check("the file name is used as the name",
                "mysterymod-0.1".equals(info.name()));
        check("no version specs means 'unspecified', which matches anything",
                info.supportsMcVersion("1.20.1"));
        check("no dependencies claimed", info.requiredMods().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    /** Writes a jar containing the given entries. */
    private static Path jar(Path dir, String name, Map<String, String> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
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
