package org.dsh.servermanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// Loads and saves the list of managed server profiles (`servers.json`).
public final class ProfileStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final List<ServerProfile> profiles = new ArrayList<>();

    public ProfileStore(Path dataDirectory) {
        this.file = dataDirectory.resolve("servers.json");
        load();
    }

    private void load() {
        profiles.clear();
        if (!Files.exists(file))
            return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<ServerProfile> loaded = GSON.fromJson(json, new TypeToken<List<ServerProfile>>() {}.getType());
            if (loaded != null)
                profiles.addAll(loaded);
        } catch (Exception e) {
            System.err.println("[server-manager] Failed to read servers.json: " + e);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(profiles), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized List<ServerProfile> getProfiles() {
        return new ArrayList<>(profiles);
    }

    public synchronized ServerProfile get(String id) {
        for (ServerProfile profile : profiles)
            if (profile.getId().equals(id))
                return profile;
        return null;
    }

    public synchronized void add(ServerProfile profile) throws IOException {
        if (profile.getId() == null || profile.getId().isEmpty())
            profile.setId(UUID.randomUUID().toString());
        profiles.add(profile);
        save();
    }

    public synchronized void update(ServerProfile profile) throws IOException {
        save();
    }

    public synchronized void remove(ServerProfile profile) throws IOException {
        profiles.removeIf(p -> p.getId().equals(profile.getId()));
        save();
    }
}
