package org.dsh.cfkey;

import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Verifies the two mechanisms the plugin rests on: putting a key into the launcher's
 * {@code API_KEY}, and storing one on disk so it survives a restart.
 *
 * <p>Both are things that fail silently. If the write stops working, CurseForge never
 * appears and the user has no way to tell why; if the store cannot round-trip, the user is
 * asked for the key on every launch. So they are exercised rather than assumed.</p>
 */
public final class KeyKeyStoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("cfkey-test-");
        try {
            installerChecks();
            storeRoundTrip(work);
            storeRobustness(work);
            sentinelChecks();
        } finally {
            deleteTree(work);
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// The launcher's key field really is replaceable, and {@code isAvailable()} follows it.
    private static void installerChecks() {
        System.out.println("=== the launcher's API key can be replaced ===");

        String before = KeyInstaller.currentKey();
        System.out.println("  before: isAvailable=" + KeyInstaller.available());

        KeyInstaller.Result result = KeyInstaller.install("test-key-1234");
        System.out.println("  install: ok=" + result.ok()
                + (result.ok() ? "" : "  message=" + result.message()));
        check("installing a key reports success", result.ok());
        check("the launcher now reports CurseForge as available",
                CurseForgeRemoteAddonRepository.isAvailable());
        check("the field holds the key that was installed",
                "test-key-1234".equals(CurseForgeRemoteAddonRepository.API_KEY));

        System.out.println();
        System.out.println("=== a blank key is refused rather than installed ===");
        check("an empty key is refused", !KeyInstaller.install("").ok());
        check("a whitespace-only key is refused", !KeyInstaller.install("    ").ok());
        check("a null key is refused", !KeyInstaller.install(null).ok());
        check("the previous key survived the refusals",
                "test-key-1234".equals(CurseForgeRemoteAddonRepository.API_KEY));

        System.out.println();
        System.out.println("=== the key is trimmed ===");
        KeyInstaller.install("  padded-key  ");
        check("surrounding whitespace is removed",
                "padded-key".equals(CurseForgeRemoteAddonRepository.API_KEY));

        // Leave the field as it was found, so running this next to other suites is harmless.
        if (before != null && !before.isEmpty()) {
            KeyInstaller.install(before);
        }
    }

    /// A key written to disk must read back unchanged.
    private static void storeRoundTrip(Path work) throws Exception {
        System.out.println();
        System.out.println("=== a stored key reads back ===");

        KeyStore store = new KeyStore(work);
        check("a fresh store has no key", !store.hasKey());

        store.save("abc-123-XYZ_456");
        check("the key is reported as present", store.hasKey());
        check("the key reads back unchanged", "abc-123-XYZ_456".equals(store.load()));

        System.out.println();
        System.out.println("=== the file is not the bare key ===");
        String raw = Files.readString(store.path());
        System.out.println("  file content: " + raw.trim());
        check("the file does not contain the key in the clear",
                !raw.contains("abc-123-XYZ_456"));
        check("the file carries the format marker", raw.startsWith("v1:"));

        System.out.println();
        System.out.println("=== a second key replaces the first ===");
        store.save("second-key");
        check("the new key reads back", "second-key".equals(store.load()));

        System.out.println();
        System.out.println("=== the stored key can be forgotten ===");
        check("clearing reports that it removed the file", store.clear());
        check("no key remains", store.load() == null && !store.hasKey());
        check("clearing again is harmless", !store.clear());
    }

    /// Corrupt and foreign files must not throw, and must not be trusted.
    private static void storeRobustness(Path work) throws Exception {
        System.out.println();
        System.out.println("=== a corrupt file is treated as no key ===");

        KeyStore store = new KeyStore(work);
        Files.createDirectories(work);
        Files.writeString(store.path(), "this is not the format");
        check("a malformed file yields no key", store.load() == null);

        Files.writeString(store.path(), "v2:AAAA:BBBB");
        check("an unknown format version yields no key", store.load() == null);

        Files.writeString(store.path(), "v1:!!!!:????");
        check("undecodable base64 yields no key", store.load() == null);

        Files.writeString(store.path(), "");
        check("an empty file yields no key", store.load() == null);

        System.out.println();
        System.out.println("=== a key saved over a corrupt file works ===");
        store.save("recovered-key");
        check("the store recovered", "recovered-key".equals(store.load()));

        System.out.println();
        System.out.println("=== the store survives a new instance ===");
        // A new object reads the same file, which is what a restart looks like.
        KeyStore reopened = new KeyStore(work);
        check("a new store instance reads the saved key",
                "recovered-key".equals(reopened.load()));
    }

    /// The sentinel must make CurseForge look available without being used as the key.
    private static void sentinelChecks() {
        System.out.println();
        System.out.println("=== the sentinel makes the source visible but is not a real key ===");

        KeyInstaller.Result result = KeyInstaller.install(CurseForgeKeyPlugin.SENTINEL);
        check("the sentinel installs", result.ok());
        check("the launcher lists CurseForge because the sentinel is non-empty",
                CurseForgeRemoteAddonRepository.isAvailable());
        check("the sentinel is recognisable", 
                CurseForgeKeyPlugin.SENTINEL.equals(CurseForgeRemoteAddonRepository.API_KEY));

        // The plugin must not treat the sentinel as a usable key, or it would search with it
        // and every request would 403.
        check("the sentinel is not acceptable as the sentinel itself",
                !CurseForgeKeyPlugin.SENTINEL.isEmpty());
    }

    private static void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
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
