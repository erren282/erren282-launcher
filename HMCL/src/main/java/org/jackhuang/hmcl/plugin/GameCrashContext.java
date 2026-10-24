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

import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.Log;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// Context of a game crash, handed to [PluginCrashAction]s shown on the crash window.
///
/// <p>All getters are safe to call from any thread; the joined log is computed lazily
/// and cached.</p>
@NotNullByDefault
public final class GameCrashContext {

    private final DefaultGameRepository repository;
    private final String versionId;
    private final @Nullable String gameVersion;
    private final Path runDirectory;
    private final String exitType;
    private final int exitCode;
    private final Map<String, String> loaders;
    private final List<Log> logs;

    private @Nullable String joinedLog;

    public GameCrashContext(DefaultGameRepository repository, String versionId, @Nullable String gameVersion,
                            Path runDirectory, String exitType, int exitCode,
                            Map<String, String> loaders, List<Log> logs) {
        this.repository = repository;
        this.versionId = versionId;
        this.gameVersion = gameVersion;
        this.runDirectory = runDirectory;
        this.exitType = exitType;
        this.exitCode = exitCode;
        this.loaders = loaders;
        this.logs = logs;
    }

    /// The instance id (version id) that crashed.
    public String getVersionId() {
        return versionId;
    }

    /// The Minecraft version of the instance, if known.
    public @Nullable String getGameVersion() {
        return gameVersion;
    }

    /// The run directory the game process was started with — the base `.minecraft`, which
    /// with version isolation is *not* where the instance's files live.
    public Path getRunDirectory() {
        return runDirectory;
    }

    /// The crash-stricken instance's own directory: `<runDir>/versions/<id>` when the
    /// instance is isolated, else the run directory. This is where its mods, configs and
    /// saves actually are, so plugins must act here rather than on [getRunDirectory].
    public Path getInstanceDirectory() {
        try {
            Path instance = repository.getRunDirectory(versionId);
            if (instance != null)
                return instance;
        } catch (Throwable ignored) {
            // fall through to the launch directory
        }
        return runDirectory;
    }

    /// The instance's mods directory (may not exist), resolved through the repository so
    /// version isolation and modpack layouts are honoured.
    public Path getModsDirectory() {
        try {
            Path mods = repository.getModsDirectory(versionId);
            if (mods != null)
                return mods;
        } catch (Throwable ignored) {
            // fall through to the launch directory
        }
        return runDirectory.resolve("mods");
    }

    /// How the game ended ("APPLICATION_ERROR", "JVM_ERROR", "SIGKILL").
    public String getExitType() {
        return exitType;
    }

    /// The game process exit code.
    public int getExitCode() {
        return exitCode;
    }

    /// Loader versions of the instance, e.g. `forge=47.2.0`, `fabric=0.15.11`.
    public Map<String, String> getLoaders() {
        return loaders;
    }

    /// All captured game output joined with newlines; can be large.
    public synchronized String getLog() {
        if (joinedLog == null)
            joinedLog = logs.stream().map(Log::getLog).collect(Collectors.joining("\n"));
        return joinedLog;
    }

    /// Content of the instance's `logs/latest.log`, or null when unreadable. With
    /// version isolation the game writes to `<runDir>/versions/<id>/logs/` while the
    /// crash window shows the base `.minecraft` — try both. The launcher launches the
    /// game with `-Dfile.encoding=GB18030` on Chinese Windows, so the file may be
    /// GB18030 rather than UTF-8; try UTF-8 first, then GB18030.
    public @Nullable String getLatestLog() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(getInstanceDirectory().resolve("logs/latest.log"));
        candidates.add(runDirectory.resolve("logs/latest.log"));
        candidates.add(runDirectory.resolve("versions").resolve(versionId).resolve("logs/latest.log"));
        for (Path latestLog : candidates) {
            if (!Files.isReadable(latestLog))
                continue;
            try {
                return Files.readString(latestLog);
            } catch (IOException ignored) {
            }
            try {
                return Files.readString(latestLog, java.nio.charset.Charset.forName("GB18030"));
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /// The launcher's own game repository, for advanced integrations.
    public DefaultGameRepository getRepository() {
        return repository;
    }
}
