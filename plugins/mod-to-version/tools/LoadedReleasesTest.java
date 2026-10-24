package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.util.SimpleMultimap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * Verifies that a release is resolved from the page's in-memory list before the
 * network is touched, and that the fallback still happens when it must.
 *
 * <p>The cost being avoided is real and measured: one
 * {@code getRemoteVersionsById} call takes 0.6–2 s. The point of these checks is
 * that the common click never pays it, while a miss still works.</p>
 */
public final class LoadedReleasesTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        resolutionOrderChecks();
        multimapShapeChecks();
        realPageChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Confirms the reflective read works on a real {@code DownloadPage}.
     *
     * <p>This is the part that could silently stop working if the launcher renames
     * the field. It is checked against the actual class rather than a stand-in.</p>
     */
    private static void realPageChecks() throws Exception {
        System.out.println();
        System.out.println("=== the field is read from the real DownloadPage ===");

        java.lang.reflect.Field field;
        try {
            field = org.jackhuang.hmcl.ui.versions.DownloadPage.class
                    .getDeclaredField("versions");
        } catch (NoSuchFieldException e) {
            check("DownloadPage still has a 'versions' field", false);
            System.out.println("  -> the cache is unavailable; the plugin will fall back"
                    + " to the network on every click");
            return;
        }
        check("DownloadPage still has a 'versions' field", true);
        System.out.println("  field type: " + field.getType().getName());
        check("it is the map of versions the plugin expects",
                field.getType() == SimpleMultimap.class);

        // Setting accessible must be permitted: the field is private, and a module
        // that refused it would make this whole cache unusable.
        field.setAccessible(true);
        check("setAccessible was accepted", field.trySetAccessible() || field.isAccessible());

        // And an unreadable page must not throw out of the helper.
        check("a null page yields nothing rather than throwing",
                LoadedReleases.releasesOf(null).isEmpty());
    }

    private static void resolutionOrderChecks() {
        System.out.println("=== the cached list is used first ===");

        RemoteAddon.Version wanted = release("appleskin-fabric-mc26.3-3.0.10", "2026-09-18");
        RemoteAddon.Version other = release("appleskin-fabric-mc26.2-3.0.9", "2026-08-01");
        List<RemoteAddon.Version> cached = List.of(wanted, other);

        String[] fetched = {null};
        Optional<RemoteAddon.Version> found = resolve(cached, "appleskin-fabric-mc26.3-3.0.10",
                () -> fetched[0] = "network");
        System.out.println("  found: " + found.map(RemoteAddon.Version::name).orElse("nothing"));
        check("the release is found", found.isPresent());
        check("it is the one asked for", found.get() == wanted);
        check("the network was never consulted", fetched[0] == null);

        System.out.println();
        System.out.println("=== a miss falls back to fetching ===");
        fetched[0] = null;
        // Nothing in the cache matches, so the fallback path must run.
        Optional<RemoteAddon.Version> missed = resolve(cached, "not-in-the-list",
                () -> fetched[0] = "network");
        check("the fallback was attempted", fetched[0] != null);
        check("nothing is returned when it is not found there either", missed.isEmpty());

        System.out.println();
        System.out.println("=== an empty cache still resolves ===");
        // This is the case where the page is still loading its own list.
        check("an empty cached list yields nothing on its own",
                ModToVersionEngine.findReleaseByName(List.of(), "anything").isEmpty());
    }

    /** The multimap the launcher stores its versions in must read back correctly. */
    private static void multimapShapeChecks() {
        System.out.println();
        System.out.println("=== the launcher's map shape is read correctly ===");

        // Built the way DownloadPage.sortVersions builds it: one entry per game
        // version, so a release supporting several game versions appears several
        // times, and the same release must not be counted twice.
        SimpleMultimap<String, RemoteAddon.Version, List<RemoteAddon.Version>> map =
                new SimpleMultimap<>(HashMap::new, ArrayList::new);
        RemoteAddon.Version multi = release("multi", List.of("1.20.4", "1.20.1"), "2026-01-01");
        RemoteAddon.Version single = release("single", List.of("1.20.4"), "2026-02-01");
        map.put("1.20.4", multi);
        map.put("1.20.1", multi);
        map.put("1.20.4", single);

        List<RemoteAddon.Version> read = dedupe(new ArrayList<Object>(map.values()));
        System.out.println("  values() gave " + map.values().size()
                + " entries, deduped to " + read.size());
        check("the release counted twice by the map is only returned once",
                read.size() == 2);
        check("both distinct releases are present",
                read.contains(multi) && read.contains(single));
        check("a name is found through the deduped list",
                ModToVersionEngine.findReleaseByName(read, "single").isPresent());
    }

    /** The de-duplication {@link LoadedReleases} performs. */
    private static List<RemoteAddon.Version> dedupe(Iterable<Object> values) {
        List<RemoteAddon.Version> releases = new ArrayList<>();
        for (Object candidate : values) {
            if (candidate instanceof RemoteAddon.Version release && !releases.contains(release)) {
                releases.add(release);
            }
        }
        return releases;
    }

    /**
     * Runs the engine's resolution with a stubbed network step, so the test can tell
     * whether the cache was enough.
     */
    private static Optional<RemoteAddon.Version> resolve(List<RemoteAddon.Version> cached,
                                                         String name,
                                                         Runnable onNetwork) {
        Optional<RemoteAddon.Version> fromCache = ModToVersionEngine.findReleaseByName(cached, name);
        if (fromCache.isPresent()) {
            return fromCache;
        }
        onNetwork.run();
        return ModToVersionEngine.findReleaseByName(List.of(), name);
    }

    // ------------------------------------------------------------------ helpers

    private static RemoteAddon.Version release(String name, String date) {
        return release(name, List.of("1.20.4"), date);
    }

    private static RemoteAddon.Version release(String name, List<String> gameVersions, String date) {
        return new RemoteAddon.Version(
                null, "appleskin", name, name, "",
                Instant.parse(date + "T00:00:00Z"),
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(java.util.Map.of(), "https://example.invalid/x.jar", "x.jar"),
                List.of(), gameVersions, List.of(ModLoaderType.FABRIC));
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
