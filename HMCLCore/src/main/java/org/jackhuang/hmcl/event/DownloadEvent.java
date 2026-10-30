/*
 * HMCL 下载事件
 *
 * 本文件属于启动器，用于把下载任务的状态广播给插件。
 */
package org.jackhuang.hmcl.event;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Broadcasts the lifecycle of a download so plugins can observe it.
///
/// <p>Before this existed, downloads were plain tasks with no global entry point: a plugin could
/// see the aggregate speed but never learn which files were being fetched, when one started, or
/// whether it succeeded. Firing an event per state change gives plugins a supported way to show a
/// download list without reaching into internal task state.</p>
///
/// <p>The launcher itself does not consume these events; they exist for extensions.</p>
@NotNullByDefault
public final class DownloadEvent extends Event {

    /// What happened.
    public enum Phase {
        /// A download was queued.
        STARTED,
        /// Bytes arrived; carries the running totals.
        PROGRESS,
        /// The file is complete.
        FINISHED,
        /// The download stopped because of an error.
        FAILED
    }

    private final Phase phase;
    private final String url;
    private final String name;
    private final String target;
    private final long received;
    private final long total;
    private final long speed;
    private final @Nullable String message;
    private final @Nullable String batch;
    private final @Nullable Runnable cancelAction;

    /// Creates an event.
    ///
    /// @param source   the object that produced it
    /// @param phase    what happened
    /// @param url      the source URL
    /// @param name     a display name for the download
    /// @param target   the destination path
    /// @param received bytes transferred so far
    /// @param total    total bytes, or `0` when unknown
    /// @param speed    current rate in bytes per second, or `0`
    /// @param message  an error message, or `null`
    /// @param batch    the enclosing batch, or `null` for a standalone file
    /// @param cancelAction stops the download, or `null` when it cannot be stopped
    public DownloadEvent(Object source, Phase phase, String url, String name, String target,
                         long received, long total, long speed, @Nullable String message,
                         @Nullable String batch, @Nullable Runnable cancelAction) {
        super(source);
        this.phase = phase;
        this.url = url;
        this.name = name;
        this.target = target;
        this.received = received;
        this.total = total;
        this.speed = speed;
        this.message = message;
        this.batch = batch;
        this.cancelAction = cancelAction;
    }

    /// Returns an action that stops this download.
    ///
    /// <p>A plugin can only observe events; it has no handle on the task itself. Supplying the
    /// stop action with the event is what lets the download list offer a working cancel button.</p>
    ///
    /// @return the action, or `null` when the download cannot be stopped
    public @Nullable Runnable getCancelAction() {
        return cancelAction;
    }

    /// Returns the batch this download belongs to.
    ///
    /// <p>A game install or modpack fetches hundreds of files. They all carry the same batch name,
    /// so a UI can show one row per install instead of one per file.</p>
    ///
    /// @return the batch name, or `null` for a standalone file
    public @Nullable String getBatch() {
        return batch;
    }

    /// Returns what happened.
    ///
    /// @return the phase
    public Phase getPhase() {
        return phase;
    }

    /// Returns the source URL.
    ///
    /// @return the URL
    public String getUrl() {
        return url;
    }

    /// Returns the display name.
    ///
    /// @return the name
    public String getName() {
        return name;
    }

    /// Returns the destination path as text.
    ///
    /// @return the target
    public String getTarget() {
        return target;
    }

    /// Returns the transferred byte count.
    ///
    /// @return the bytes received
    public long getReceived() {
        return received;
    }

    /// Returns the total size.
    ///
    /// @return the total bytes, or `0` when unknown
    public long getTotal() {
        return total;
    }

    /// Returns the transfer rate.
    ///
    /// @return bytes per second, or `0`
    public long getSpeed() {
        return speed;
    }

    /// Returns the failure reason.
    ///
    /// @return the message, or `null`
    public @Nullable String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "DownloadEvent[" + phase + ", " + name + ", " + received + "/" + total + "]";
    }
}
