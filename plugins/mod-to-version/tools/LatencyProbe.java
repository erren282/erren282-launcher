package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;

/**
 * Times the exact call the plugin makes when the button is clicked.
 *
 * <p>A diagnostic, not a test: it exists so the optimisation targets the slow step
 * instead of a guess. Run with a Modrinth project id or slug.</p>
 *
 * <pre>java ... org.dsh.modtoversion.LatencyProbe sodium</pre>
 */
public final class LatencyProbe {

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "sodium";
        var provider = DownloadProviders.getDownloadProvider();

        System.out.println("timing getRemoteVersionsById(" + id + ") — the click's slow step");
        for (int i = 1; i <= 3; i++) {
            long start = System.nanoTime();
            int count = -1;
            String note = "";
            try {
                count = ModrinthRemoteAddonRepository.MODS
                        .getRemoteVersionsById(provider, id).toList().size();
            } catch (Throwable t) {
                note = "  <- " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            System.out.printf("  attempt %d: %5d ms  (%d versions)%s%n",
                    i, (System.nanoTime() - start) / 1_000_000, count, note);
        }
    }
}
