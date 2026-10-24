/*
 * Hello Minecraft! Launcher plugin: lunar-offline
 * Copyright (C) 2026 dsh
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
package org.dsh.lunarofl;

import org.jackhuang.hmcl.download.RemoteVersion;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// One entry of the Lunar version list, shaped like the launcher's own [RemoteVersion].
///
/// <p>The launcher's version download page renders `RemoteVersion` rows and lets the user filter
/// them the same way, so modelling a Lunar release as a `RemoteVersion` is what makes the Lunar
/// page behave identically down to the list cell, the search box and the category filter. The
/// originals live in the launcher's own version lists; this one is produced by [LunarApi].</p>
@NotNullByDefault
public final class LunarRemoteVersion extends RemoteVersion {

    /// The library id used by the launcher's list cell to tell "game" versions from loaders.
    public static final String LIBRARY_ID = "lunar";

    private final LunarApi.SubVersion lunar;

    LunarRemoteVersion(LunarApi.SubVersion lunar) {
        // gameVersion = the Minecraft version this Lunar build ships (its parent, e.g. 1.8),
        // selfVersion = the Lunar release (e.g. 1.8.9) - the same split the launcher uses so the
        // version number is what the list shows and the game version is what tags display.
        super(LIBRARY_ID,
                lunar.parentId(),
                lunar.id(),
                null,
                Type.RELEASE,
                List.of());
        this.lunar = lunar;
    }

    /// The underlying Lunar release, carrying its modules.
    public LunarApi.SubVersion getLunar() {
        return lunar;
    }

    /// The parent game version label the API reports (e.g. `1.8`), or `null` when absent.
    public @Nullable String getParentName() {
        return lunar.parentName();
    }

    /// Whether this is the release the API marks as the default choice.
    public boolean isDefaultChoice() {
        return lunar.isDefault();
    }
}
