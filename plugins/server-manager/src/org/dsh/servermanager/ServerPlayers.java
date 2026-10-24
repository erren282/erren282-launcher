package org.dsh.servermanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Reads and writes a server's player lists: operators, whitelist and bans.
///
/// <p>Every list is stored by the server itself as a JSON file in the server directory
/// (`ops.json`, `whitelist.json`, `banned-players.json`). Two ways of changing them exist and
/// both are used here, because neither is sufficient alone:</p>
///
/// <ul>
///   <li><b>Console commands</b> (`op`, `deop`, `whitelist add`, `ban`, `pardon`) are the only
///       way to change a *running* server: it holds the lists in memory and would overwrite the
///       files on its next save, so editing the files behind its back would silently be lost.
///       Commands also make the server resolve the player's real UUID.</li>
///   <li><b>Direct file writes</b> are what works while the server is **stopped**, and are the
///       only way to express a *temporary* ban with an expiry, which vanilla commands cannot
///       set at all.</li>
/// </ul>
///
/// <p>The UI decides which path to take based on whether the server is running.</p>
@NotNullByDefault
public final class ServerPlayers {

    /// One operator entry from `ops.json`.
    public record Op(String name, String uuid, int level, boolean bypassesPlayerLimit) {
    }

    /// One whitelist entry from `whitelist.json`.
    public record WhitelistEntry(String name, String uuid) {
    }

    /// One ban entry from `banned-players.json`.
    ///
    /// @param expires `forever` for a permanent ban, otherwise the server's own timestamp
    /// @param permanent whether [expires] means "never"
    public record Ban(String name, String uuid, String created, String source,
                      String expires, String reason, boolean permanent) {

        /// A human-readable description of how long the ban still lasts.
        public String expiryText() {
            if (permanent)
                return "永久";
            return expires == null || expires.isBlank() ? "永久" : expires;
        }
    }

    /// Everything the management page needs, read in one pass.
    public static final class Snapshot {
        private final @Unmodifiable List<Op> ops;
        private final @Unmodifiable List<WhitelistEntry> whitelist;
        private final @Unmodifiable List<Ban> banned;
        private final boolean whitelistEnabled;
        private final @Unmodifiable List<String> knownPlayers;

        Snapshot(List<Op> ops, List<WhitelistEntry> whitelist, List<Ban> banned,
                 boolean whitelistEnabled, List<String> knownPlayers) {
            this.ops = List.copyOf(ops);
            this.whitelist = List.copyOf(whitelist);
            this.banned = List.copyOf(banned);
            this.whitelistEnabled = whitelistEnabled;
            this.knownPlayers = List.copyOf(knownPlayers);
        }

        public @Unmodifiable List<Op> getOps() { return ops; }

        public @Unmodifiable List<WhitelistEntry> getWhitelist() { return whitelist; }

        public @Unmodifiable List<Ban> getBanned() { return banned; }

        /// Whether `white-list=true` in `server.properties`.
        public boolean isWhitelistEnabled() { return whitelistEnabled; }

        /// Every name the server has seen, from `usercache.json` plus the lists above.
        public @Unmodifiable List<String> getKnownPlayers() { return knownPlayers; }
    }

    /// Bans in this state never expire.
    private static final String FOREVER = "forever";
    /// Vanilla format for the timestamps it writes.
    private static final String TIME_FORMAT = "yyyy-MM-dd HH:mm:ss Z";
    /// Captures the player name out of a `join`/`left` console line.
    private static final Pattern JOINED = Pattern.compile(
            "\\b([A-Za-z0-9_]{1,16})(?:\\[/[^\\]]*\\])?\\s+"
                    + "(?:joined the game|logged in with entity id)");
    private static final Pattern LEFT = Pattern.compile(
            "\\b([A-Za-z0-9_]{1,16})(?:\\[/[^\\]]*\\])?\\s+"
                    + "(?:left the game|lost connection)");

    private ServerPlayers() {
    }

    // ------------------------------------------------------------- read ---

    /// Reads the whole player state of `profile`'s server directory.
    public static Snapshot read(ServerProfile profile) {
        Path directory = directoryOf(profile);
        if (directory == null)
            return new Snapshot(List.of(), List.of(), List.of(), false, List.of());

        List<Op> ops = new ArrayList<>();
        for (JsonObject entry : readArray(directory.resolve("ops.json"))) {
            String name = string(entry, "name");
            if (name.isBlank())
                continue;
            ops.add(new Op(name, string(entry, "uuid"),
                    entry.has("level") && entry.get("level").isJsonPrimitive()
                            ? entry.get("level").getAsInt() : 4,
                    entry.has("bypassesPlayerLimit")
                            && entry.get("bypassesPlayerLimit").getAsBoolean()));
        }

        List<WhitelistEntry> whitelist = new ArrayList<>();
        for (JsonObject entry : readArray(directory.resolve("whitelist.json"))) {
            String name = string(entry, "name");
            if (!name.isBlank())
                whitelist.add(new WhitelistEntry(name, string(entry, "uuid")));
        }

        List<Ban> banned = new ArrayList<>();
        for (JsonObject entry : readArray(directory.resolve("banned-players.json"))) {
            String name = string(entry, "name");
            if (name.isBlank())
                continue;
            String expires = string(entry, "expires");
            banned.add(new Ban(name, string(entry, "uuid"), string(entry, "created"),
                    string(entry, "source"), expires, string(entry, "reason"),
                    expires.isBlank() || FOREVER.equalsIgnoreCase(expires)));
        }

        Set<String> known = new LinkedHashSet<>();
        for (JsonObject entry : readArray(directory.resolve("usercache.json"))) {
            String name = string(entry, "name");
            if (!name.isBlank())
                known.add(name);
        }
        for (Op op : ops)
            known.add(op.name());
        for (WhitelistEntry entry : whitelist)
            known.add(entry.name());
        for (Ban ban : banned)
            known.add(ban.name());

        return new Snapshot(ops, whitelist, banned, isWhitelistEnabled(directory), sorted(known));
    }

    /// Reads `white-list` from `server.properties`.
    public static boolean isWhitelistEnabled(Path serverDirectory) {
        Path file = serverDirectory.resolve("server.properties");
        if (!Files.isRegularFile(file))
            return false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("#") && trimmed.startsWith("white-list="))
                    return "true".equalsIgnoreCase(trimmed.substring("white-list=".length()).trim());
            }
        } catch (IOException ignored) {
            // unreadable properties: treat the whitelist as off
        }
        return false;
    }

    /// Players currently online, derived from the console lines of a running server.
    ///
    /// <p>A dedicated server offers no query for this over stdin, so the join/leave lines in the
    /// captured console are the only local source. Names that appear in `list` output are not
    /// parsed here to keep this dependency-free and predictable.</p>
    public static @Unmodifiable List<String> onlinePlayers(@Nullable ServerProcess process) {
        if (process == null || !process.isRunning())
            return List.of();
        Set<String> online = new LinkedHashSet<>();
        // The last `list` reply wins outright: it is the server's own answer, so it beats
        // anything inferred from join/leave lines (which miss lines the launcher never saw,
        // for example those from before it took the console over).
        List<String> fromList = null;
        for (String line : process.getHistory()) {
            List<String> parsed = parseListReply(line);
            if (parsed != null) {
                fromList = parsed;
                continue;
            }
            Matcher joined = JOINED.matcher(line);
            if (joined.find()) {
                online.add(joined.group(1));
                continue;
            }
            Matcher left = LEFT.matcher(line);
            if (left.find())
                online.remove(left.group(1));
        }
        if (fromList != null) {
            // The reply can repeat a name; sorted() takes a set, which also deduplicates.
            online.clear();
            online.addAll(fromList);
        }
        return sorted(online);
    }

    /// Parses the reply to the `list` command.
    ///
    /// <p>Two shapes exist: {@code There are 2 of a max of 20 players online: a, b} and the
    /// older {@code There are 2/20 players online: a, b}. An empty list omits the colon
    /// entirely, which is why the name part is optional.</p>
    ///
    /// @param line one console line
    /// @return the names, or null when this is not a `list` reply
    private static @Nullable List<String> parseListReply(String line) {
        if (line == null)
            return null;
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("players online"))
            return null;
        int colon = line.indexOf(':');
        if (colon < 0)
            return List.of();
        List<String> names = new ArrayList<>();
        for (String part : line.substring(colon + 1).split(",")) {
            String name = part.trim();
            // The trailing line may carry log decoration; only accept a clean player name.
            if (isValidName(name))
                names.add(name);
        }
        return names;
    }

    /// True when a console line is something the online list would react to.
    ///
    /// <p>Used to decide whether a page needs rebuilding: a server can be chatty, and
    /// redrawing the player list for every unrelated line would make the page flicker and
    /// steal focus from whatever the user is typing.</p>
    ///
    /// <p>The {@code list} command's own output is included because it is the most reliable
    /// way to learn who is online, and a page that ignored it would stay stale until the
    /// next join or leave.</p>
    ///
    /// @param line one console line, usually with the server's timestamp prefix
    public static boolean isPlayerPresenceLine(@Nullable String line) {
        if (line == null || line.isEmpty())
            return false;
        if (JOINED.matcher(line).find() || LEFT.matcher(line).find())
            return true;
        // The `list` command answers with "There are N of a max of M players online: a, b".
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("players online:") || lower.contains("of a max of");
    }

    // ------------------------------------------------------------ write ---

    /// Adds `name` to the operators list on disk, granting the given permission level.
    ///
    /// <p>Only for a stopped server; use the `op` command while it runs.</p>
    public static void writeOp(ServerProfile profile, String name, int level) throws IOException {
        Path directory = requireDirectory(profile);
        List<JsonObject> entries = new ArrayList<>(readArray(directory.resolve("ops.json")));
        entries.removeIf(entry -> name.equalsIgnoreCase(string(entry, "name")));

        JsonObject op = new JsonObject();
        op.addProperty("uuid", resolveUuid(directory, name, profile.isOnlineMode()));
        op.addProperty("name", name);
        op.addProperty("level", level);
        op.addProperty("bypassesPlayerLimit", false);
        entries.add(op);
        writeArray(directory.resolve("ops.json"), entries);
    }

    /// Removes `name` from the operators list on disk. For a stopped server.
    public static void removeOp(ServerProfile profile, String name) throws IOException {
        Path directory = requireDirectory(profile);
        List<JsonObject> entries = new ArrayList<>(readArray(directory.resolve("ops.json")));
        if (entries.removeIf(entry -> name.equalsIgnoreCase(string(entry, "name"))))
            writeArray(directory.resolve("ops.json"), entries);
    }

    /// Adds `name` to the whitelist on disk. For a stopped server.
    public static void addToWhitelist(ServerProfile profile, String name) throws IOException {
        Path directory = requireDirectory(profile);
        List<JsonObject> entries = new ArrayList<>(readArray(directory.resolve("whitelist.json")));
        entries.removeIf(entry -> name.equalsIgnoreCase(string(entry, "name")));

        JsonObject entry = new JsonObject();
        entry.addProperty("uuid", resolveUuid(directory, name, profile.isOnlineMode()));
        entry.addProperty("name", name);
        entries.add(entry);
        writeArray(directory.resolve("whitelist.json"), entries);
    }

    /// Removes `name` from the whitelist on disk. For a stopped server.
    public static void removeFromWhitelist(ServerProfile profile, String name) throws IOException {
        Path directory = requireDirectory(profile);
        List<JsonObject> entries = new ArrayList<>(readArray(directory.resolve("whitelist.json")));
        if (entries.removeIf(entry -> name.equalsIgnoreCase(string(entry, "name"))))
            writeArray(directory.resolve("whitelist.json"), entries);
    }

    /// Bans `name` on disk, optionally until `expires` (null or blank means permanent).
    /// For a stopped server, or for a temporary ban the vanilla commands cannot express.
    public static void writeBan(ServerProfile profile, String name, String reason,
                                @Nullable String expires) throws IOException {
        Path directory = requireDirectory(profile);
        List<JsonObject> entries = new ArrayList<>(readArray(directory.resolve("banned-players.json")));
        entries.removeIf(entry -> name.equalsIgnoreCase(string(entry, "name")));

        JsonObject ban = new JsonObject();
        ban.addProperty("uuid", resolveUuid(directory, name, profile.isOnlineMode()));
        ban.addProperty("name", name);
        ban.addProperty("created", now());
        ban.addProperty("source", "Server");
        ban.addProperty("expires", expires == null || expires.isBlank() ? FOREVER : expires);
        ban.addProperty("reason", reason == null || reason.isBlank()
                ? "Banned by an operator." : reason);
        entries.add(ban);
        writeArray(directory.resolve("banned-players.json"), entries);
    }

    /// Lifts the ban on disk. For a stopped server.
    public static void removeBan(ServerProfile profile, String name) throws IOException {
        Path directory = requireDirectory(profile);
        List<JsonObject> entries = new ArrayList<>(readArray(directory.resolve("banned-players.json")));
        if (entries.removeIf(entry -> name.equalsIgnoreCase(string(entry, "name"))))
            writeArray(directory.resolve("banned-players.json"), entries);
    }

    /// The exact `expires` timestamp the server would store for a ban lasting `amount` `unit`.
    ///
    /// @param unit one of `minute`, `hour`, `day`, `week`, `month`, `year`, or `forever`
    /// @return the timestamp, or [FOREVER]
    public static String expiresAfter(long amount, String unit) {
        if (amount <= 0 || unit == null || unit.equalsIgnoreCase(FOREVER))
            return FOREVER;
        java.time.LocalDateTime until = java.time.LocalDateTime.now();
        switch (unit.toLowerCase(Locale.ROOT)) {
            case "minute", "minutes" -> until = until.plusMinutes(amount);
            case "hour", "hours" -> until = until.plusHours(amount);
            case "day", "days" -> until = until.plusDays(amount);
            case "week", "weeks" -> until = until.plusWeeks(amount);
            case "month", "months" -> until = until.plusMonths(amount);
            case "year", "years" -> until = until.plusYears(amount);
            default -> {
                return FOREVER;
            }
        }
        return until.atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern(TIME_FORMAT, Locale.ROOT));
    }

    // ---------------------------------------------------------- commands ---

    /// The console command granting operator rights.
    public static String opCommand(String name) {
        return "op " + name;
    }

    /// The console command revoking operator rights.
    public static String deopCommand(String name) {
        return "deop " + name;
    }

    /// The console command adding a whitelist entry.
    public static String whitelistAddCommand(String name) {
        return "whitelist add " + name;
    }

    /// The console command removing a whitelist entry.
    public static String whitelistRemoveCommand(String name) {
        return "whitelist remove " + name;
    }

    /// The console command turning the whitelist on or off.
    public static String whitelistToggleCommand(boolean enabled) {
        return enabled ? "whitelist on" : "whitelist off";
    }

    /// The vanilla command banning a player permanently.
    public static String banCommand(String name, String reason) {
        return reason == null || reason.isBlank() ? "ban " + name : "ban " + name + " " + reason;
    }

    /// The vanilla command lifting a ban.
    public static String pardonCommand(String name) {
        return "pardon " + name;
    }

    /// The EssentialsX command for a ban that expires; only available with that plugin.
    public static String tempBanCommand(String name, long amount, String unit, String reason) {
        String duration = amount + " " + unit;
        return reason == null || reason.isBlank()
                ? "tempban " + name + " " + duration
                : "tempban " + name + " " + duration + " " + reason;
    }

    /// The vanilla command removing a player from the server right now.
    public static String kickCommand(String name, String reason) {
        return reason == null || reason.isBlank() ? "kick " + name : "kick " + name + " " + reason;
    }

    // ----------------------------------------------------------- helpers ---

    /// Resolves a player's UUID: the one the server cached, else the offline-mode UUID.
    ///
    /// <p>With `online-mode=false` (the usual case for these servers) the UUID is
    /// `MD5("OfflinePlayer:<name>")` with the version/variant bits adjusted — the same value
    /// the server computes, so a written entry matches what the server expects.</p>
    public static String resolveUuid(Path serverDirectory, String name, boolean onlineMode) {
        for (JsonObject entry : readArray(serverDirectory.resolve("usercache.json")))
            if (name.equalsIgnoreCase(string(entry, "name"))) {
                String uuid = string(entry, "uuid");
                if (!uuid.isBlank())
                    return uuid;
            }
        return offlineUuid(name);
    }

    /// The offline-mode UUID for `name` (Java's `UUID.nameUUIDFromBytes` over `OfflinePlayer:`).
    public static String offlineUuid(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x30);   // version 3
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);   // IETF variant
            StringBuilder builder = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10)
                    builder.append('-');
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String now() {
        return java.time.ZonedDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern(TIME_FORMAT, Locale.ROOT));
    }

    private static @Nullable Path directoryOf(ServerProfile profile) {
        String directory = profile.getDirectory();
        if (directory == null || directory.isBlank())
            return null;
        try {
            return Path.of(directory);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path requireDirectory(ServerProfile profile) throws IOException {
        Path directory = directoryOf(profile);
        if (directory == null || !Files.isDirectory(directory))
            throw new IOException("服务端目录不存在：" + profile.getDirectory());
        return directory;
    }

    private static List<JsonObject> readArray(Path file) {
        if (!Files.isRegularFile(file))
            return List.of();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank())
                return List.of();
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonArray())
                return List.of();
            JsonArray array = parsed.getAsJsonArray();
            List<JsonObject> result = new ArrayList<>(array.size());
            for (JsonElement element : array)
                if (element.isJsonObject())
                    result.add(element.getAsJsonObject());
            return result;
        } catch (Exception ignored) {
            // a corrupt list is treated as empty rather than crashing the page
            return List.of();
        }
    }

    /// Writes a list back, preserving the pretty-printed style the server itself uses.
    private static void writeArray(Path file, List<JsonObject> entries) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries)
            array.add(entry);
        Path parent = file.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.writeString(file, gson.toJson(array) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull())
            return "";
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static List<String> sorted(Set<String> names) {
        List<String> list = new ArrayList<>(names);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(list);
    }

    /// Player names that look valid for Minecraft (1–16 chars, `[A-Za-z0-9_]`).
    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    /// Case-insensitive membership test over the whitelist.
    public static boolean isWhitelisted(Snapshot snapshot, String name) {
        return snapshot.getWhitelist().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name));
    }

    /// Case-insensitive membership test over the operator list.
    public static boolean isOp(Snapshot snapshot, String name) {
        return snapshot.getOps().stream().anyMatch(op -> op.name().equalsIgnoreCase(name));
    }

    /// Case-insensitive membership test over the ban list.
    public static boolean isBanned(Snapshot snapshot, String name) {
        return snapshot.getBanned().stream().anyMatch(ban -> ban.name().equalsIgnoreCase(name));
    }

    /// The distinct names across all lists, for completion and for the picker.
    public static @Unmodifiable List<String> allNames(Snapshot snapshot) {
        Map<String, String> byLower = new LinkedHashMap<>();
        snapshot.getKnownPlayers().forEach(name -> byLower.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        snapshot.getOps().forEach(op -> byLower.putIfAbsent(op.name().toLowerCase(Locale.ROOT), op.name()));
        snapshot.getWhitelist().forEach(entry ->
                byLower.putIfAbsent(entry.name().toLowerCase(Locale.ROOT), entry.name()));
        snapshot.getBanned().forEach(ban ->
                byLower.putIfAbsent(ban.name().toLowerCase(Locale.ROOT), ban.name()));
        List<String> names = new ArrayList<>(byLower.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }
}
