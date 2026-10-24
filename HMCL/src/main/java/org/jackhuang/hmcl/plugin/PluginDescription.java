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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// The manifest of an HMCL plugin, deserialized from `hmcl.plugin.json` at the root of the plugin jar.
///
/// @see PluginManager
@NotNullByDefault
public final class PluginDescription {

    /// The name of the manifest file expected at the root of a plugin jar.
    public static final String MANIFEST_FILE_NAME = "hmcl.plugin.json";

    /// The pattern a valid plugin identifier must match: dot-separated lowercase segments
    /// of letters, digits, underscores and hyphens, e.g. `org.example.hello-plugin`.
    public static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$");

    private String id = "";
    private String name = "";
    private @Nullable String version;
    private @Nullable String author;
    private @Nullable String description;
    private String entry = "";
    private @Nullable String minLauncherVersion;
    private @Nullable String access;

    /// Returns the unique identifier of the plugin, e.g. `my-plugin`.
    public String getId() {
        return id;
    }

    /// Returns the display name of the plugin.
    public String getName() {
        return name;
    }

    /// Returns the plugin version, or `null` if not declared.
    public @Nullable String getVersion() {
        return version;
    }

    /// Returns the plugin author, or `null` if not declared.
    public @Nullable String getAuthor() {
        return author;
    }

    /// Returns the plugin description, or `null` if not declared.
    public @Nullable String getDescription() {
        return description;
    }

    /// Returns the fully qualified name of the class extending [HmclPlugin].
    public String getEntry() {
        return entry;
    }

    /// Returns the minimum launcher version required by the plugin, or `null` if not declared.
    public @Nullable String getMinLauncherVersion() {
        return minLauncherVersion;
    }

    /// Returns the access level declared by the plugin.
    ///
    /// <p>An absent or unknown `access` field means [PluginAccess#ROOT]: by default a plugin
    /// receives the full launcher API, including [PluginContext#getRoot].</p>
    public PluginAccess getAccess() {
        try {
            return PluginAccess.fromId(access);
        } catch (IllegalArgumentException e) {
            return PluginAccess.ROOT;
        }
    }

    /// Parses and validates the plugin manifest contained in the given plugin jar.
    ///
    /// @param jarPath the path of the plugin jar
    /// @return the parsed description
    /// @throws IOException           if the jar cannot be read
    /// @throws PluginFormatException if the manifest is missing, malformed or fails validation
    public static PluginDescription readFromJar(Path jarPath) throws IOException, PluginFormatException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST_FILE_NAME);
            if (entry == null)
                throw new PluginFormatException("Missing " + MANIFEST_FILE_NAME + " in " + jarPath.getFileName());

            try (InputStream input = zip.getInputStream(entry)) {
                return parse(stripUtf8Bom(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
    }

    /// Manifests written on Windows (Notepad, PowerShell) often carry a UTF-8 BOM; Gson
    /// rejects a document that starts with one, which made perfectly valid plugins
    /// "unrecognizable". The BOM is not part of the JSON, so drop it.
    public static String stripUtf8Bom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /// Parses and validates a plugin manifest from its JSON content.
    ///
    /// @param json the JSON content of `hmcl.plugin.json`
    /// @return the parsed description
    /// @throws PluginFormatException if the JSON is malformed or fails validation
    public static PluginDescription parse(String json) throws PluginFormatException {
        PluginDescription description = org.jackhuang.hmcl.util.gson.JsonUtils.fromJson(json, PluginDescription.class);
        if (description == null)
            throw new PluginFormatException("Manifest is empty");

        if (!ID_PATTERN.matcher(description.id).matches())
            throw new PluginFormatException("Invalid id \"" + description.id
                    + "\": expected lowercase letters, digits, underscores, hyphens and dots, starting with a letter");
        if (description.name.isBlank())
            throw new PluginFormatException("Missing name");
        if (description.entry.isBlank())
            throw new PluginFormatException("Missing entry class");

        description.id = description.id.trim();
        description.name = description.name.trim();
        description.entry = description.entry.trim();
        return description;
    }

    @Override
    public String toString() {
        return id + (version == null ? "" : " " + version);
    }
}
