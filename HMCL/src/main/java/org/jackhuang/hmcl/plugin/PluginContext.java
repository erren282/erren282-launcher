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

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The services the launcher provides to one plugin instance.
///
/// <p>Methods are safe to call from any thread; UI operations are forwarded to the JavaFX thread.</p>
///
/// <p>Plugins that run with [PluginAccess#ROOT] (the default) can additionally obtain
/// [LauncherRoot] through [PluginContext#getRoot], which exposes the whole launcher process:
/// settings, accounts, game directories, launching, window control, plugin management and
/// shutdown.</p>
@NotNullByDefault
public final class PluginContext {

    /// The version of the plugin API implemented by this launcher build.
    ///
    /// <p>Plugins may compare this value against the API level they were written for. The
    /// value is independent from [Metadata#VERSION] and only changes when the plugin-facing
    /// surface changes.</p>
    public static final int API_VERSION = 2;

    private final String pluginId;
    private final Path dataDirectory;
    private final PluginAccess accessLevel;
    private final LauncherRoot root;

    /// Creates a context for the plugin with the given identifier.
    ///
    /// @param pluginId      the plugin identifier
    /// @param dataDirectory the private data directory of the plugin
    public PluginContext(String pluginId, Path dataDirectory) {
        this(pluginId, dataDirectory, PluginAccess.ROOT);
    }

    /// Creates a context for the plugin with the given identifier and access level.
    ///
    /// @param pluginId      the plugin identifier
    /// @param dataDirectory the private data directory of the plugin
    /// @param accessLevel   the privilege level granted to the plugin
    public PluginContext(String pluginId, Path dataDirectory, PluginAccess accessLevel) {
        this.pluginId = pluginId;
        this.dataDirectory = dataDirectory;
        this.accessLevel = accessLevel;
        this.root = new LauncherRoot(this);
    }

    /// Returns the identifier of the plugin this context belongs to.
    public String getPluginId() {
        return pluginId;
    }

    /// Returns the plugin API version implemented by this launcher, see [PluginContext#API_VERSION].
    public int getApiVersion() {
        return API_VERSION;
    }

    /// Returns the privilege level granted to the plugin.
    public PluginAccess getAccessLevel() {
        return accessLevel;
    }

    /// Returns the root service object of the launcher.
    ///
    /// <p>A plugin declared as [PluginAccess#STANDARD] gets an [IllegalStateException] because
    /// it may not touch launcher internals; [PluginAccess#ELEVATED] and [PluginAccess#ROOT]
    /// get the object.</p>
    ///
    /// @return the launcher root services
    /// @throws IllegalStateException when the plugin's access level is [PluginAccess#STANDARD]
    public LauncherRoot getRoot() {
        if (!accessLevel.isAtLeast(PluginAccess.ELEVATED))
            throw new IllegalStateException("Plugin " + pluginId + " runs with access level "
                    + accessLevel + "; declare \"access\": \"root\" in " + PluginDescription.MANIFEST_FILE_NAME
                    + " to use the launcher root API");
        return root;
    }

    /// Returns the private data directory of the plugin, creating it if necessary.
    ///
    /// <p>The directory survives plugin reloads and is the right place to store persistent state.</p>
    public Path getDataDirectory() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (java.io.IOException e) {
            LOG.warning("Failed to create data directory of plugin " + pluginId, e);
        }
        return dataDirectory;
    }

    /// Resolves a path inside the private data directory, creating the directory if necessary.
    ///
    /// @param name a relative file name, e.g. `config.json`
    public Path getDataFile(String name) {
        return getDataDirectory().resolve(name);
    }

    /// Logs an informational message through the launcher logger.
    public void logInfo(String message) {
        LOG.info("[" + pluginId + "] " + message);
    }

    /// Logs a warning message through the launcher logger.
    public void logWarning(String message) {
        LOG.warning("[" + pluginId + "] " + message);
    }

    /// Logs an error message through the launcher logger.
    public void logError(String message, Throwable throwable) {
        LOG.error("[" + pluginId + "] " + message, throwable);
    }

    /// Shows an information dialog on the launcher UI.
    public void showMessage(String title, String message) {
        FXUtils.runInFX(() -> Controllers.dialog(message, title));
    }

    /// Shows a warning dialog on the launcher UI.
    public void showWarning(String title, String message) {
        FXUtils.runInFX(() -> Controllers.dialog(message, title, MessageType.WARNING));
    }

    /// Shows an error dialog on the launcher UI.
    public void showError(String title, String message) {
        FXUtils.runInFX(() -> Controllers.dialog(message, title, MessageType.ERROR));
    }

    /// Shows a short toast on the launcher UI.
    public void showToast(String message) {
        FXUtils.runInFX(() -> Controllers.showToast(message));
    }

    /// Asks the user a yes/no question and runs the matching callback on the JavaFX thread.
    ///
    /// @param title   the dialog title
    /// @param message the question
    /// @param yes     invoked when the user confirms, may be `null`
    /// @param no      invoked when the user cancels, may be `null`
    public void confirm(String title, String message, @Nullable Runnable yes, @Nullable Runnable no) {
        FXUtils.runInFX(() -> Controllers.confirm(message, title, yes, no));
    }

    /// Opens the given link with the system browser.
    public void openLink(String link) {
        FXUtils.runInFX(() -> FXUtils.openLink(link));
    }

    /// Opens the given directory with the system file manager.
    public void openDirectory(Path directory) {
        FXUtils.runInFX(() -> FXUtils.openFolder(directory));
    }

    /// Puts text on the system clipboard.
    public void copyToClipboard(String text) {
        FXUtils.runInFX(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    /// Runs the action on the JavaFX application thread.
    public void runInFX(Runnable action) {
        FXUtils.runInFX(action);
    }

    /// Runs the action on a launcher-owned background thread.
    public void runAsync(Runnable action) {
        root.runAsync(action);
    }

    /// Returns the version string of the running launcher, e.g. `3.16.4`.
    public String getLauncherVersion() {
        return Metadata.VERSION;
    }

    @Override
    public String toString() {
        return "PluginContext[" + pluginId + ", access=" + accessLevel + "]";
    }
}
