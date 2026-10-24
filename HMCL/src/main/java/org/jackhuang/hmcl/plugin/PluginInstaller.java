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

import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Installs plugin jars on behalf of the user interface.
///
/// <p>Used by the plugin page import actions and by drag and drop on the launcher window.
/// Installation runs in the background; the outcome is reported with a toast or an error
/// dialog once it finishes.</p>
@NotNullByDefault
public final class PluginInstaller {

    private PluginInstaller() {
    }

    /// Installs the given plugin jars asynchronously and reloads the plugins afterwards.
    ///
    /// <p>Every jar is validated and copied into the plugin directory; jars that fail are
    /// collected and reported together in one error dialog. When at least one jar was
    /// installed, the plugin list is reloaded.</p>
    ///
    /// @param jars the plugin jars to install
    public static void installWithToast(List<Path> jars) {
        if (jars.isEmpty())
            return;

        Task.supplyAsync(() -> {
            List<String> installed = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            for (Path jar : jars) {
                try {
                    installed.add(PluginManager.install(jar).getName());
                } catch (Exception e) {
                    failures.add(jar.getFileName() + ": " + describe(e));
                }
            }

            if (!installed.isEmpty())
                PluginManager.reload();

            return new InstallResult(installed, failures);
        }).whenComplete(Schedulers.javafx(), (result, exception) -> {
            if (result == null) {
                Controllers.dialog(exception != null ? describe(exception) : i18n("plugin.install.failed"),
                        i18n("plugin.install.failed"), MessageType.ERROR);
                return;
            }

            if (!result.failures().isEmpty()) {
                Controllers.dialog(String.join("\n", result.failures()),
                        i18n("plugin.install.failed"), MessageType.ERROR);
            }

            if (result.installed().size() == 1) {
                Controllers.showToast(i18n("plugin.install.success", result.installed().get(0)));
            } else if (result.installed().size() > 1) {
                Controllers.showToast(i18n("plugin.install.count", result.installed().size()));
            }
        }).start();
    }

    /// Describes an exception with its message, falling back to the class name.
    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && !message.isBlank() ? message : throwable.getClass().getSimpleName();
    }

    /// The outcome of one installation batch.
    private record InstallResult(List<String> installed, List<String> failures) {
    }
}
