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

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Describes an addon (mod, resource pack or shader pack) that has been downloaded from a
/// remote source and saved into a game directory.
///
/// @param downloadProvider the download provider used for the download
/// @param repository       the game repository the addon was installed into
/// @param instanceId       the identifier of the target instance, or `null` when no instance is selected
/// @param addonVersion     the addon version that was downloaded
/// @param directory        the directory the addon file was saved into, e.g. the `mods` directory
@NotNullByDefault
public record PluginAddonEvent(
        DownloadProvider downloadProvider,
        HMCLGameRepository repository,
        @Nullable String instanceId,
        RemoteAddon.Version addonVersion,
        Path directory
) {
}
