package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.ui.versions.DownloadPage;
import org.jackhuang.hmcl.util.SimpleMultimap;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the release list the launcher has <em>already</em> loaded.
 *
 * <h2>Why this exists</h2>
 * <p>Opening a mod's page makes the launcher fetch that mod's entire version list,
 * and it keeps the result in {@code DownloadPage.versions}. Clicking
 * 「下载到对应版本」 needs exactly that list. Asking the network for it again costs
 * 0.6–2 seconds on every click — measured, not assumed — for data already sitting
 * in memory.</p>
 *
 * <h2>How it is read</h2>
 * <p>The field is private with no getter, so it is read reflectively. That is
 * deliberate and contained here:</p>
 * <ul>
 *   <li>it is only a <em>cache</em>: when anything about it fails — the field is
 *       renamed, access is refused, the list is not loaded yet — {@link #releasesOf}
 *       returns an empty list and the caller falls back to the network. The feature
 *       keeps working, just slower.</li>
 *   <li>nothing is written back, so the launcher's own state is untouched.</li>
 * </ul>
 *
 * <p>The values are {@code RemoteAddon.Version} objects already filtered and sorted
 * by the launcher, keyed by game version.</p>
 */
final class LoadedReleases {

    /// Cached reflective handle; resolved once, then reused.
    private static Field versionsField;

    /// True when the field has been looked up and could not be used.
    private static boolean unavailable;

    private LoadedReleases() {
    }

    /**
     * The releases the page already has, or an empty list when they cannot be read.
     *
     * <p>An empty result is not an error: the page may still be loading its own
     * list, in which case the caller should fall back to fetching.</p>
     */
    static List<RemoteAddon.Version> releasesOf(DownloadPage page) {
        if (page == null || unavailable) {
            return List.of();
        }
        try {
            Object value = field().get(page);
            if (!(value instanceof SimpleMultimap<?, ?, ?> multimap)) {
                return List.of();
            }
            List<RemoteAddon.Version> releases = new ArrayList<>();
            for (Object candidate : multimap.values()) {
                if (candidate instanceof RemoteAddon.Version release && !releases.contains(release)) {
                    releases.add(release);
                }
            }
            return releases;
        } catch (Throwable t) {
            // Missing field, denied access, or a shape change: give up on the cache
            // for the rest of the session rather than throwing on every click.
            unavailable = true;
            versionsField = null;
            return List.of();
        }
    }

    /** True when the cache can be read at all (used by the tests). */
    static boolean isAvailable() {
        return !unavailable;
    }

    private static Field field() throws NoSuchFieldException {
        Field cached = versionsField;
        if (cached != null) {
            return cached;
        }
        Field field = DownloadPage.class.getDeclaredField("versions");
        field.setAccessible(true);
        versionsField = field;
        return field;
    }
}
