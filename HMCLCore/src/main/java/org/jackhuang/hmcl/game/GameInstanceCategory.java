/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// The client kind a game instance belongs to.
///
/// <p>Mod loaders are detected by [LibraryAnalyzer], which inspects the version json (including
/// inherited patches) for well-known loader libraries. An instance carrying several loaders is
/// classified by the first match in the declaration order of this enum, so the order matters:
/// the more specific loaders come before the more generic ones.</p>
///
/// <p>[#VANILLA] is the fallback for instances without any recognized loader, and [#UNKNOWN]
/// marks instances whose version json could not be read at all - those two must stay last, as
/// they are matched by absence rather than by a library.</p>
@NotNullByDefault
public enum GameInstanceCategory {
    /// Lunar Client.
    ///
    /// <p>Lunar cannot be detected like the other entries: it ships its own patched game classes
    /// and declares no loader library at all, so a Lunar instance would otherwise look vanilla.
    /// It is recognized by the entry wrapper main class its launcher plugin writes into the
    /// version json, which is a stable integration contract between that plugin and this launcher
    /// (see `LunarVersionInstaller.WRAPPER_MAIN_CLASS` in the Lunar plugin).</p>
    LUNAR(null),
    /// Fabric.
    FABRIC(LibraryAnalyzer.LibraryType.FABRIC),
    /// Quilt.
    QUILT(LibraryAnalyzer.LibraryType.QUILT),
    /// Legacy Fabric (Fabric for 1.8.9 and older).
    LEGACY_FABRIC(LibraryAnalyzer.LibraryType.LEGACY_FABRIC),
    /// NeoForge.
    NEO_FORGE(LibraryAnalyzer.LibraryType.NEO_FORGE),
    /// Forge.
    FORGE(LibraryAnalyzer.LibraryType.FORGE),
    /// Cleanroom, the modern Forge fork for 1.12.2.
    CLEANROOM(LibraryAnalyzer.LibraryType.CLEANROOM),
    /// LiteLoader.
    LITELOADER(LibraryAnalyzer.LibraryType.LITELOADER),
    /// OptiFine. Not a mod loader proper, but a very common client patch users want to filter by.
    OPTIFINE(LibraryAnalyzer.LibraryType.OPTIFINE),
    /// Vanilla, or an instance with no recognized loader. Matched when no other category applies.
    VANILLA(null),
    /// The version json could not be read; classification is impossible.
    UNKNOWN(null);

    /// The main class of the entry wrapper the Lunar plugin puts in front of the game.
    private static final String LUNAR_WRAPPER_MAIN = "org.dsh.lunarofl.wrapper.GenesisLauncher";

    /// The library that identifies this kind, or `null` when matched by absence or by main class.
    private final LibraryAnalyzer.LibraryType libraryType;

    GameInstanceCategory(@Nullable LibraryAnalyzer.LibraryType libraryType) {
        this.libraryType = libraryType;
    }

    /// Classifies an instance from its resolved main class and its analyzed libraries.
    ///
    /// @param mainClass the resolved main class of the version, may be `null`
    /// @param analyzer  the analyzer of the resolved version, or `null` when the version json could
    ///                  not be read - which yields [#UNKNOWN]
    public static GameInstanceCategory from(@Nullable String mainClass, @Nullable LibraryAnalyzer analyzer) {
        if (mainClass != null && LUNAR_WRAPPER_MAIN.equals(mainClass))
            return LUNAR;
        if (analyzer == null)
            return UNKNOWN;
        for (GameInstanceCategory category : values()) {
            if (category.libraryType != null && analyzer.has(category.libraryType))
                return category;
        }
        return VANILLA;
    }

    /// The translation key describing this kind.
    public String getTranslationKey() {
        return "version.category." + name().toLowerCase(Locale.ROOT);
    }
}