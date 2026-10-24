package org.dsh.cfkey;

import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.util.io.NetworkUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Verifies that installing a key also refreshes the launcher's network layer.
 *
 * <h2>The bug this pins down</h2>
 * <p>{@code NetworkUtils} does not read {@code API_KEY} per request. It snapshots it once,
 * in a static initialiser:</p>
 * <pre>
 *   static {
 *       if (CurseForgeRemoteAddonRepository.API_KEY.isEmpty())
 *           API_KEYS = List.of();                         // empty, permanently
 *       else
 *           API_KEYS = List.of(pair("api.curseforge.com", ...), ...);
 *   }
 * </pre>
 * <p>On a build without a compiled-in key, that class is already initialised by the time a
 * plugin runs, so writing {@code API_KEY} made CurseForge <em>appear</em> but every request
 * still went out without the header and got {@code 403}. A user with a completely valid key
 * was told it had been rejected. The installer now rebuilds the table too, and this test
 * measures exactly that.</p>
 */
public final class NetworkSyncTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== the network table starts out consistent with the field ===");

        // Force NetworkUtils to initialise now, which is the state a running launcher is in
        // by the time a plugin gets control. It must be initialised BEFORE the key is set,
        // or the bug would not reproduce.
        Class.forName("org.jackhuang.hmcl.util.io.NetworkUtils");
        List<?> before = table();
        System.out.println("  API_KEYS before = " + before);
        check("the table exists", before != null);

        System.out.println();
        System.out.println("=== installing a key refreshes the table ===");
        KeyInstaller.Result result = KeyInstaller.install("sync-test-key");
        System.out.println("  install ok = " + result.ok()
                + (result.ok() ? "" : "  message=" + result.message()));
        check("installing reports success", result.ok());

        List<?> after = table();
        System.out.println("  API_KEYS after  = " + after);
        check("the table is no longer empty", after != null && !after.isEmpty());
        check("the table has the two hosts the launcher knows about", after.size() == 2);
        check("it carries the new key", after.toString().contains("sync-test-key"));
        check("it covers the API host",
                after.toString().contains("api.curseforge.com"));
        check("it covers the CDN host", after.toString().contains("forgecdn.net"));

        System.out.println();
        System.out.println("=== the launcher's own getter agrees ===");
        check("isAvailable() is true", CurseForgeRemoteAddonRepository.isAvailable());
        check("API_KEY holds the key",
                "sync-test-key".equals(CurseForgeRemoteAddonRepository.API_KEY));

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// Reads {@code NetworkUtils.API_KEYS} reflectively.
    private static List<?> table() throws Exception {
        Field field = NetworkUtils.class.getDeclaredField("API_KEYS");
        field.setAccessible(true);
        Object value = field.get(null);
        return value instanceof List<?> list ? list : null;
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
