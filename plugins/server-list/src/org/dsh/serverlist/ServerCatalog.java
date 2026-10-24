package org.dsh.serverlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The browsable server catalog: a curated, bundled list plus the user's own additions.
///
/// <p>Live data (players, latency, version, icon) is fetched per server by [ServerPinger];
/// this class only knows the static identity of each server. Bundled entries live in the
/// plugin jar (`/servers.json`), user additions in `<data>/custom-servers.json` so plugin
/// updates never wipe them.</p>
public final class ServerCatalog {

    /// One browsable server.
    public record Entry(String name, String address, List<String> categories,
                        String description, String website, boolean custom) {
    }

    private ServerCatalog() {
    }

    /// Bundled entries from the plugin jar.
    public static List<Entry> bundled() {
        try (InputStream input = ServerCatalog.class.getResourceAsStream("/servers.json")) {
            if (input == null) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Bundled servers.json missing");
                return List.of();
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Failed to read bundled servers.json", e);
            return List.of();
        }
    }

    /// The user's own servers, persisted in the plugin data directory.
    public static List<Entry> custom(Path dataDirectory) {
        Path file = dataDirectory.resolve("custom-servers.json");
        if (!Files.isRegularFile(file))
            return new ArrayList<>();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Failed to read custom-servers.json", e);
            return new ArrayList<>();
        }
    }

    /// Persists the user's servers; called after every add/remove.
    public static void saveCustom(Path dataDirectory, List<Entry> custom) {
        Path file = dataDirectory.resolve("custom-servers.json");
        JsonArray array = new JsonArray();
        for (Entry entry : custom) {
            JsonObject object = new JsonObject();
            object.addProperty("name", entry.name());
            object.addProperty("address", entry.address());
            JsonArray categories = new JsonArray();
            entry.categories().forEach(categories::add);
            object.add("categories", categories);
            if (entry.description() != null)
                object.addProperty("description", entry.description());
            if (entry.website() != null)
                object.addProperty("website", entry.website());
            array.add(object);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, array.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Failed to save custom-servers.json", e);
        }
    }

    private static List<Entry> parse(String json, boolean custom) {
        List<Entry> entries = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (var element : array) {
                JsonObject object = element.getAsJsonObject();
                String address = object.has("address") ? object.get("address").getAsString() : "";
                if (address.isBlank())
                    continue;
                List<String> categories = new ArrayList<>();
                if (object.has("categories") && object.get("categories").isJsonArray())
                    object.getAsJsonArray("categories").forEach(c -> categories.add(c.getAsString()));
                entries.add(new Entry(
                        object.has("name") && !object.get("name").isJsonNull()
                                ? object.get("name").getAsString() : address,
                        address,
                        categories,
                        object.has("description") && !object.get("description").isJsonNull()
                                ? object.get("description").getAsString() : "",
                        object.has("website") && !object.get("website").isJsonNull()
                                ? object.get("website").getAsString() : null,
                        custom));
            }
        } catch (Exception e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Failed to parse server catalog", e);
        }
        return entries;
    }
}
