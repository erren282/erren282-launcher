/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.addon;

import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface RemoteAddonRepository {

    enum Type {
        MOD,
        MODPACK,
        RESOURCE_PACK,
        SHADER_PACK,
        /// A world's data pack: functions, advancements, recipes, loot tables.
        ///
        /// <p>Kept apart from [RESOURCE_PACK] because the two are not interchangeable: a data
        /// pack goes into a world's `datapacks/` folder and is loaded by the server-side half
        /// of the game, while a resource pack goes into `resourcepacks/` and only affects what
        /// the client draws. Installing one as the other produces a folder the game ignores.</p>
        DATA_PACK,
        WORLD,
        CUSTOMIZATION
    }

    Type getType();

    enum SortType {
        POPULARITY,
        NAME,
        DATE_CREATED,
        LAST_UPDATED,
        AUTHOR,
        TOTAL_DOWNLOADS
    }

    enum SortOrder {
        ASC,
        DESC
    }

    class SearchResult {
        private final Stream<RemoteAddon> sortedResults;

        private final Stream<RemoteAddon> unsortedResults;

        private final int totalPages;

        public SearchResult(Stream<RemoteAddon> sortedResults, Stream<RemoteAddon> unsortedResults, int totalPages) {
            this.sortedResults = sortedResults;
            this.unsortedResults = unsortedResults;
            this.totalPages = totalPages;
        }

        public SearchResult(Stream<RemoteAddon> sortedResults, int pages) {
            this.sortedResults = sortedResults;
            this.unsortedResults = sortedResults;
            this.totalPages = pages;
        }

        public Stream<RemoteAddon> getResults() {
            return this.sortedResults;
        }

        public Stream<RemoteAddon> getUnsortedResults() {
            return this.unsortedResults;
        }

        public int getTotalPages() {
            return this.totalPages;
        }
    }

    SearchResult search(DownloadProvider downloadProvider, String gameVersion, @Nullable Category category, int pageOffset, int pageSize, String searchFilter, SortType sortType, SortOrder sortOrder)
            throws IOException;

    /// Searches restricted to one mod loader.
    ///
    /// <p>Mods and modpacks are almost always loader-specific, so browsing without this filter
    /// returns a mix that the user has to pick through. Repositories that can express the filter
    /// server-side override this and narrow the query itself; the default deliberately ignores the
    /// loader rather than failing, and callers treat `null` as "any loader".</p>
    ///
    /// @param gameVersion the Minecraft version, or blank for all
    /// @param category     the category filter, or `null`
    /// @param modLoader    the loader to restrict to, or `null` for any
    /// @return the results
    default SearchResult search(DownloadProvider downloadProvider, String gameVersion,
                                @Nullable Category category, int pageOffset, int pageSize,
                                String searchFilter, SortType sortType, SortOrder sortOrder,
                                @Nullable ModLoaderType modLoader) throws IOException {
        return search(downloadProvider, gameVersion, category, pageOffset, pageSize,
                searchFilter, sortType, sortOrder);
    }

    Optional<RemoteAddon.Version> getRemoteVersionByLocalFile(Path file) throws IOException;

    RemoteAddon getModById(DownloadProvider downloadProvider, String id) throws IOException;

    default RemoteAddon resolveDependency(DownloadProvider downloadProvider, String id) throws IOException {
        return getModById(downloadProvider, id);
    }

    RemoteAddon.File getModFile(String modId, String fileId) throws IOException;

    Stream<RemoteAddon.Version> getRemoteVersionsById(DownloadProvider downloadProvider, String id) throws IOException;

    Stream<Category> getCategories() throws IOException;

    record Category(Object self, String id, List<Category> subcategories) {
    }
}
