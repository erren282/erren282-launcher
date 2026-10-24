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

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.jackhuang.hmcl.EntryPoint;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.LauncherState;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.UserSettings;
import org.jackhuang.hmcl.setting.UserState;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jackhuang.hmcl.util.Restarter;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The root service object handed to plugins that run with [PluginAccess#ROOT].
///
/// <p>This is the "highest privilege" surface of the launcher plugin API: everything the
/// launcher itself can do to its own process and state is reachable from here without
/// reflection and without knowing internal class names.</p>
///
/// <p>Threading: methods that touch JavaFX marshal themselves through
/// [Platform#runLater]; everything else runs on the calling thread. Blocking calls (game
/// launching, restarts) return immediately and do their work on launcher-owned threads.</p>
///
/// @see PluginContext#getRoot()
@NotNullByDefault
public final class LauncherRoot {

    /// Executor used by [LauncherRoot#runAsync] so plugin work never blocks the launcher.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "hmcl-plugin-root");
        thread.setDaemon(true);
        return thread;
    });

    private final PluginContext context;

    /// Creates the root service for one plugin context; created by the plugin manager.
    ///
    /// @param context the owning plugin context
    public LauncherRoot(PluginContext context) {
        this.context = context;
    }

    // ---------------------------------------------------------------- identity

    /// Returns the identifier of the plugin that owns this object.
    public String getPluginId() {
        return context.getPluginId();
    }

    /// Returns the privilege level of the owning plugin.
    public PluginAccess getAccess() {
        return context.getAccessLevel();
    }

    /// Returns the launcher version, e.g. `3.16.4`.
    public String getLauncherVersion() {
        return Metadata.VERSION;
    }

    /// Returns the path of the running launcher jar, or `null` when it cannot be resolved.
    public @Nullable Path getLauncherJarPath() {
        return JarUtils.thisJarPath();
    }

    /// Returns the launcher working directory (the folder containing `.hmcl`).
    public Path getWorkingDirectory() {
        return Metadata.CURRENT_DIRECTORY;
    }

    /// Returns `.hmcl` (or the directory selected by `hmcl.dir`).
    public Path getLocalHome() {
        return Metadata.HMCL_LOCAL_HOME;
    }

    /// Returns the user-level launcher directory.
    public Path getUserHome() {
        return Metadata.HMCL_USER_HOME;
    }

    /// Returns the directory plugin jars are loaded from.
    public Path getPluginsDirectory() {
        return PluginManager.getPluginsDirectory();
    }

    /// Returns the class loader that loaded the launcher itself.
    public ClassLoader getLauncherClassLoader() {
        return LauncherRoot.class.getClassLoader();
    }

    /// Reads a resource bundled in the launcher jar, e.g. `assets/docs/plugin/plugin-api.md`.
    ///
    /// @param resource the absolute resource path
    /// @return the bytes, or `null` when the resource does not exist
    public byte @Nullable [] readLauncherResource(String resource) throws IOException {
        String path = resource.startsWith("/") ? resource : "/" + resource;
        try (InputStream input = LauncherRoot.class.getResourceAsStream(path)) {
            return input != null ? input.readAllBytes() : null;
        }
    }

    /// Returns the live JVM system properties (mutable, exactly like the launcher sees them).
    public Properties getSystemProperties() {
        return System.getProperties();
    }

    // ---------------------------------------------------------------- threading

    /// Runs the action on the JavaFX application thread.
    public void runInFX(Runnable action) {
        FXUtils.runInFX(action);
    }

    /// Runs the action on a launcher-owned daemon thread.
    public void runAsync(Runnable action) {
        EXECUTOR.execute(action);
    }

    /// Runs the supplier on a launcher-owned daemon thread.
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, EXECUTOR);
    }

    /// Whether the calling thread is the JavaFX application thread.
    public boolean isFXThread() {
        return Platform.isFxApplicationThread();
    }

    // ---------------------------------------------------------------- window / UI

    /// Returns the primary launcher window, or `null` before it is created.
    public @Nullable Stage getStage() {
        return Controllers.getStage();
    }

    /// Returns the launcher scene, or `null` before it is created.
    public @Nullable Scene getScene() {
        return Controllers.getScene();
    }

    /// Navigates the launcher window to the given page node.
    public void navigate(Node page) {
        Controllers.navigate(page);
    }

    /// Shows a short toast at the bottom of the launcher window.
    public void showToast(String message) {
        FXUtils.runInFX(() -> Controllers.showToast(message));
    }

    /// Shows a launcher-styled dialog.
    public void showDialog(String title, String message, MessageType type) {
        FXUtils.runInFX(() -> Controllers.dialog(message, title, type));
    }

    /// Shows a task dialog driven by a launcher task and returns the executor.
    public void runTask(org.jackhuang.hmcl.task.Task<?> task, String title) {
        FXUtils.runInFX(() -> Controllers.taskDialog(task, title, TaskCancellationAction.NORMAL));
    }

    // ---------------------------------------------------------------- settings

    /// Returns the live per-workspace launcher settings.
    public LauncherSettings getSettings() {
        return SettingsManager.settings();
    }

    /// Returns the live user-level settings.
    public UserSettings getUserSettings() {
        return SettingsManager.userSettings();
    }

    /// Returns the live per-workspace launcher state.
    public LauncherState getState() {
        return SettingsManager.state();
    }

    /// Returns the live user-level state.
    public UserState getUserState() {
        return SettingsManager.userState();
    }

    // ---------------------------------------------------------------- accounts

    /// Returns the live account list; mutate it on the JavaFX thread.
    public ObservableList<Account> getAccounts() {
        return Accounts.getAccounts();
    }

    /// Returns the currently selected account, or `null`.
    public @Nullable Account getSelectedAccount() {
        return Accounts.getSelectedAccount();
    }

    /// Selects an account; the change is persisted by the launcher.
    public void setSelectedAccount(@Nullable Account account) {
        Accounts.setSelectedAccount(account);
    }

    // ---------------------------------------------------------------- game directories

    /// Returns every known game directory.
    public ObservableList<GameDirectory> getGameDirectories() {
        return GameDirectoryManager.getGameDirectories();
    }

    /// Returns the selected game directory, or `null` before the launcher finished starting.
    public @Nullable GameDirectory getSelectedGameDirectory() {
        try {
            return GameDirectoryManager.getSelectedGameDirectory();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /// Returns the repository of the selected game directory, or `null` before the launcher
    /// finished starting.
    public @Nullable HMCLGameRepository getSelectedRepository() {
        try {
            return GameDirectoryManager.getSelectedRepository();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /// Selects a game directory by id.
    public void setSelectedGameDirectory(GameDirectory gameDirectory) {
        GameDirectoryManager.setSelectedGameDirectory(gameDirectory);
    }

    /// Returns the selected instance id of the selected game directory, or `null`.
    public @Nullable String getSelectedInstance() {
        try {
            return GameDirectoryManager.getSelectedInstance();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /// Selects an instance in the selected game directory; ignored while the launcher is
    /// still starting and no repository exists yet.
    public void setSelectedInstance(@Nullable String instanceId) {
        HMCLGameRepository repository = getSelectedRepository();
        if (repository == null) {
            context.logWarning("setSelectedInstance() called before the launcher finished starting");
            return;
        }
        repository.setSelectedInstance(instanceId);
    }

    /// Returns the ids of every instance in the selected game directory; empty while the
    /// launcher is still starting.
    public List<String> getInstanceIds() {
        HMCLGameRepository repository = getSelectedRepository();
        if (repository == null)
            return List.of();
        return repository.getVersions().stream().map(version -> version.getId()).toList();
    }

    // ---------------------------------------------------------------- launching

    /// Launches an instance of the selected game directory, asking for an account first.
    ///
    /// @param instanceId the instance id, or `null` to launch the selected instance
    public void launchGame(@Nullable String instanceId) {
        HMCLGameRepository repository = getSelectedRepository();
        if (repository == null) {
            context.logWarning("launchGame() called before the launcher finished starting");
            return;
        }
        String id = instanceId != null ? instanceId : repository.getSelectedInstance();
        if (id == null) {
            context.logWarning("launchGame() called without an instance id");
            return;
        }
        FXUtils.runInFX(() -> Versions.launch(repository, id));
    }

    /// Returns the number of game processes started by this launcher instance.
    public int countGameProcesses() {
        return LauncherHelper.countMangedProcesses();
    }

    /// Kills every game process started by this launcher instance.
    public void stopGameProcesses() {
        LauncherHelper.stopManagedProcesses();
    }

    /// Wraps an already spawned process so plugins can reuse the launcher process helper.
    ///
    /// @param process the process
    /// @param name    a display name used when the process has no command line
    public ManagedProcess manageProcess(Process process, String name) {
        return new ManagedProcess(process,
                process.info().commandLine().map(List::of).orElseGet(() -> List.of(name)));
    }

    // ---------------------------------------------------------------- plugin management

    /// Returns every plugin known to the launcher.
    public List<PluginHandle> getPlugins() {
        return PluginManager.getPlugins();
    }

    /// Enables or disables another plugin and persists the choice.
    public boolean setPluginEnabled(String pluginId, boolean enabled) {
        return PluginManager.setEnabled(pluginId, enabled);
    }

    /// Unloads and reloads every plugin jar.
    public void reloadPlugins() {
        PluginManager.reload();
    }

    /// Installs a plugin jar into the plugin directory.
    public PluginDescription installPlugin(Path jar) throws IOException {
        return PluginManager.install(jar);
    }

    // ---------------------------------------------------------------- lifecycle

    /// Restarts the launcher process.
    public void restartLauncher() {
        FXUtils.runInFX(() -> {
            Controllers.onApplicationStop();
            try {
                org.jackhuang.hmcl.util.FileSaver.waitForAllSaves();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                Restarter.restartSelf();
            } catch (IOException e) {
                LOG.warning("Failed to restart self", e);
            }
            Platform.exit();
        });
    }

    /// Stops the launcher process.
    ///
    /// @param exitCode the process exit code
    public void shutdownLauncher(int exitCode) {
        FXUtils.runInFX(() -> {
            Controllers.onApplicationStop();
            Launcher.stopApplication();
            EntryPoint.exit(exitCode);
        });
    }

    @Override
    public String toString() {
        return "LauncherRoot[" + context.getPluginId() + ", access=" + context.getAccessLevel() + "]";
    }
}
