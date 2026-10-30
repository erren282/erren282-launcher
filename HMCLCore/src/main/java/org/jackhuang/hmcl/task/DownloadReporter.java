/*
 * HMCL 下载上报
 *
 * 本文件属于启动器，负责把下载任务的状态转成 DownloadEvent 广播出去。
 */
package org.jackhuang.hmcl.task;

import org.jackhuang.hmcl.event.DownloadEvent;
import org.jackhuang.hmcl.event.EventBus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Map;
import java.util.WeakHashMap;

/// Turns download task state into [DownloadEvent]s.
///
/// <p>Plugins cannot reach into `Task` internals, so the launcher publishes what they need: which
/// file is being fetched, how far along it is, and how it ended. Events are fired from the task's
/// own thread; listeners that touch the UI are expected to marshal themselves.</p>
///
/// <p>A registry keyed by task identity remembers the display name and size, because a task does
/// not carry them and re-deriving them from the URL would be guesswork.</p>
@NotNullByDefault
public final class DownloadReporter {

    /// Byte totals seen for the running tasks, used to compute the reported size.
    private static final Map<Task<?>, long[]> TOTALS = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /// The version most recently being installed, such as `1.20.1`.
    ///
    /// <p>Only the first stage of an install carries the version (`hmcl.install.game:1.20.1`);
    /// the library and asset stages are bare keys. Remembering the last one seen lets files that
    /// arrive under a bare stage still be attributed to the install that is running.</p>
    private static volatile @Nullable String currentVersion;

    private DownloadReporter() {
    }

    /// Publishes the start of a download.
    ///
    /// @param task the task that started
    public static void started(Task<?> task) {
        try {
            String url = firstUrl(task);
            String name = displayName(task, url);
            long total = knownTotal(task);
            TOTALS.put(task, new long[]{0, total});

            // Two things have to happen for a cancel to be a real cancel:
            //
            //   - stop the run, so the files queued behind this one never start — they are separate
            //     tasks, and interrupting this thread leaves them untouched;
            //   - interrupt this thread, so the transfer already in flight stops now instead of
            //     finishing.
            Thread worker = Thread.currentThread();
            TaskExecutor owner = task.getOwnerExecutor();
            Runnable cancel = () -> {
                if (owner != null) {
                    try {
                        owner.cancel();
                    } catch (Throwable ignored) {
                        // An executor that never started cannot be cancelled; the interrupt below
                        // still covers the file in flight.
                    }
                }
                worker.interrupt();
            };

            fire(new DownloadEvent(task, DownloadEvent.Phase.STARTED, url, name, target(task),
                    0, total, 0, null, batchOf(task), cancel));
        } catch (Throwable ignored) {
            // Reporting must never break a download.
        }
    }

    /// Deletes a partially written file.
    ///
    /// <p>Called after a cancel so a half-finished download does not sit on disk pretending to be
    /// complete. The launcher writes downloads to a temporary file and only moves it into place at
    /// the end, so removing the destination is right for both cases.</p>
    ///
    /// @param path the file to remove
    public static void discardPartial(java.nio.file.Path path) {
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (Throwable ignored) {
            // A file that cannot be removed is better than an exception here.
        }
    }

    /// Returns the batch a task belongs to.
    ///
    /// <p>A game install reports its stage as `hmcl.install.game:1.20.1` — the part after the colon
    /// is the version being installed. Everything that install fetches shares that stage, so the
    /// version name identifies the whole batch and the download list shows one row reading
    /// "1.20.1" instead of hundreds of library files.</p>
    ///
    /// <p>The stage is passed through untranslated: this module has no access to the launcher's
    /// resource bundles, and the consumer renders it.</p>
    ///
    /// @param task the task
    /// @return the batch name, or `null` for a standalone download
    private static @Nullable String batchOf(Task<?> task) {
        String stage = task.getInheritedStage();
        if (stage == null || stage.isBlank())
            stage = task.getStage();
        if (stage == null || stage.isBlank())
            return null;

        if (!stage.startsWith("hmcl.install"))
            return null;

        // `hmcl.install.game:1.20.1` names the target version; remember it so later stages, which
        // are bare keys like `hmcl.install.assets`, still belong to the same install.
        int colon = stage.indexOf(':');
        if (colon >= 0 && colon + 1 < stage.length()) {
            currentVersion = stage.substring(colon + 1);
            return currentVersion;
        }

        return currentVersion;
    }

    /// Publishes a progress update.
    ///
    /// @param task     the task
    /// @param received bytes transferred so far
    /// @param total    total bytes, or `0` when unknown
    public static void progress(Task<?> task, long received, long total) {
        try {
            long[] state = TOTALS.get(task);
            if (state == null)
                return;
            state[0] = received;
            if (total > 0)
                state[1] = total;

            fire(new DownloadEvent(task, DownloadEvent.Phase.PROGRESS, firstUrl(task),
                    displayName(task, firstUrl(task)), target(task),
                    received, state[1], 0, null, batchOf(task), null));
        } catch (Throwable ignored) {
            // Reporting must never break a download.
        }
    }

    /// Publishes the successful end of a download.
    ///
    /// @param task the task that finished
    public static void finished(Task<?> task) {
        try {
            long[] state = TOTALS.get(task);
            long total = state == null ? 0 : state[1];

            fire(new DownloadEvent(task, DownloadEvent.Phase.FINISHED, firstUrl(task),
                    displayName(task, firstUrl(task)), target(task),
                    total, total, 0, null, batchOf(task), null));
            TOTALS.remove(task);
        } catch (Throwable ignored) {
            // Reporting must never break a download.
        }
    }

    /// Publishes the failure of a download.
    ///
    /// @param task    the task that failed
    /// @param message the reason
    public static void failed(Task<?> task, @Nullable String message) {
        try {
            long[] state = TOTALS.get(task);
            long received = state == null ? 0 : state[0];
            long total = state == null ? 0 : state[1];

            fire(new DownloadEvent(task, DownloadEvent.Phase.FAILED, firstUrl(task),
                    displayName(task, firstUrl(task)), target(task),
                    received, total, 0, message, batchOf(task), null));
            TOTALS.remove(task);
        } catch (Throwable ignored) {
            // Reporting must never break a download.
        }
    }

    /// Fires one event on the bus.
    ///
    /// @param event the event
    private static void fire(DownloadEvent event) {
        EventBus.EVENT_BUS.fireEvent(event);
    }

    /// Returns the first source URL of a task.
    ///
    /// @param task the task
    /// @return the URL, or an empty string
    private static String firstUrl(Task<?> task) {
        if (task instanceof FetchTask<?> fetch && !fetch.uris.isEmpty())
            return fetch.uris.get(0).toString();
        return "";
    }

    /// Returns the destination of a download.
    ///
    /// @param task the task
    /// @return the path as text, or an empty string
    private static String target(Task<?> task) {
        if (task instanceof FileDownloadTask download) {
            var path = download.getPath();
            if (path != null)
                return path.toString();
        }
        return "";
    }

    /// Picks a display name for a download.
    ///
    /// @param task the task
    /// @param url  its first URL
    /// @return the name
    private static String displayName(Task<?> task, String url) {
        String target = target(task);
        if (!target.isBlank()) {
            int slash = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
            if (slash >= 0 && slash + 1 < target.length())
                return target.substring(slash + 1);
        }

        String name = task.getName();
        if (name != null && !name.isBlank())
            return name;

        if (!url.isBlank()) {
            int slash = url.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < url.length())
                return url.substring(slash + 1);
        }
        return "下载";
    }

    /// Returns the size a task already knows about.
    ///
    /// @param task the task
    /// @return the total bytes, or `0`
    private static long knownTotal(Task<?> task) {
        try {
            if (task instanceof FileDownloadTask download) {
                var path = download.getPath();
                if (path != null && java.nio.file.Files.isRegularFile(path))
                    return java.nio.file.Files.size(path);
            }
        } catch (Throwable ignored) {
            // Unknown is fine.
        }
        return 0;
    }
}
