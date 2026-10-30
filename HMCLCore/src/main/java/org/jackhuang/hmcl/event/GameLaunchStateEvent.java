/*
 * HMCL 启动状态事件
 *
 * 本文件属于启动器，用于把游戏启动的开始与结束广播给插件。
 */
package org.jackhuang.hmcl.event;

import org.jetbrains.annotations.NotNullByDefault;

/// Broadcasts the start and end of a game launch.
///
/// <p>Plugins that add launcher UI need to know when the game is starting: actions that would
/// interfere — opening a page, switching versions — should be unavailable while a launch is in
/// flight, and the UI should show that something is happening.</p>
@NotNullByDefault
public final class GameLaunchStateEvent extends Event {

    /// What happened.
    public enum Phase {
        /// The game is about to start.
        STARTING,
        /// The process is running, or the launch finished.
        FINISHED
    }

    private final Phase phase;
    private final String versionId;

    /// Creates an event.
    ///
    /// @param source    the object that produced it
    /// @param phase     what happened
    /// @param versionId the version being launched
    public GameLaunchStateEvent(Object source, Phase phase, String versionId) {
        super(source);
        this.phase = phase;
        this.versionId = versionId;
    }

    /// Returns what happened.
    ///
    /// @return the phase
    public Phase getPhase() {
        return phase;
    }

    /// Returns the version being launched.
    ///
    /// @return the version id
    public String getVersionId() {
        return versionId;
    }

    @Override
    public String toString() {
        return "GameLaunchStateEvent[" + phase + ", " + versionId + "]";
    }
}
