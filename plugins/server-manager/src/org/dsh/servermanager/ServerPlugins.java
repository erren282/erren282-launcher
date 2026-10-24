package org.dsh.servermanager;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Inspects and manages the plugins of one server directory.
///
/// <p>A Bukkit-family plugin is a jar with a `plugin.yml` describing itself. Two facts shape the
/// design here:</p>
///
/// <ul>
///   <li>Only a handful of plugins declare `website`, so it is shown when present and the page
///       links to a search otherwise.</li>
///   <li>A server only loads `plugins/*.jar`, so **disabling** a plugin means moving the jar out
///       of the way — into `plugins/.disabled/` — and enabling means moving it back. That keeps
///       the jar intact and is trivially reversible, which deleting is not.</li>
/// </ul>
@NotNullByDefault
public final class ServerPlugins {

    /// Where disabled plugins are parked. A leading dot keeps Paper from treating it as a plugin.
    public static final String DISABLED_DIRECTORY = ".disabled";

    /// One installed plugin.
    public static final class Plugin {
        private final String fileName;
        private final String name;
        private final String version;
        private final String description;
        private final String authors;
        private final String website;
        private final String apiVersion;
        private final String mainClass;
        private final long sizeBytes;
        private final boolean enabled;
        private final @Unmodifiable List<String> depends;
        private final @Unmodifiable List<String> softDepends;

        Plugin(String fileName, String name, String version, String description, String authors,
               String website, String apiVersion, String mainClass, long sizeBytes, boolean enabled,
               List<String> depends, List<String> softDepends) {
            this.fileName = fileName;
            this.name = name;
            this.version = version;
            this.description = description;
            this.authors = authors;
            this.website = website;
            this.apiVersion = apiVersion;
            this.mainClass = mainClass;
            this.sizeBytes = sizeBytes;
            this.enabled = enabled;
            this.depends = List.copyOf(depends);
            this.softDepends = List.copyOf(softDepends);
        }

        /// The jar file name, which is the identity used for every operation.
        public String getFileName() { return fileName; }

        /// `name` from plugin.yml, or the file name when the jar declares nothing.
        public String getName() { return name; }

        public String getVersion() { return version; }

        public String getDescription() { return description; }

        /// Author list, already joined for display; may be empty.
        public String getAuthors() { return authors; }

        /// The `website` field, or empty when the plugin does not declare one.
        public String getWebsite() { return website; }

        public String getApiVersion() { return apiVersion; }

        public String getMainClass() { return mainClass; }

        public long getSizeBytes() { return sizeBytes; }

        /// False while the jar sits in the disabled folder.
        public boolean isEnabled() { return enabled; }

        public @Unmodifiable List<String> getDepends() { return depends; }

        public @Unmodifiable List<String> getSoftDepends() { return softDepends; }

        /// Human-readable name plus version, e.g. `EssentialsX 2.21.0`.
        public String getDisplayName() {
            return version.isBlank() ? name : name + " " + version;
        }

        /// A URL that can be opened for this plugin: its `website`, else a Modrinth search.
        public String getInfoUrl() {
            if (!website.isBlank()) {
                String url = website.trim();
                if (url.startsWith("http://") || url.startsWith("https://"))
                    return url;
                // plugin.yml often carries a bare host such as `www.citizensnpcs.co`
                return "https://" + url;
            }
            return "https://modrinth.com/plugins?q="
                    + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
        }

        /// True when [getInfoUrl] points at the plugin's own site rather than a search page.
        public boolean hasWebsite() {
            return !website.isBlank();
        }
    }

    private ServerPlugins() {
    }

    /// The server's `plugins` directory.
    public static Path pluginsDirectory(ServerProfile profile) {
        return Path.of(profile.getDirectory(), "plugins");
    }

    /// Where disabled jars are parked.
    public static Path disabledDirectory(ServerProfile profile) {
        return pluginsDirectory(profile).resolve(DISABLED_DIRECTORY);
    }

    /// Lists every plugin of the server, enabled ones first, each group sorted by name.
    public static @Unmodifiable List<Plugin> list(ServerProfile profile) {
        List<Plugin> result = new ArrayList<>();
        collect(pluginsDirectory(profile), true, result);
        collect(disabledDirectory(profile), false, result);
        result.sort(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(plugin -> !plugin.isEnabled()));
        return List.copyOf(result);
    }

    /// Reads the jars directly inside `directory`; nested folders are skipped on purpose, since
    /// those belong to plugins (data folders) rather than being plugins themselves.
    private static void collect(Path directory, boolean enabled, List<Plugin> result) {
        if (!Files.isDirectory(directory))
            return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path jar : children.toList()) {
                String fileName = jar.getFileName().toString();
                if (!Files.isRegularFile(jar) || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar"))
                    continue;
                result.add(read(jar, fileName, enabled));
            }
        } catch (IOException ignored) {
            // an unreadable plugins directory yields an empty list
        }
    }

    /// Builds a [Plugin] from a jar, falling back to the file name when `plugin.yml` is absent
    /// or unreadable (a broken or non-Bukkit jar still has to be listed so it can be removed).
    public static Plugin read(Path jar, String fileName, boolean enabled) {
        long size = 0;
        try {
            size = Files.size(jar);
        } catch (IOException ignored) {
        }
        String text = readPluginYml(jar);
        if (text == null)
            return new Plugin(fileName, stripExtension(fileName), "", "", "", "", "", "",
                    size, enabled, List.of(), List.of());

        return new Plugin(fileName,
                firstNonBlank(yamlString(text, "name"), stripExtension(fileName)),
                yamlString(text, "version"),
                yamlString(text, "description"),
                yamlAuthors(text),
                yamlString(text, "website"),
                yamlString(text, "api-version"),
                yamlString(text, "main"),
                size, enabled,
                yamlList(text, "depend"),
                yamlList(text, "softdepend"));
    }

    /// Reads `plugin.yml` out of the jar, trying UTF-8 then the legacy encodings these files
    /// are usually written in.
    static @Nullable String readPluginYml(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null)
                entry = zip.getEntry("plugin.yaml");
            if (entry == null)
                return null;
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readAllBytes();
                return decode(bytes);
            }
        } catch (IOException e) {
            // not a readable zip: treat as a plugin with no metadata
            return null;
        }
    }

    /// Decodes plugin.yml bytes, preferring UTF-8 and falling back to the legacy code pages.
    static String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        // a replacement character means the bytes were not valid UTF-8
        if (utf8.indexOf('\uFFFD') < 0)
            return utf8;
        for (String charset : new String[]{"GB18030", "windows-1252", "ISO-8859-1"}) {
            try {
                String decoded = new String(bytes, java.nio.charset.Charset.forName(charset));
                if (decoded.indexOf('\uFFFD') < 0)
                    return decoded;
            } catch (RuntimeException ignored) {
                // unsupported charset on this JVM: try the next one
            }
        }
        return utf8;
    }

    // ---------------------------------------------------------- metadata ---

    /// Matches one top-level key and captures everything after the colon on the same line.
    ///
    /// <p>`[ \t]*` rather than `\s*` is essential: `\s` also matches a newline, which would make
    /// the pattern skip past an empty value and capture the *next* line instead — turning a
    /// block-style list's first item into the scalar value.</p>
    private static Pattern topLevelKey(String key) {
        return Pattern.compile("(?m)^" + Pattern.quote(key) + "[ \\t]*:[ \\t]*(.*)$");
    }

    /// Extracts a top-level scalar from a plugin.yml.
    ///
    /// <p>This is a deliberately small YAML reader rather than a dependency: `plugin.yml` is
    /// flat at the top level, and pulling in a YAML library for a handful of string fields is
    /// not worth it. Only lines at zero indentation are considered, so nested keys such as
    /// `commands.<name>.description` can never be mistaken for the top-level ones.</p>
    static String yamlString(String yaml, String key) {
        Matcher matcher = topLevelKey(key).matcher(yaml);
        if (!matcher.find())
            return "";
        return unquote(stripComment(matcher.group(1)));
    }

    /// Joins `author` and `authors` into one display string.
    static String yamlAuthors(String yaml) {
        List<String> authors = new ArrayList<>(yamlList(yaml, "authors"));
        String single = yamlString(yaml, "author");
        if (!single.isBlank())
            authors.add(0, single);
        if (authors.isEmpty())
            return "";
        return String.join(", ", new java.util.LinkedHashSet<>(authors));
    }

    /// Reads a top-level list written either inline (`[A, B]`) or as `- A` lines.
    static List<String> yamlList(String yaml, String key) {
        Matcher matcher = topLevelKey(key).matcher(yaml);
        if (!matcher.find())
            return List.of();
        String inline = stripComment(matcher.group(1)).trim();
        if (inline.startsWith("[")) {
            int end = inline.lastIndexOf(']');
            String body = end > 0 ? inline.substring(1, end) : inline.substring(1);
            List<String> items = new ArrayList<>();
            for (String part : body.split(",")) {
                String item = unquote(part.trim());
                if (!item.isBlank())
                    items.add(item);
            }
            return items;
        }
        if (!inline.isEmpty())
            return List.of(unquote(inline));

        // block style: subsequent lines indented with `- item`, until a non-indented line
        List<String> items = new ArrayList<>();
        int start = matcher.end();
        for (String line : yaml.substring(start).split("\r?\n")) {
            if (line.isBlank())
                continue;
            if (!Character.isWhitespace(line.charAt(0)))
                break;
            String trimmed = line.trim();
            if (!trimmed.startsWith("-"))
                break;
            String item = unquote(stripComment(trimmed.substring(1).trim()));
            if (!item.isBlank())
                items.add(item);
        }
        return items;
    }

    private static String stripComment(String value) {
        String trimmed = value.trim();
        // a `#` only starts a comment when preceded by whitespace (or at the start)
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '#' && (i == 0 || Character.isWhitespace(trimmed.charAt(i - 1))))
                return trimmed.substring(0, i).trim();
        }
        return trimmed;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.charAt(0) == '\'' && trimmed.endsWith("'"))
                || (trimmed.charAt(0) == '"' && trimmed.endsWith("\""))))
            return trimmed.substring(1, trimmed.length() - 1).trim();
        return trimmed;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    static String stripExtension(String fileName) {
        int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // --------------------------------------------------------- operations ---

    /// Disables a plugin by moving its jar into `plugins/.disabled/`.
    public static void disable(ServerProfile profile, String fileName) throws IOException {
        Path source = pluginsDirectory(profile).resolve(fileName);
        if (!Files.isRegularFile(source))
            throw new IOException("找不到插件文件：" + source);
        Path target = disabledDirectory(profile);
        Files.createDirectories(target);
        Files.move(source, target.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
    }

    /// Re-enables a plugin by moving its jar back into `plugins/`.
    public static void enable(ServerProfile profile, String fileName) throws IOException {
        Path source = disabledDirectory(profile).resolve(fileName);
        if (!Files.isRegularFile(source))
            throw new IOException("找不到已停用的插件：" + source);
        Path target = pluginsDirectory(profile);
        Files.createDirectories(target);
        Files.move(source, target.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
    }

    /// Deletes a plugin jar, wherever it currently lives.
    ///
    /// <p>The plugin's data folder (`plugins/<name>/`) is deliberately kept: it holds worlds of
    /// configuration a reinstall would need, and deleting it silently would be destructive.</p>
    public static void delete(ServerProfile profile, String fileName) throws IOException {
        Files.deleteIfExists(pluginsDirectory(profile).resolve(fileName));
        Files.deleteIfExists(disabledDirectory(profile).resolve(fileName));
    }

    /// Moves a downloaded jar into the server's `plugins` directory, avoiding name clashes.
    ///
    /// @return the file name it was stored under
    public static String install(ServerProfile profile, Path downloadedJar, String preferredName)
            throws IOException {
        Path target = pluginsDirectory(profile);
        Files.createDirectories(target);
        String fileName = sanitizeFileName(
                preferredName == null || preferredName.isBlank()
                        ? downloadedJar.getFileName().toString() : preferredName);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar"))
            fileName = fileName + ".jar";

        Path destination = target.resolve(fileName);
        // never overwrite an existing plugin silently: pick a free name instead
        int counter = 1;
        while (Files.exists(destination)) {
            String base = stripExtension(fileName);
            destination = target.resolve(base + " (" + counter + ").jar");
            counter++;
        }
        Files.copy(downloadedJar, destination, StandardCopyOption.COPY_ATTRIBUTES);
        return destination.getFileName().toString();
    }

    /// Strips characters that are illegal in a file name on Windows.
    static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "plugin.jar" : cleaned;
    }
}
