package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Verifies the two rules the world install path depends on: the suggested folder name
 * has to be usable as a directory name, and the temporary download has to keep the
 * archive's extension.
 *
 * <p>Both are silent failure modes. A name containing {@code /} or {@code :} would throw
 * an {@code InvalidPathException} deep inside the unpack step, and an archive saved as
 * {@code .tmp} would not be recognised by the zip file system — the user would just see
 * “installation failed” with no hint why.</p>
 */
public final class WorldNameTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        suggestedNameChecks();
        extensionChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void suggestedNameChecks() {
        System.out.println("=== the suggested world folder name ===");

        check("a plain name is kept as-is",
                "SkyBlock Adventure".equals(
                        AddonInstaller.suggestedWorldName(release("SkyBlock Adventure", "w.zip"))));
        check("a slash is replaced",
                !AddonInstaller.suggestedWorldName(release("Sky/Block", "w.zip")).contains("/"));
        check("a backslash is replaced",
                !AddonInstaller.suggestedWorldName(release("Sky\\Block", "w.zip")).contains("\\"));
        check("a colon is replaced",
                !AddonInstaller.suggestedWorldName(release("Sky: Block", "w.zip")).contains(":"));
        check("a star is replaced",
                !AddonInstaller.suggestedWorldName(release("Sky*Block", "w.zip")).contains("*"));
        check("a question mark is replaced",
                !AddonInstaller.suggestedWorldName(release("Sky?Block", "w.zip")).contains("?"));
        check("a quote is replaced",
                !AddonInstaller.suggestedWorldName(release("Sky\"Block", "w.zip")).contains("\""));
        check("angle brackets are replaced",
                !AddonInstaller.suggestedWorldName(release("Sky<Block>", "w.zip"))
                        .contains("<"));

        System.out.println();
        System.out.println("=== runs of spaces are collapsed ===");
        // Replacing a forbidden character with a space can leave a double space, which is
        // legal but ugly in the world list.
        String collapsed = AddonInstaller.suggestedWorldName(release("Sky:: Block", "w.zip"));
        System.out.println("  [" + collapsed + "]");
        check("no double spaces remain", !collapsed.contains("  "));
        check("the name is not blank", !collapsed.isBlank());

        System.out.println();
        System.out.println("=== degenerate input ===");
        check("a name of only separators still yields something",
                !AddonInstaller.suggestedWorldName(release("///", "w.zip")).isBlank());
        check("a null release yields an empty suggestion",
                AddonInstaller.suggestedWorldName(null).isEmpty());
        check("a padded name is trimmed",
                "SkyBlock".equals(
                        AddonInstaller.suggestedWorldName(release("  SkyBlock  ", "w.zip"))));
    }

    private static void extensionChecks() {
        System.out.println();
        System.out.println("=== the temporary archive keeps its extension ===");

        check("a zip keeps .zip",
                ".zip".equals(AddonInstaller.extensionOf(release("W", "world.zip"))));
        check("an uppercase extension is preserved",
                ".ZIP".equals(AddonInstaller.extensionOf(release("W", "world.ZIP"))));
        check("a jar-like extension is preserved",
                ".jar".equals(AddonInstaller.extensionOf(release("W", "w.jar"))));
        check("a dot in a directory is not mistaken for the extension",
                ".zip".equals(AddonInstaller.extensionOf(release("W", "a.b/world"))));

        System.out.println();
        System.out.println("=== degenerate file names fall back to .zip ===");
        check("no extension yields .zip",
                ".zip".equals(AddonInstaller.extensionOf(release("W", "world"))));
        check("a trailing dot yields .zip",
                ".zip".equals(AddonInstaller.extensionOf(release("W", "world."))));
        check("an absurdly long extension yields .zip",
                ".zip".equals(AddonInstaller.extensionOf(
                        release("W", "world.abcdefghijklmnop"))));
        check("a null release yields .zip", ".zip".equals(AddonInstaller.extensionOf(null)));
    }

    /// A release whose file carries the given name.
    private static RemoteAddon.Version release(String name, String filename) {
        return new RemoteAddon.Version(
                null, "example", name, name, "",
                Instant.parse("2026-05-01T00:00:00Z"),
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/" + filename, filename),
                List.of(), List.of("1.20.4"), List.of());
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
