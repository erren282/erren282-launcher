/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.game.GameRepository;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;

/// Information about one game launch, passed to plugin launch hooks.
///
/// <p>Components may be {@code null} when the launch context is used outside a real launch,
/// e.g. in tests or before the game repository is resolved.</p>
///
/// @see HmclPlugin#onBeforeLaunch(LaunchContext)
/// @see HmclPlugin#onAfterLaunch(LaunchContext, org.jackhuang.hmcl.util.platform.ManagedProcess)
@NotNullByDefault
public final class LaunchContext {

    private final @Nullable GameRepository repository;
    private final @Nullable String versionId;
    private final @Nullable String gameVersion;
    private final @Nullable Path runDirectory;
    private final @Nullable LaunchOptions launchOptions;

    /// Creates a launch context for the given game launch.
    public LaunchContext(@Nullable GameRepository repository, @Nullable String versionId,
                         @Nullable String gameVersion, @Nullable Path runDirectory,
                         @Nullable LaunchOptions launchOptions) {
        this.repository = repository;
        this.versionId = versionId;
        this.gameVersion = gameVersion;
        this.runDirectory = runDirectory;
        this.launchOptions = launchOptions;
    }

    /// Returns the game repository managing the launched instance, or `null` if unavailable.
    public @Nullable GameRepository getRepository() {
        return repository;
    }

    /// Returns the identifier of the launched version, e.g. `1.21.4`, or `null` if unavailable.
    public @Nullable String getVersionId() {
        return versionId;
    }

    /// Returns the resolved game version, or `null` if unavailable.
    public @Nullable String getGameVersion() {
        return gameVersion;
    }

    /// Returns the working directory the game will run in, or `null` if unavailable.
    public @Nullable Path getRunDirectory() {
        return runDirectory;
    }

    /// Returns the launch options of this launch.
    ///
    /// <p>Launch options are read-only snapshots; plugins must not mutate them.</p>
    public @Nullable LaunchOptions getLaunchOptions() {
        return launchOptions;
    }

    /// Returns the resolved game version, or an empty optional when unavailable.
    public Optional<String> gameVersion() {
        return Optional.ofNullable(gameVersion);
    }
}
