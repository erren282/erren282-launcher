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
package org.jackhuang.hmcl.addon.repository;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.util.*;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.io.ResponseCodeException;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Pair.pair;
import static org.jackhuang.hmcl.util.gson.JsonUtils.listTypeOf;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class ModrinthRemoteAddonRepository implements RemoteAddonRepository {
    public static final ModrinthRemoteAddonRepository MODS = new ModrinthRemoteAddonRepository("mod");
    public static final ModrinthRemoteAddonRepository MODPACKS = new ModrinthRemoteAddonRepository("modpack");
    public static final ModrinthRemoteAddonRepository RESOURCE_PACKS = new ModrinthRemoteAddonRepository("resourcepack");
    public static final ModrinthRemoteAddonRepository SHADER_PACKS = new ModrinthRemoteAddonRepository("shader");

    private static final Comparator<String> TAG_COMPARATOR = PriorityComparator.of(
            List.of("babric",
                    "bta-babric",
                    "bukkit",
                    "bungeecord",
                    "canvas",
                    "datapack",
                    "fabric",
                    "folia",
                    "forge",
                    "geyser",
                    "iris",
                    "java-agent",
                    "legacy-fabric",
                    "liteloader",
                    "minecraft",
                    "modloader",
                    "mrpack",
                    "neoforge",
                    "nilloader",
                    "optifine",
                    "ornith",
                    "paper",
                    "purpur",
                    "quilt",
                    "rift",
                    "spigot",
                    "sponge",
                    "vanilla",
                    "velocity",
                    "waterfall"),
            Comparator.naturalOrder(),
            false
    );

    private static final Semaphore SEMAPHORE = new Semaphore(16);

    /// The official Modrinth API base.
    static final String OFFICIAL_API = "https://api.modrinth.com";

    /// The official Modrinth CDN base, used for file downloads.
    static final String OFFICIAL_CDN = "https://cdn.modrinth.com";

    /// MCIM mirror bases, from <https://www.mcimirror.top/guide/platform/modrinth>.
    ///
    /// The mirror replaces `api.modrinth.com` with `mod.mcimirror.top/modrinth` and
    /// `cdn.modrinth.com` with `mod.mcimirror.top`. Measured from here, the API is
    /// about 2.7x faster (127 ms vs 347 ms median).
    static final String MIRROR_API = "https://mod.mcimirror.top/modrinth";
    static final String MIRROR_CDN = "https://mod.mcimirror.top";

    /// Whether API requests go to the mirror first.
    ///
    /// <p>Kept as a mutable flag rather than a `final String` so it can be turned off
    /// at runtime: `-Dhmcl.modrinth.mirror=false`, or by a plugin. A `static final
    /// String` would be inlined by javac and could not be changed after compilation.</p>
    private static volatile boolean mirrorEnabled = !"false".equalsIgnoreCase(
            System.getProperty("hmcl.modrinth.mirror", "true"));

    /// The API bases to try, most preferred first.
    ///
    /// <p>The mirror comes first so the common case is the fast one. The official API
    /// stays as a fallback: the mirror answers 404 for a project it has not cached yet
    /// and queues the fetch, so falling back keeps that from looking like a failure.</p>
    static List<String> apiBases() {
        return mirrorEnabled
                ? List.of(MIRROR_API, OFFICIAL_API)
                : List.of(OFFICIAL_API);
    }

    /// The first API base to use.
    private static String apiBase() {
        return apiBases().get(0);
    }

    /// Whether the mirror is in use.
    public static boolean isMirrorEnabled() {
        return mirrorEnabled;
    }

    /// Turns the mirror on or off at runtime.
    public static void setMirrorEnabled(boolean enabled) {
        mirrorEnabled = enabled;
    }

    /// Rewrites a CDN file URL onto the mirror.
    ///
    /// <p>Only the API is worth mirroring: the mirror answers a CDN path with a 302 to
    /// the original `cdn.modrinth.com`, so file downloads are not faster through it.
    /// This is here for completeness and stays off by default.</p>
    static String mirrorCdn(String cdnUrl) {
        if (cdnUrl != null && cdnUrl.startsWith(OFFICIAL_CDN)) {
            return MIRROR_CDN + cdnUrl.substring(OFFICIAL_CDN.length());
        }
        return cdnUrl;
    }

    /// Builds the request candidates for an API path.
    ///
    /// <p>Every API base is paired with the caller's `DownloadProvider`, so the
    /// launcher's own mirroring still applies on top (for example BMCLAPI's mapping of
    /// other hosts). The result keeps the preferred order and drops duplicates.</p>
    ///
    /// @param path an API path beginning with `/v2/...`
    private static List<URI> apiCandidates(DownloadProvider downloadProvider, String path) {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        for (String base : apiBases()) {
            result.addAll(downloadProvider.injectURLWithCandidates(base + path));
        }
        return List.copyOf(result);
    }

    private final String projectType;

    private final RemoteAddonRepository.Type type;

    private ModrinthRemoteAddonRepository(String projectType) {
        this.projectType = projectType;
        this.type = switch (projectType) {
            case "modpack" -> Type.MODPACK;
            case "resourcepack" -> Type.RESOURCE_PACK;
            case "shader" -> Type.SHADER_PACK;
            default -> Type.MOD;
        };
    }

    @Override
    public Type getType() {
        return this.type;
    }

    private static String convertSortType(SortType sortType) {
        return switch (sortType) {
            case DATE_CREATED -> "newest";
            case POPULARITY, NAME, AUTHOR -> "relevance";
            case LAST_UPDATED -> "updated";
            case TOTAL_DOWNLOADS -> "downloads";
            default -> throw new IllegalArgumentException("Unsupported sort type " + sortType);
        };
    }

    static List<String> sortDisplayCategories(List<String> displayCategories) {
        return displayCategories != null && !displayCategories.isEmpty()
                ? displayCategories.stream().sorted(TAG_COMPARATOR).toList()
                : List.of();
    }

    @Override
    public SearchResult search(DownloadProvider downloadProvider, String gameVersion, @Nullable RemoteAddonRepository.Category category, int pageOffset, int pageSize, String searchFilter, SortType sort, SortOrder sortOrder) throws IOException {
        return search(downloadProvider, gameVersion, category, pageOffset, pageSize, searchFilter, sort, sortOrder, null);
    }

    @Override
    public SearchResult search(DownloadProvider downloadProvider, String gameVersion, @Nullable RemoteAddonRepository.Category category, int pageOffset, int pageSize, String searchFilter, SortType sort, SortOrder sortOrder, @Nullable ModLoaderType modLoader) throws IOException {
        SEMAPHORE.acquireUninterruptibly();
        try {
            List<List<String>> facets = new ArrayList<>();
            facets.add(Collections.singletonList("project_type:" + projectType));
            if (StringUtils.isNotBlank(gameVersion)) {
                facets.add(Collections.singletonList("versions:" + gameVersion));
            }
            if (category != null && StringUtils.isNotBlank(category.id())) {
                facets.add(Collections.singletonList("categories:" + category.id()));
            }
            // The loader is a facet like any other. Modrinth treats several values inside one
            // facet group as OR and separate groups as AND, which is exactly the wanted behaviour:
            // "this loader" combined with the version and category constraints.
            if (modLoader != null) {
                String facetName = toModrinthLoader(modLoader);
                if (facetName != null)
                    facets.add(Collections.singletonList("categories:" + facetName));
            }
            Map<String, String> query = mapOf(
                    pair("query", searchFilter),
                    pair("facets", JsonUtils.UGLY_GSON.toJson(facets)),
                    pair("offset", Integer.toString(pageOffset * pageSize)),
                    pair("limit", Integer.toString(pageSize)),
                    pair("index", convertSortType(sort))
            );

            List<URI> candidates = apiCandidates(downloadProvider,
                    NetworkUtils.withQuery("/v2/search", query));
            IOException exception = null;
            for (URI candidate : candidates) {
                try {
                    LOG.info("Fetching " + candidate);
                    Response<ProjectSearchResult> response = HttpRequest.GET(candidate.toString())
                            .getJson(Response.typeOf(ProjectSearchResult.class));
                    return new SearchResult(response.getHits().stream().map(ProjectSearchResult::toMod), (int) Math.ceil((double) response.totalHits / pageSize));
                } catch (IOException e) {
                    LOG.warning("Failed to search addons: " + candidate, e);

                    IOException wrapper = new IOException("Failed to search addons: " + candidate, e);
                    if (candidates.size() == 1) {
                        exception = wrapper;
                    } else {
                        if (exception == null) {
                            exception = new IOException("Failed to search addons");
                        }
                        exception.addSuppressed(wrapper);
                    }
                }
            }

            throw exception != null ? exception : new IOException("No candidates found");
        } finally {
            SEMAPHORE.release();
        }
    }

    /// Maps a loader to the Modrinth category name used to filter by it.
    ///
    /// <p>Modrinth models loaders as ordinary categories, so restricting a search to one loader is
    /// the same operation as selecting its category. Loaders Modrinth has no name for map to
    /// `null`, which leaves the search unfiltered rather than returning nothing.</p>
    ///
    /// @param loader the loader
    /// @return the category name, or `null` when Modrinth cannot express it
    private static @Nullable String toModrinthLoader(ModLoaderType loader) {
        return switch (loader) {
            case FABRIC -> "fabric";
            case FORGE -> "forge";
            case NEO_FORGE -> "neoforge";
            case QUILT -> "quilt";
            case LITE_LOADER -> "liteloader";
            case LEGACY_FABRIC -> "legacy-fabric";
            // Cleanroom runs Forge mods and has no Modrinth category of its own.
            case CLEANROOM -> null;
            case UNKNOWN -> null;
        };
    }

    @Override
    public Optional<RemoteAddon.Version> getRemoteVersionByLocalFile(Path file) throws IOException {
        String sha1 = DigestUtils.digestToString("SHA-1", file);

        SEMAPHORE.acquireUninterruptibly();
        try {
            ProjectVersion mod = HttpRequest.GET(apiBase() + "/v2/version_file/" + sha1,
                            pair("algorithm", "sha1"))
                    .getJson(ProjectVersion.class);
            return mod.toVersion();
        } catch (ResponseCodeException e) {
            if (e.getResponseCode() == 404) {
                return Optional.empty();
            } else {
                throw e;
            }
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } finally {
            SEMAPHORE.release();
        }
    }

    @Override
    public RemoteAddon getModById(DownloadProvider downloadProvider, String id) throws IOException {
        SEMAPHORE.acquireUninterruptibly();
        try {
            id = StringUtils.removePrefix(id, "local-");
            List<URI> candidates = apiCandidates(downloadProvider, "/v2/project/" + id);
            IOException exception = null;

            for (URI candidate : candidates) {
                try {
                    Project project = HttpRequest.GET(candidate.toString()).getJson(Project.class);
                    return project.toMod();
                } catch (IOException e) {
                    IOException wrapper = new IOException("Failed to get mod: " + candidate, e);
                    if (candidates.size() == 1) {
                        exception = wrapper;
                    } else {
                        if (exception == null) {
                            exception = new IOException("Failed to get mod");
                        }
                        exception.addSuppressed(wrapper);
                    }
                }
            }

            throw exception != null ? exception : new IOException("No candidates found");
        } finally {
            SEMAPHORE.release();
        }
    }

    @Override
    public RemoteAddon resolveDependency(DownloadProvider downloadProvider, String id) throws IOException {
        try {
            return getModById(downloadProvider, id);
        } catch (ResponseCodeException e) {
            if (e.getResponseCode() == 502 || e.getResponseCode() == 404) {
                return RemoteAddon.BROKEN;
            }
            throw e;
        } catch (FileNotFoundException e) {
            return RemoteAddon.BROKEN;
        }
    }

    @Override
    public RemoteAddon.File getModFile(String modId, String fileId) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Stream<RemoteAddon.Version> getRemoteVersionsById(DownloadProvider downloadProvider, String id) throws IOException {
        SEMAPHORE.acquireUninterruptibly();
        try {
            id = StringUtils.removePrefix(id, "local-");

            List<URI> candidates = apiCandidates(downloadProvider,
                    "/v2/project/" + id + "/version?include_changelog=false");
            IOException exception = null;

            for (URI candidate : candidates) {
                try {
                    List<ProjectVersion> versions = HttpRequest.GET(candidate.toString())
                            .getJson(listTypeOf(ProjectVersion.class));
                    return versions.stream().map(ProjectVersion::toVersion).flatMap(Lang::toStream);
                } catch (IOException e) {
                    IOException wrapper = new IOException("Failed to get remote versions: " + candidate, e);
                    if (candidates.size() == 1) {
                        exception = wrapper;
                    } else {
                        if (exception == null) {
                            exception = new IOException("Failed to get remote versions");
                        }
                        exception.addSuppressed(wrapper);
                    }
                }
            }

            throw exception != null ? exception : new IOException("No candidates found");
        } finally {
            SEMAPHORE.release();
        }
    }

    @Override
    public Stream<RemoteAddonRepository.Category> getCategories() throws IOException {
        SEMAPHORE.acquireUninterruptibly();
        try {
            List<Category> categories = HttpRequest.GET(apiBase() + "/v2/tag/category").getJson(listTypeOf(Category.class));
            return categories.stream()
                    .filter(category -> category.projectType().equals(projectType))
                    .map(Category::toCategory);
        } finally {
            SEMAPHORE.release();
        }
    }

    public record Category(String icon, String name, @SerializedName("project_type") String projectType) {
        public Category() {
            this("", "", "");
        }

        public RemoteAddonRepository.Category toCategory() {
            return new RemoteAddonRepository.Category(
                    this,
                    name,
                    Collections.emptyList());
        }
    }

    /**
     * @param body A long body describing project in detail.
     */
    public record Project(String slug, String title, String description, List<String> categories, String body,
                          @SerializedName("project_type") String projectType, int downloads,
                          @SerializedName("icon_url") String iconUrl, String id, String team, Instant published,
                          Instant updated, List<String> versions) implements RemoteAddon.IMod {

        @Override
        public List<RemoteAddon> loadDependencies(RemoteAddonRepository modRepository, DownloadProvider downloadProvider) throws IOException {
            Set<RemoteAddon.Dependency> dependencies = modRepository.getRemoteVersionsById(downloadProvider, id())
                    .flatMap(version -> version.dependencies().stream())
                    .collect(Collectors.toSet());
            List<RemoteAddon> mods = new ArrayList<>();
            for (RemoteAddon.Dependency dependency : dependencies) {
                mods.add(dependency.load(downloadProvider));
            }
            return mods;
        }

        @Override
        public Stream<RemoteAddon.Version> loadVersions(RemoteAddonRepository modRepository, DownloadProvider downloadProvider) throws IOException {
            return modRepository.getRemoteVersionsById(downloadProvider, id());
        }

        public RemoteAddon toMod() {
            Type type = switch (projectType) {
                case "modpack" -> Type.MODPACK;
                case "resourcepack" -> Type.RESOURCE_PACK;
                case "shader" -> Type.SHADER_PACK;
                default -> Type.MOD;
            };
            return new RemoteAddon(
                    slug,
                    "",
                    title,
                    description,
                    categories,
                    String.format("https://modrinth.com/%s/%s", projectType, id),
                    iconUrl,
                    this,
                    type
            );
        }
    }

    @Immutable
    public record Dependency(@SerializedName("version_id") String versionId,
                             @SerializedName("project_id") String projectId,
                             @SerializedName("dependency_type") String dependencyType) {
    }

    public record ProjectVersion(String name, @SerializedName("version_number") String versionNumber, String changelog,
                                 List<Dependency> dependencies,
                                 @SerializedName("game_versions") List<String> gameVersions,
                                 @SerializedName("version_type") String versionType, List<String> loaders,
                                 boolean featured, String id, @SerializedName("project_id") String projectId,
                                 @SerializedName("author_id") String authorId,
                                 @SerializedName("date_published") Instant datePublished, int downloads,
                                 @SerializedName("changelog_url") String changelogUrl,
                                 List<ProjectVersionFile> files) implements RemoteAddon.IVersion {
        private static final Map<String, RemoteAddon.@Nullable DependencyType> DEPENDENCY_TYPE = mapOf(
                pair("required", RemoteAddon.DependencyType.REQUIRED),
                pair("optional", RemoteAddon.DependencyType.OPTIONAL),
                pair("embedded", RemoteAddon.DependencyType.EMBEDDED),
                pair("incompatible", RemoteAddon.DependencyType.INCOMPATIBLE)
        );

        @Override
        public RemoteAddon.Source getType() {
            return RemoteAddon.Source.MODRINTH;
        }

        public Optional<RemoteAddon.Version> toVersion() {
            RemoteAddon.VersionType type;
            if ("release".equals(versionType)) {
                type = RemoteAddon.VersionType.Release;
            } else if ("beta".equals(versionType)) {
                type = RemoteAddon.VersionType.Beta;
            } else if ("alpha".equals(versionType)) {
                type = RemoteAddon.VersionType.Alpha;
            } else {
                type = RemoteAddon.VersionType.Release;
            }

            if (files.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new RemoteAddon.Version(
                    this,
                    projectId,
                    name,
                    versionNumber,
                    changelog,
                    datePublished,
                    type,
                    files.get(0).toFile(),
                    dependencies.stream().map(dependency -> {
                        if (dependency.projectId == null) {
                            return RemoteAddon.Dependency.ofBroken();
                        }

                        if (!DEPENDENCY_TYPE.containsKey(dependency.dependencyType)) {
                            throw new IllegalStateException("Broken datas");
                        }

                        return RemoteAddon.Dependency.ofGeneral(DEPENDENCY_TYPE.get(dependency.dependencyType), MODS, dependency.projectId);
                    }).filter(Objects::nonNull).collect(Collectors.toList()),
                    gameVersions,
                    loaders.stream().flatMap(loader -> {
                        if ("fabric".equalsIgnoreCase(loader)) return Stream.of(ModLoaderType.FABRIC);
                        else if ("forge".equalsIgnoreCase(loader)) return Stream.of(ModLoaderType.FORGE);
                        else if ("neoforge".equalsIgnoreCase(loader)) return Stream.of(ModLoaderType.NEO_FORGE);
                        else if ("quilt".equalsIgnoreCase(loader)) return Stream.of(ModLoaderType.QUILT);
                        else if ("liteloader".equalsIgnoreCase(loader)) return Stream.of(ModLoaderType.LITE_LOADER);
                        else return Stream.empty();
                    }).collect(Collectors.toList())
            ));
        }
    }

    public record ProjectVersionFile(Map<String, String> hashes, String url, String filename, boolean primary,
                                     int size) {

        public RemoteAddon.File toFile() {
            return new RemoteAddon.File(hashes, url, filename);
        }
    }

    public record ProjectSearchResult(String slug, String title, String description, List<String> categories,
                                      @SerializedName("display_categories") List<String> displayCategories,
                                      @SerializedName("project_type") String projectType, int downloads,
                                      @SerializedName("icon_url") String iconUrl,
                                      @SerializedName("project_id") String projectId, String author,
                                      List<String> versions, @SerializedName("date_created") Instant dateCreated,
                                      @SerializedName("date_modified") Instant dateModified,
                                      @SerializedName("latest_version") String latestVersion) implements RemoteAddon.IMod {

        @Override
        public List<RemoteAddon> loadDependencies(RemoteAddonRepository modRepository, DownloadProvider downloadProvider) throws IOException {
            Set<RemoteAddon.Dependency> dependencies = modRepository.getRemoteVersionsById(downloadProvider, projectId())
                    .flatMap(version -> version.dependencies().stream())
                    .collect(Collectors.toSet());
            List<RemoteAddon> mods = new ArrayList<>();
            for (RemoteAddon.Dependency dependency : dependencies) {
                mods.add(dependency.load(downloadProvider));
            }
            return mods;
        }

        @Override
        public Stream<RemoteAddon.Version> loadVersions(RemoteAddonRepository modRepository, DownloadProvider downloadProvider) throws IOException {
            return modRepository.getRemoteVersionsById(downloadProvider, projectId());
        }

        public RemoteAddon toMod() {
            Type type = switch (projectType) {
                case "modpack" -> Type.MODPACK;
                case "resourcepack" -> Type.RESOURCE_PACK;
                case "shader" -> Type.SHADER_PACK;
                default -> Type.MOD;
            };
            return new RemoteAddon(
                    slug,
                    author,
                    title,
                    description,
                    sortDisplayCategories(displayCategories),
                    String.format("https://modrinth.com/%s/%s", projectType, projectId),
                    iconUrl,
                    this,
                    type,
                    // Modrinth lists loaders as ordinary categories, so the search response already
                    // carries what the card needs to show which loaders a project supports.
                    detectLoaders(categories)
            );
        }
    }

    /// Extracts the supported loaders from a Modrinth project's category list.
    ///
    /// @param categories the category tags
    /// @return the loaders, possibly empty
    private static List<ModLoaderType> detectLoaders(@Nullable List<String> categories) {
        if (categories == null || categories.isEmpty())
            return List.of();

        List<ModLoaderType> loaders = new ArrayList<>();
        for (String category : categories) {
            if (category == null)
                continue;
            ModLoaderType loader = switch (category.toLowerCase(Locale.ROOT)) {
                case "fabric" -> ModLoaderType.FABRIC;
                case "forge" -> ModLoaderType.FORGE;
                case "neoforge" -> ModLoaderType.NEO_FORGE;
                case "quilt" -> ModLoaderType.QUILT;
                case "liteloader" -> ModLoaderType.LITE_LOADER;
                case "legacy-fabric" -> ModLoaderType.LEGACY_FABRIC;
                default -> null;
            };
            if (loader != null && !loaders.contains(loader))
                loaders.add(loader);
        }
        return List.copyOf(loaders);
    }

    public static class Response<T> {

        @SuppressWarnings("unchecked")
        public static <T> TypeToken<Response<T>> typeOf(Class<T> responseType) {
            return (TypeToken<Response<T>>) TypeToken.getParameterized(Response.class, responseType);
        }

        private final int offset;

        private final int limit;

        @SerializedName("total_hits")
        private final int totalHits;

        private final List<T> hits;

        public Response() {
            this(0, 0, Collections.emptyList());
        }

        public Response(int offset, int limit, List<T> hits) {
            this.offset = offset;
            this.limit = limit;
            this.totalHits = hits.size();
            this.hits = hits;
        }

        public int getOffset() {
            return offset;
        }

        public int getLimit() {
            return limit;
        }

        public int getTotalHits() {
            return totalHits;
        }

        public List<T> getHits() {
            return hits;
        }
    }
}
