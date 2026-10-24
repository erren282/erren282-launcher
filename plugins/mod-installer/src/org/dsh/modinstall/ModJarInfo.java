package org.dsh.modinstall;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a mod jar's metadata without installing it.
 *
 * <p>The dropped file is not in any instance yet, so the launcher's own mod
 * scanner (which binds results to a {@code ModManager} for a specific instance)
 * cannot be used. This reads the same files directly:</p>
 *
 * <table>
 *   <caption>Metadata files per loader</caption>
 *   <tr><th>File</th><th>Loader</th></tr>
 *   <tr><td>{@code fabric.mod.json}</td><td>Fabric</td></tr>
 *   <tr><td>{@code quilt.mod.json}</td><td>Quilt</td></tr>
 *   <tr><td>{@code META-INF/neoforge.mods.toml}</td><td>NeoForge</td></tr>
 *   <tr><td>{@code META-INF/mods.toml}</td><td>Forge</td></tr>
 *   <tr><td>{@code mcmod.info}</td><td>Forge (1.7–1.12)</td></tr>
 *   <tr><td>{@code litemod.json}</td><td>LiteLoader</td></tr>
 * </table>
 *
 * <p>What comes back is everything the install dialog needs: the name and icon,
 * which game versions the mod accepts, and which other mods it requires.</p>
 */
final class ModJarInfo {

    /** A declared dependency of the mod. */
    record Dependency(String id, String versionRange, boolean mandatory) {

        /** True for entries that name the game or the loader rather than a mod. */
        boolean isPlatform() {
            String lower = id.toLowerCase(Locale.ROOT);
            return lower.equals("minecraft") || lower.equals("forge") || lower.equals("neoforge")
                    || lower.equals("javafml") || lower.equals("fml") || lower.equals("java")
                    || lower.equals("fabricloader") || lower.equals("fabric-loader")
                    || lower.equals("quilt_loader") || lower.equals("quiltloader")
                    || lower.equals("liteloader") || lower.equals("mcp");
        }
    }

    /** Everything the dialog shows about one dropped file. */
    record Info(Path file, ModLoaderType loader, String id, String name, String version,
                String iconPath, List<String> mcVersionSpecs, List<Dependency> dependencies,
                String description, String authors) {

        /** True when the mod accepts the given game version. */
        boolean supportsMcVersion(String mcVersion) {
            return McVersionRange.matches(mcVersionSpecs, mcVersion);
        }

        /** The mods that must be present before this one runs. */
        List<Dependency> requiredMods() {
            List<Dependency> required = new ArrayList<>();
            for (Dependency dependency : dependencies) {
                if (dependency.mandatory() && !dependency.isPlatform()) {
                    required.add(dependency);
                }
            }
            return required;
        }

        /** How the loader is written in the dialog. */
        String loaderName() {
            return switch (loader) {
                case FABRIC -> "Fabric";
                case QUILT -> "Quilt";
                case NEO_FORGE -> "NeoForge";
                case FORGE -> "Forge";
                case LITE_LOADER -> "LiteLoader";
                case LEGACY_FABRIC -> "Legacy Fabric";
                case CLEANROOM -> "Cleanroom";
                default -> "未知加载器";
            };
        }
    }

    /** Metadata file names, in the order they are probed. */
    private static final String FABRIC = "fabric.mod.json";
    private static final String QUILT = "quilt.mod.json";
    private static final String NEOFORGE = "META-INF/neoforge.mods.toml";
    private static final String FORGE = "META-INF/mods.toml";
    private static final String LEGACY_FORGE = "mcmod.info";
    private static final String LITELOADER = "litemod.json";

    private ModJarInfo() {
    }

    /**
     * Reads whatever metadata the jar carries.
     *
     * @throws IOException when the file is not a readable zip
     */
    static Info read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (hasEntry(zip, FABRIC)) {
                return readFabric(jar, zip);
            }
            if (hasEntry(zip, QUILT)) {
                return readQuilt(jar, zip);
            }
            if (hasEntry(zip, NEOFORGE)) {
                return readForgeToml(jar, zip, NEOFORGE, ModLoaderType.NEO_FORGE);
            }
            if (hasEntry(zip, FORGE)) {
                return readForgeToml(jar, zip, FORGE, ModLoaderType.FORGE);
            }
            if (hasEntry(zip, LEGACY_FORGE)) {
                return readLegacyForge(jar, zip);
            }
            if (hasEntry(zip, LITELOADER)) {
                return readLiteLoader(jar, zip);
            }
            return unknown(jar);
        }
    }

    /** A jar with no recognisable metadata; the dialog still shows the file name. */
    private static Info unknown(Path jar) {
        String fileName = jar.getFileName().toString();
        String base = fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        return new Info(jar, ModLoaderType.UNKNOWN, base, base, "", null,
                List.of(), List.of(), "", "");
    }

    /** The icon bytes, or null when the jar has no icon. */
    static byte[] readIcon(Info info) {
        if (info == null) {
            return null;
        }
        try (ZipFile zip = new ZipFile(info.file().toFile())) {
            List<String> candidates = new ArrayList<>();
            if (info.iconPath() != null && !info.iconPath().isBlank()) {
                candidates.add(info.iconPath());
            }
            // Common conventions, for mods whose metadata omits the icon.
            String id = info.id() == null ? "" : info.id();
            candidates.add("assets/" + id + "/icon.png");
            candidates.add("assets/" + id + "/logo.png");
            candidates.add("icon.png");
            candidates.add("logo.png");
            candidates.add("pack.png");
            for (String candidate : candidates) {
                ZipEntry entry = zip.getEntry(candidate);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ Fabric

    private static Info readFabric(Path jar, ZipFile zip) throws IOException {
        JsonObject root = json(zip, FABRIC);
        String id = string(root, "id", baseName(jar));
        String name = string(root, "name", id);
        String version = string(root, "version", "");
        String description = string(root, "description", "");
        String authors = joinAuthors(root.get("authors"));
        String icon = fabricIcon(root.get("icon"));

        List<String> mcVersions = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        JsonObject depends = object(root, "depends");
        if (depends != null) {
            for (Map.Entry<String, JsonElement> entry : depends.entrySet()) {
                String depId = entry.getKey();
                List<String> specs = versionSpecs(entry.getValue());
                if ("minecraft".equalsIgnoreCase(depId)) {
                    mcVersions.addAll(specs);
                } else {
                    dependencies.add(new Dependency(depId, String.join(" ", specs), true));
                }
            }
        }
        // `breaks` and `conflicts` are deliberately ignored: they are not prerequisites.
        return new Info(jar, ModLoaderType.FABRIC, id, name, version, icon,
                mcVersions, dependencies, description, authors);
    }

    /** Fabric's {@code icon} is a path or a {"64": path, "128": path} object. */
    private static String fabricIcon(JsonElement icon) {
        if (icon == null || icon.isJsonNull()) {
            return null;
        }
        if (icon.isJsonPrimitive()) {
            return icon.getAsString();
        }
        if (icon.isJsonObject()) {
            String best = null;
            for (Map.Entry<String, JsonElement> entry : icon.getAsJsonObject().entrySet()) {
                // Prefer the largest size that is a plain string.
                if (entry.getValue().isJsonPrimitive()) {
                    best = entry.getValue().getAsString();
                }
            }
            return best;
        }
        return null;
    }

    private static String joinAuthors(JsonElement authors) {
        if (authors == null || !authors.isJsonArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonElement element : authors.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                names.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonElement name = element.getAsJsonObject().get("name");
                if (name != null && name.isJsonPrimitive()) {
                    names.add(name.getAsString());
                }
            }
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------ Quilt

    private static Info readQuilt(Path jar, ZipFile zip) throws IOException {
        JsonObject root = json(zip, QUILT);
        JsonObject loader = object(root, "quilt_loader");
        if (loader == null) {
            return unknown(jar);
        }
        String id = string(loader, "id", baseName(jar));
        String version = string(loader, "version", "");
        JsonObject metadata = object(loader, "metadata");
        String name = metadata == null ? id : string(metadata, "name", id);
        String description = metadata == null ? "" : string(metadata, "description", "");
        String icon = metadata == null ? null : quiltIcon(metadata.get("icon"));

        List<String> mcVersions = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        JsonElement depends = loader.get("depends");
        if (depends != null && depends.isJsonArray()) {
            for (JsonElement element : depends.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject dependency = element.getAsJsonObject();
                    String depId = string(dependency, "id", "");
                    List<String> specs = versionSpecs(dependency.get("versions"));
                    if ("minecraft".equalsIgnoreCase(depId)) {
                        mcVersions.addAll(specs);
                    } else if (!depId.isBlank()) {
                        dependencies.add(new Dependency(depId, String.join(" ", specs), true));
                    }
                } else if (element.isJsonPrimitive()) {
                    // Shorthand "modid" or "modid>=1.0".
                    String text = element.getAsString();
                    if (text.toLowerCase(Locale.ROOT).startsWith("minecraft")) {
                        mcVersions.add(text.replaceFirst("(?i)minecraft", "").trim());
                    } else {
                        dependencies.add(new Dependency(text, "", true));
                    }
                }
            }
        }
        return new Info(jar, ModLoaderType.QUILT, id, name, version, icon,
                mcVersions, dependencies, description, "");
    }

    private static String quiltIcon(JsonElement icon) {
        if (icon == null) {
            return null;
        }
        if (icon.isJsonPrimitive()) {
            return icon.getAsString();
        }
        if (icon.isJsonObject()) {
            JsonElement value = icon.getAsJsonObject().get("file");
            if (value != null && value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        return null;
    }

    // ------------------------------------------------------- Forge / NeoForge

    private static Info readForgeToml(Path jar, ZipFile zip, String entry, ModLoaderType loader)
            throws IOException {
        Map<String, Object> toml = Toml.parse(text(zip, entry));

        List<Map<String, Object>> mods = tables(toml.get("mods"));
        Map<String, Object> primary = mods.isEmpty() ? Map.of() : mods.get(0);
        String id = string(primary, "modId", baseName(jar));
        String name = string(primary, "displayName", id);
        String version = string(primary, "version", "");
        String description = string(primary, "description", "");
        String authors = string(primary, "authors", "");
        String icon = blankToNull(string(primary, "logoFile", ""));

        List<String> mcVersions = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        // dependencies is a table keyed by the *declaring* mod; every entry names
        // the mod it depends on in its own modId field.
        Object dependencyTable = toml.get("dependencies");
        if (dependencyTable instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                for (Map<String, Object> dependency : tables(value)) {
                    String depId = string(dependency, "modId", "");
                    if (depId.isBlank()) {
                        continue;
                    }
                    String range = string(dependency, "versionRange", "");
                    boolean mandatory = bool(dependency, "mandatory", true);
                    if ("minecraft".equalsIgnoreCase(depId)) {
                        mcVersions.add(range);
                    } else {
                        dependencies.add(new Dependency(depId, range, mandatory));
                    }
                }
            }
        }
        return new Info(jar, loader, id, name, version, icon,
                mcVersions, dependencies, description, authors);
    }

    // ---------------------------------------------------------- legacy Forge

    private static Info readLegacyForge(Path jar, ZipFile zip) throws IOException {
        JsonElement parsed = JsonParser.parseString(text(zip, LEGACY_FORGE));
        JsonArray mods = parsed.isJsonArray() ? parsed.getAsJsonArray()
                : parsed.isJsonObject() && parsed.getAsJsonObject().has("modList")
                ? parsed.getAsJsonObject().getAsJsonArray("modList")
                : new JsonArray();
        if (mods.isEmpty()) {
            return unknown(jar);
        }
        JsonObject mod = mods.get(0).getAsJsonObject();
        String id = string(mod, "modid", baseName(jar));
        String name = string(mod, "name", id);
        String version = string(mod, "version", "");
        String description = string(mod, "description", "");
        String authors = string(mod, "authorList", string(mod, "author", ""));
        String icon = blankToNull(string(mod, "logoFile", ""));
        // Legacy Forge pins one game version, e.g. "1.12.2".
        String mcVersion = string(mod, "mcversion", "");
        List<String> mcVersions = mcVersion.isBlank() ? List.of() : List.of(mcVersion);

        List<Dependency> dependencies = new ArrayList<>();
        JsonElement required = mod.get("requiredMods");
        if (required != null && required.isJsonArray()) {
            for (JsonElement element : required.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    String text = element.getAsString();
                    // Legacy Forge writes "modid@versionRange"; some mods use ":".
                    int separator = text.indexOf('@');
                    if (separator < 0) {
                        separator = text.indexOf(':');
                    }
                    String depId = separator < 0 ? text : text.substring(0, separator);
                    String range = separator < 0 ? "" : text.substring(separator + 1);
                    if (!depId.isBlank()) {
                        dependencies.add(new Dependency(depId, range, true));
                    }
                }
            }
        }
        return new Info(jar, ModLoaderType.FORGE, id, name, version, icon,
                mcVersions, dependencies, description, authors);
    }

    // ------------------------------------------------------------ LiteLoader

    private static Info readLiteLoader(Path jar, ZipFile zip) throws IOException {
        JsonObject root = json(zip, LITELOADER);
        String id = string(root, "name", baseName(jar));
        String name = id;
        String version = string(root, "version", "");
        String icon = blankToNull(string(root, "logoFile", ""));
        String mcVersion = string(root, "mcversion", "");
        return new Info(jar, ModLoaderType.LITE_LOADER, id, name, version, icon,
                mcVersion.isBlank() ? List.of() : List.of(mcVersion),
                List.of(), "", string(root, "author", ""));
    }

    // ------------------------------------------------------------------ utils

    /** Turns a JSON value into a list of version specifiers. */
    private static List<String> versionSpecs(JsonElement element) {
        List<String> specs = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return specs;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    specs.add(item.getAsString());
                }
            }
        } else if (element.isJsonPrimitive()) {
            specs.add(element.getAsString());
        }
        return specs;
    }

    /** Coerces a TOML value into a list of tables. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
        } else if (value instanceof Map) {
            result.add((Map<String, Object>) value);
        }
        return result;
    }

    private static String string(Map<String, Object> table, String key, String fallback) {
        Object value = table.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static boolean bool(Map<String, Object> table, String key, boolean fallback) {
        Object value = table.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element != null && element.isJsonPrimitive()) {
            String text = element.getAsString();
            return text.isBlank() ? fallback : text;
        }
        return fallback;
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonObject json(ZipFile zip, String entry) throws IOException {
        return JsonParser.parseString(text(zip, entry)).getAsJsonObject();
    }

    private static String text(ZipFile zip, String entry) throws IOException {
        ZipEntry zipEntry = zip.getEntry(entry);
        if (zipEntry == null) {
            throw new IOException("Missing " + entry);
        }
        try (InputStream in = zip.getInputStream(zipEntry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean hasEntry(ZipFile zip, String entry) {
        return zip.getEntry(entry) != null;
    }

    private static String baseName(Path jar) {
        String fileName = jar.getFileName().toString();
        return fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
