package org.dsh.servermanager;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Tab completion for the server console input box.
///
/// <p>The suggestions come from three sources, in order of usefulness:</p>
///
/// <ol>
///   <li><b>The server's own data</b> — player names are read from `usercache.json`, `ops.json`,
///       `whitelist.json` and `banned-players.json`, and world names from the directories that
///       contain a `level.dat`. Those are the values a command actually accepts, so they are far
///       more useful than a hard-coded list.</li>
///   <li><b>A built-in command table</b> — vanilla server commands plus the usual Bukkit/Spigot/
///       Paper ones, with argument suggestions for the commands whose arguments are a small,
///       fixed set (`gamemode`, `difficulty`, `weather`, `time`, `gamerule`, `whitelist`, …).</li>
///   <li><b>User commands</b> — an optional `commands.txt` in the plugin data directory lets the
///       user add plugin commands (`#` starts a comment, one name per line), because a plugin
///       cannot discover the commands other plugins registered.</li>
/// </ol>
///
/// <p>Completion is purely local: a dedicated server exposes no "ask for completions" channel
/// over stdin, so nothing is sent to the server to build a suggestion.</p>
@NotNullByDefault
public final class CommandCompleter {

    /// A completion result: the candidates plus the text range they replace.
    public static final class Completion {
        private final int tokenStart;
        private final int tokenEnd;
        private final @Unmodifiable List<String> candidates;

        Completion(int tokenStart, int tokenEnd, @Unmodifiable List<String> candidates) {
            this.tokenStart = tokenStart;
            this.tokenEnd = tokenEnd;
            this.candidates = candidates;
        }

        /// Index in the input where the completed token starts.
        public int getTokenStart() { return tokenStart; }

        /// Index just past the token (excluding any whitespace that follows).
        public int getTokenEnd() { return tokenEnd; }

        /// Matching candidates, alphabetically ordered and de-duplicated.
        public @Unmodifiable List<String> getCandidates() { return candidates; }
    }

    /// The file users can fill with extra command names; one per line, `#` comments allowed.
    private static final String CUSTOM_COMMANDS_FILE = "commands.txt";
    /// JSON keys that carry a player name in the server's own json files.
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    /// How many entries `commands.txt` may contribute, to keep a stray huge file harmless.
    private static final int CUSTOM_COMMAND_LIMIT = 500;

    private static final @Unmodifiable List<String> SELECTORS = List.of("@a", "@e", "@p", "@r", "@s");

    private static final @Unmodifiable List<String> GAMEMODES =
            List.of("adventure", "creative", "spectator", "survival");
    private static final @Unmodifiable List<String> DIFFICULTIES =
            List.of("easy", "hard", "normal", "peaceful");
    private static final @Unmodifiable List<String> WEATHERS = List.of("clear", "rain", "thunder");
    private static final @Unmodifiable List<String> TIMES = List.of("day", "midnight", "noon", "night");
    private static final @Unmodifiable List<String> BOOLEANS = List.of("false", "true");

    /// Commands whose first argument is a player (or a target selector).
    /// `teammsg`/`tm` are absent on purpose: their first argument is the message itself.
    private static final @Unmodifiable Set<String> PLAYER_FIRST = Set.of(
            "advancement", "attribute", "ban", "ban-ip", "clear", "damage", "deop", "effect",
            "enchant", "experience", "give", "kick", "kill", "op", "pardon", "pardon-ip",
            "playsound", "ride", "spawnpoint", "spectate", "stopsound", "tag", "tell", "tellraw",
            "title", "tp", "transfer", "w", "whisper", "xp");

    /// Commands whose first argument is free-form text, so there is nothing to suggest.
    /// Player-targeting commands such as `tell`/`msg` are deliberately not listed here: their
    /// first argument is a player, which is worth completing.
    private static final @Unmodifiable Set<String> TEXT_COMMANDS =
            Set.of("me", "say", "teammsg", "tm");

    /// Vanilla server commands (`/help` output), plus common Bukkit/Spigot/Paper additions.
    private static final @Unmodifiable List<String> BUILT_IN_COMMANDS = List.of(
            "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "bukkit", "clear",
            "clone", "damage", "data", "datapack", "debug", "defaultgamemode", "deop", "dialog",
            "difficulty", "effect", "enchant", "execute", "experience", "fill", "fillbiome",
            "forceload", "function", "gamemode", "gamerule", "give", "help", "item", "jfr", "kick",
            "kill", "list", "locate", "loot", "me", "msg", "mspt", "op", "paper", "pardon",
            "pardon-ip", "particle", "perf", "place", "playsound", "plugins", "profile", "publish",
            "random", "recipe", "reload", "return", "ride", "rotate", "save-all", "save-off",
            "save-on", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
            "setworldspawn", "spark", "spawnpoint", "spectate", "spigot", "spreadplayers", "stop",
            "summon", "swap", "tag", "team", "teammsg", "teleport", "tell", "tellraw", "tick",
            "timings", "time", "title", "tm", "tp", "tps", "transfer", "trigger", "version",
            "warden", "waypoint", "weather", "whitelist", "worldborder", "w", "whisper", "xp");

    /// Common gamerules, so `gamerule ` does not have to be memorised.
    private static final @Unmodifiable List<String> GAMERULES = List.of(
            "announceAdvancements", "blockExplosionDropDecay", "commandBlockOutput",
            "commandBlocksEnabled", "disableElytraMovementCheck", "disablePlayerMovementCheck",
            "disableRaids", "doDaylightCycle", "doEntityDrops", "doFireTick", "doImmediateRespawn",
            "doInsomnia", "doLimitedCrafting", "doMobLoot", "doMobSpawning", "doPatrolSpawning",
            "doTileDrops", "doTraderSpawning", "doVinesSpread", "doWardenSpawning", "doWeatherCycle",
            "drowningDamage", "elytraMovementCheck", "enderPearlsVanishOnDeath", "fallDamage",
            "fireDamage", "forgiveDeadPlayers", "freezeDamage", "globalSoundEvents", "keepInventory",
            "lavaSourceConversion", "logAdminCommands", "maxCommandChainLength",
            "maxCommandForkCount", "mobExplosionDropDecay", "mobGriefing", "naturalRegeneration",
            "playersNetherPortalCreativeDelay", "playersNetherPortalDefaultDelay",
            "playersSleepingPercentage", "projectilesCanBreakBlocks", "pvp", "randomTickSpeed",
            "reducedDebugInfo", "respawnRadius", "sendCommandFeedback", "showDeathMessages",
            "snowLayersDestroyOnMelt", "spawnChunkRadius", "spawnMonsterRadius", "spawnRadius",
            "spectatorGenerateChunks", "tntExplosionDropDecay", "universalAnger",
            "waterSourceConversion");

    /// Gamerules whose value is a number rather than true/false. Stored lower-cased because
    /// argument matching compares against the lower-cased user input.
    private static final @Unmodifiable Set<String> NUMERIC_GAMERULES = Set.of(
            "maxcommandchainlength", "maxcommandforkcount", "playersnetherportalcreativedelay",
            "playersnetherportaldefaultdelay", "playerssleepingpercentage", "randomtickspeed",
            "spawnchunkradius", "spawnmonsterradius", "spawnradius");

    private static final @Unmodifiable List<String> DIMENSIONS = List.of(
            "minecraft:overworld", "minecraft:the_end", "minecraft:the_nether");

    /// Vanilla entity ids, for `summon`. Written without the `minecraft:` prefix because
    /// servers accept the bare id and it is much less to type.
    private static final @Unmodifiable List<String> ENTITIES = List.of(
            "allay", "area_effect_cloud", "armadillo", "armor_stand", "arrow", "axolotl", "bat",
            "bee", "blaze", "block_display", "bogged", "breeze", "breeze_wind_charge", "camel",
            "cat", "cave_spider", "chicken", "cod", "command_block_minecart", "cow", "creaking",
            "creeper", "dolphin", "donkey", "dragon_fireball", "drowned", "egg", "elder_guardian",
            "end_crystal", "ender_dragon", "ender_pearl", "enderman", "endermite", "evoker",
            "evoker_fangs", "experience_bottle", "experience_orb", "eye_of_ender", "falling_block",
            "fireball", "firework_rocket", "fox", "frog", "furnace_minecart", "ghast",
            "giant", "glow_item_frame", "glow_squid", "goat", "guardian", "hoglin",
            "hopper_minecart", "horse", "husk", "illusioner", "interaction", "iron_golem",
            "item", "item_display", "item_frame", "leash_knot", "lightning_bolt", "llama",
            "magma_cube", "marker", "minecart", "mooshroom", "mule", "ocelot", "ominous_item_spawner",
            "painting", "panda", "parrot", "phantom", "pig", "piglin", "piglin_brute", "pillager",
            "polar_bear", "potion", "pufferfish", "rabbit", "ravager", "salmon", "sheep", "shulker",
            "shulker_bullet", "silverfish", "skeleton", "skeleton_horse", "slime", "small_fireball",
            "sniffer", "snow_golem", "snowball", "spawner_minecart", "spectral_arrow", "spider",
            "squid", "stray", "strider", "tadpole", "text_display", "tnt", "tnt_minecart",
            "trader_llama", "trident", "tropical_fish", "turtle", "vex", "villager",
            "vindicator", "wandering_trader", "warden", "wind_charge", "witch", "wither",
            "wither_skeleton", "wither_skull", "wolf", "zoglin", "zombie", "zombie_horse",
            "zombie_villager", "zombified_piglin");

    private CommandCompleter() {
    }

    // --------------------------------------------------------------- api ---

    /// Completes the token at `caret` in `text`.
    ///
    /// @param text the whole input
    /// @param caret the caret position (the token being typed ends here)
    /// @param profile the selected server, used to read player and world names; may be null
    /// @param dataDirectory the plugin data directory, searched for `commands.txt`; may be null
    /// @return the completion, or null when nothing matches
    public static @Nullable Completion complete(String text, int caret,
                                                @Nullable ServerProfile profile,
                                                @Nullable Path dataDirectory) {
        if (text == null)
            return null;
        int position = Math.max(0, Math.min(caret, text.length()));

        // the token being typed: walk back to whitespace, forward to whitespace
        int start = position;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1)))
            start--;
        int end = position;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end)))
            end++;

        String partial = text.substring(start, position);
        List<String> before = tokens(text.substring(0, start));

        List<String> candidates;
        if (before.isEmpty()) {
            // Completing the command name itself. A leading `/` is decoration the user may
            // type, so it is stripped before matching (`/ga` must still find `gamemode`).
            String needle = partial.startsWith("/") ? partial.substring(1) : partial;
            Set<String> all = new LinkedHashSet<>(BUILT_IN_COMMANDS);
            all.addAll(customCommands(dataDirectory));
            candidates = filter(all, needle);
        } else {
            String command = canonical(before.get(0));
            List<String> args = before.subList(1, before.size());
            candidates = filter(argumentSuggestions(command, args, profile, dataDirectory), partial);
        }

        if (candidates.isEmpty())
            return null;
        return new Completion(start, end, List.copyOf(candidates));
    }

    /// The text and caret that result from accepting `candidate` for `completion`.
    ///
    /// <p>`appendSpace` adds the separating space that lets the next Tab move on to the next
    /// argument, matching the vanilla chat box. Repeated calls with different candidates are
    /// stable: each one rewrites the same token, so cycling cannot accumulate text.</p>
    public static Applied apply(String text, Completion completion, String candidate, boolean appendSpace) {
        String prefix = text.substring(0, completion.getTokenStart());
        // everything after the token is preserved, so completing mid-line leaves the rest alone
        String tail = text.substring(completion.getTokenEnd());
        String insertion = appendSpace ? candidate + " " : candidate;
        return new Applied(prefix + insertion + tail,
                prefix.length() + insertion.length());
    }

    /// Outcome of [apply]: the new field text and where the caret belongs.
    public record Applied(String text, int caret) {
    }

    // ---------------------------------------------------------- commands ---

    /// Suggestions for the argument at `args.size() + 1` of `command`.
    static @Unmodifiable List<String> argumentSuggestions(String command, List<String> args,
                                                          @Nullable ServerProfile profile,
                                                          @Nullable Path dataDirectory) {
        int index = args.size() + 1;
        String first = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);

        switch (command) {
            case "gamemode", "defaultgamemode" -> {
                if (index == 1)
                    return GAMEMODES;
                if (index == 2)
                    return players(profile);
            }
            case "difficulty" -> {
                if (index == 1)
                    return DIFFICULTIES;
            }
            case "weather" -> {
                if (index == 1)
                    return WEATHERS;
            }
            case "time" -> {
                if (index == 1)
                    return List.of("add", "query", "set");
                if (index == 2 && first.equals("set"))
                    return TIMES;
            }
            case "gamerule" -> {
                if (index == 1)
                    return GAMERULES;
                if (index == 2 && !NUMERIC_GAMERULES.contains(first))
                    return BOOLEANS;
            }
            case "whitelist" -> {
                if (index == 1)
                    return List.of("add", "list", "off", "on", "reload", "remove");
                if (index == 2 && (first.equals("add") || first.equals("remove")))
                    return players(profile);
            }
            case "banlist" -> {
                if (index == 1)
                    return List.of("ips", "players");
            }
            case "help" -> {
                if (index == 1)
                    return commandsWithCustom(dataDirectory);
            }
            case "execute" -> {
                if (index == 1)
                    return List.of("align", "anchored", "as", "at", "facing", "if", "in",
                            "on", "positioned", "rotated", "run", "store", "summon", "unless");
                // `execute in <dimension>` (and the same after positioned/rotated)
                if (first.equals("in"))
                    return DIMENSIONS;
            }
            case "effect" -> {
                if (index == 1)
                    return List.of("clear", "give");
                if (index == 2 && first.equals("give"))
                    return players(profile);
                if (index == 2 && first.equals("clear"))
                    return players(profile);
            }
            case "xp", "experience" -> {
                if (index == 1)
                    return List.of("add", "query", "set");
                if (index == 2)
                    return players(profile);
            }
            case "title" -> {
                if (index == 1)
                    return players(profile);
                if (index == 2)
                    return List.of("actionbar", "clear", "reset", "subtitle", "times", "title");
            }
            case "advancement" -> {
                if (index == 1)
                    return List.of("grant", "revoke");
                if (index == 2)
                    return players(profile);
            }
            case "scoreboard" -> {
                if (index == 1)
                    return List.of("objectives", "players", "teams");
            }
            case "team" -> {
                if (index == 1)
                    return List.of("add", "empty", "join", "leave", "list", "modify", "remove");
            }
            case "bossbar" -> {
                if (index == 1)
                    return List.of("add", "get", "list", "remove", "set");
            }
            case "datapack" -> {
                if (index == 1)
                    return List.of("disable", "enable", "list");
            }
            case "worldborder" -> {
                if (index == 1)
                    return List.of("add", "center", "damage", "get", "set", "warning");
            }
            case "forceload" -> {
                if (index == 1)
                    return List.of("add", "query", "remove");
            }
            case "item" -> {
                if (index == 1)
                    return List.of("block", "entity", "modify", "replace");
            }
            case "random" -> {
                if (index == 1)
                    return List.of("reset", "roll", "value");
            }
            case "return" -> {
                if (index == 1)
                    return List.of("fail", "run");
            }
            case "tick" -> {
                if (index == 1)
                    return List.of("freeze", "rate", "sprint", "step", "unfreeze");
            }
            case "debug" -> {
                if (index == 1)
                    return List.of("function", "start", "stop");
            }
            case "jfr" -> {
                if (index == 1)
                    return List.of("dump", "start", "stop");
            }
            case "perf" -> {
                if (index == 1)
                    return List.of("start", "stop");
            }
            case "waypoint" -> {
                if (index == 1)
                    return List.of("list", "modify");
            }
            case "schedule" -> {
                if (index == 1)
                    return List.of("clear", "function");
            }
            case "loot" -> {
                if (index == 1)
                    return List.of("give", "insert", "spawn");
            }
            case "summon" -> {
                if (index == 1)
                    return ENTITIES;
            }
            default -> {
                // fall through to the player-target default below
            }
        }

        if (index == 1 && PLAYER_FIRST.contains(command))
            return players(profile);
        if (index == 1 && TEXT_COMMANDS.contains(command))
            return List.of();
        return List.of();
    }

    // --------------------------------------------------------------- data ---

    /// Player names the server knows, plus the target selectors every command accepts.
    public static @Unmodifiable List<String> players(@Nullable ServerProfile profile) {
        Set<String> names = new LinkedHashSet<>(SELECTORS);
        if (profile != null) {
            Path directory = directoryOf(profile);
            if (directory != null) {
                for (String file : new String[]{"usercache.json", "ops.json", "whitelist.json",
                        "banned-players.json"}) {
                    readJsonNames(directory.resolve(file), names);
                }
            }
        }
        return sorted(names);
    }

    /// World names: every directory in the server folder that holds a `level.dat`.
    public static @Unmodifiable List<String> worlds(@Nullable ServerProfile profile) {
        Set<String> names = new LinkedHashSet<>();
        Path directory = profile == null ? null : directoryOf(profile);
        if (directory != null && Files.isDirectory(directory)) {
            try (Stream<Path> children = Files.list(directory)) {
                children.filter(Files::isDirectory)
                        .filter(child -> Files.isRegularFile(child.resolve("level.dat")))
                        .forEach(child -> names.add(child.getFileName().toString()));
            } catch (IOException ignored) {
                // an unreadable server directory simply yields no world names
            }
        }
        return sorted(names);
    }

    /// The built-in command list.
    public static @Unmodifiable List<String> commands() {
        return sorted(new LinkedHashSet<>(BUILT_IN_COMMANDS));
    }

    /// Built-in commands merged with the user's `commands.txt`.
    public static @Unmodifiable List<String> commandsWithCustom(@Nullable Path dataDirectory) {
        Set<String> all = new LinkedHashSet<>(BUILT_IN_COMMANDS);
        all.addAll(customCommands(dataDirectory));
        return sorted(all);
    }

    /// Reads `commands.txt` from the plugin data directory; missing or unreadable files are
    /// treated as empty rather than as an error.
    public static @Unmodifiable List<String> customCommands(@Nullable Path dataDirectory) {
        if (dataDirectory == null)
            return List.of();
        Path file = dataDirectory.resolve(CUSTOM_COMMANDS_FILE);
        if (!Files.isRegularFile(file))
            return List.of();
        Set<String> names = new LinkedHashSet<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                // accept both `spark` and `/spark`
                if (line.startsWith("/"))
                    line = line.substring(1).trim();
                if (line.isEmpty() || line.contains(" "))
                    continue;
                names.add(line);
                if (names.size() >= CUSTOM_COMMAND_LIMIT)
                    break;
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return sorted(names);
    }

    // ------------------------------------------------------------ helpers ---

    /// Strips a leading `/` so `/gamemode` and `gamemode` behave the same.
    static String canonical(String token) {
        String name = token.toLowerCase(Locale.ROOT);
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /// Splits on whitespace, dropping empty tokens.
    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            if (!token.isEmpty())
                result.add(token);
        }
        return result;
    }

    /// Case-insensitive prefix filter, alphabetically ordered.
    private static List<String> filter(Iterable<String> source, String partial) {
        String needle = partial.toLowerCase(Locale.ROOT);
        Set<String> matches = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String candidate : source) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(needle))
                matches.add(candidate);
        }
        return List.copyOf(matches);
    }

    private static List<String> sorted(Set<String> names) {
        TreeSet<String> ordered = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ordered.addAll(names);
        return List.copyOf(ordered);
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

    /// Collects `"name": "..."` values from one of the server's json files.
    private static void readJsonNames(Path file, Set<String> names) {
        if (!Files.isRegularFile(file))
            return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = JSON_NAME.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!name.isBlank())
                    names.add(name);
            }
        } catch (IOException ignored) {
            // a corrupt json file simply contributes nothing
        }
    }
}
