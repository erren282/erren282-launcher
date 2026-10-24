import org.dsh.servermanager.CommandCompleter;
import org.dsh.servermanager.ServerProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Verifies the console Tab-completion engine: command names, per-command arguments, data read
/// from the server directory, `commands.txt`, and — most importantly — that repeated Tab
/// presses cycle without corrupting the input.
public final class CompletionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path serverDir = Files.createTempDirectory("completion-server");
        Path dataDir = Files.createTempDirectory("completion-data");

        // A server directory as the real one looks: player lists and a world folder.
        Files.writeString(serverDir.resolve("usercache.json"),
                "[{\"name\":\"wocao_321\",\"uuid\":\"1\"},{\"name\":\"MC66\",\"uuid\":\"2\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("ops.json"),
                "[{\"name\":\"AdminUser\",\"uuid\":\"3\",\"level\":4}]", StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("whitelist.json"),
                "[{\"name\":\"Friend1\",\"uuid\":\"4\"}]", StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("banned-players.json"),
                "[{\"name\":\"Griefer\",\"uuid\":\"5\"}]", StandardCharsets.UTF_8);
        Files.createDirectories(serverDir.resolve("world"));
        Files.writeString(serverDir.resolve("world").resolve("level.dat"), "x");
        Files.createDirectories(serverDir.resolve("bw2"));
        Files.writeString(serverDir.resolve("bw2").resolve("level.dat"), "x");
        Files.createDirectories(serverDir.resolve("notaworld"));   // no level.dat

        ServerProfile profile = new ServerProfile();
        profile.setName("54");
        profile.setDirectory(serverDir.toString());
        profile.setJarFile("server.jar");

        System.out.println("=========== 1. 命令名补全 ===========");
        checkCompletes("空输入 -> 列出全部命令", "", 0, profile, dataDir, "gamemode", "list");
        checkCompletes("`ga` -> gamemode", "ga", 2, profile, dataDir, "gamemode");
        checkCompletes("`gam` -> gamemode", "gam", 3, profile, dataDir, "gamemode");
        checkCompletes("`/ga`（带斜杠）-> gamemode", "/ga", 3, profile, dataDir, "gamemode");
        // note: "weather" starts with "we", so it is correctly absent from the "wh" matches
        checkCompletes("`wh` -> whitelist/whisper", "wh", 2, profile, dataDir,
                "whitelist", "whisper");
        checkCompletes("`we` -> weather", "we", 2, profile, dataDir, "weather");
        checkCompletes("`sa` -> save-all/save-off/save-on/say", "sa", 2, profile, dataDir,
                "save-all", "save-off", "save-on", "say");
        checkNone("`zzz` 无匹配", "zzz", 3, profile, dataDir);

        System.out.println();
        System.out.println("=========== 2. 参数补全（固定值表） ===========");
        checkCompletes("gamemode <TAB>", "gamemode ", 9, profile, dataDir,
                "adventure", "creative", "spectator", "survival");
        checkCompletes("gamemode cr<TAB>", "gamemode cr", 11, profile, dataDir, "creative");
        checkCompletes("difficulty <TAB>", "difficulty ", 11, profile, dataDir,
                "easy", "hard", "normal", "peaceful");
        checkCompletes("weather <TAB>", "weather ", 8, profile, dataDir, "clear", "rain", "thunder");
        checkCompletes("time set <TAB>", "time set ", 9, profile, dataDir,
                "day", "midnight", "noon", "night");
        checkCompletes("time <TAB> -> add/query/set", "time ", 5, profile, dataDir,
                "add", "query", "set");
        checkCompletes("gamerule <TAB>", "gamerule ", 9, profile, dataDir,
                "keepInventory", "mobGriefing", "doDaylightCycle");
        checkCompletes("gamerule keepInventory <TAB>", "gamerule keepInventory ", 23, profile, dataDir,
                "false", "true");
        checkNone("gamerule randomTickSpeed <TAB> 不给 true/false（是数字）",
                "gamerule randomTickSpeed ", 25, profile, dataDir);
        checkCompletes("whitelist <TAB>", "whitelist ", 10, profile, dataDir,
                "add", "list", "off", "on", "reload", "remove");
        checkCompletes("execute <TAB>", "execute ", 8, profile, dataDir, "as", "at", "run", "store");
        checkCompletes("summon <TAB>", "summon ", 7, profile, dataDir, "creeper", "zombie", "warden");

        System.out.println();
        System.out.println("=========== 3. 玩家名（读服务端目录） ===========");
        checkCompletes("op <TAB> 含 usercache 玩家", "op ", 3, profile, dataDir, "wocao_321", "MC66");
        checkCompletes("op <TAB> 含 op 玩家", "op ", 3, profile, dataDir, "AdminUser");
        checkCompletes("op <TAB> 含白名单玩家", "op ", 3, profile, dataDir, "Friend1");
        checkCompletes("ban <TAB> 含被封禁玩家", "ban ", 4, profile, dataDir, "Griefer");
        checkCompletes("op <TAB> 含目标选择器", "op ", 3, profile, dataDir, "@a", "@p", "@s");
        checkCompletes("kick <TAB> 含玩家", "kick ", 5, profile, dataDir, "wocao_321");
        checkCompletes("tp <TAB> 含玩家", "tp ", 3, profile, dataDir, "wocao_321");
        checkCompletes("op wocao<TAB> 前缀过滤", "op wocao", 8, profile, dataDir, "wocao_321");
        checkCompletes("tell <TAB> 补玩家（第一个参数是玩家）", "tell ", 5, profile, dataDir, "wocao_321");
        checkNone("say <TAB> 不给补全（自由文本）", "say ", 4, profile, dataDir);

        System.out.println();
        System.out.println("=========== 4. 世界名 ===========");
        List<String> worlds = CommandCompleter.worlds(profile);
        check("worlds() 含 world（实际 " + worlds + "）", worlds.contains("world"));
        check("worlds() 含 bw2", worlds.contains("bw2"));
        check("worlds() 不含 notaworld（无 level.dat）", !worlds.contains("notaworld"));

        System.out.println();
        System.out.println("=========== 5. commands.txt 自定义命令 ===========");
        Files.writeString(dataDir.resolve("commands.txt"),
                "# 自定义插件命令\nmyplugin\n/spark\n\nbad name\n", StandardCharsets.UTF_8);
        checkCompletes("自定义命令参与补全", "myp", 3, profile, dataDir, "myplugin");
        checkCompletes("带斜杠的行也能识别", "spa", 3, profile, dataDir, "spark");
        List<String> custom = CommandCompleter.customCommands(dataDir);
        check("跳过含空格的行（实际 " + custom + "）", !custom.contains("bad name"));
        check("跳过注释行", !custom.stream().anyMatch(s -> s.startsWith("#")));

        System.out.println();
        System.out.println("=========== 6. apply：文本与光标 ===========");
        // completing a command name appends a space, ready for the first argument
        CommandCompleter.Completion c1 = CommandCompleter.complete("gam", 3, profile, dataDir);
        CommandCompleter.Applied a1 = CommandCompleter.apply("gam", c1, "gamemode", true);
        check("命令名补全后有空格（实际 \"" + a1.text() + "\"）", a1.text().equals("gamemode "));
        check("光标在末尾（实际 " + a1.caret() + "）", a1.caret() == "gamemode ".length());

        // cycling from the SAME base must not accumulate text
        CommandCompleter.Completion c2 = CommandCompleter.complete("gamemode ", 9, profile, dataDir);
        String base = "gamemode ";
        CommandCompleter.Applied adv = CommandCompleter.apply(base, c2, "adventure", false);
        CommandCompleter.Applied cre = CommandCompleter.apply(base, c2, "creative", false);
        check("第一次循环 -> gamemode adventure（实际 \"" + adv.text() + "\"）",
                adv.text().equals("gamemode adventure"));
        check("第二次循环不累积 -> gamemode creative（实际 \"" + cre.text() + "\"）",
                cre.text().equals("gamemode creative"));

        // completing mid-line must leave the tail intact
        CommandCompleter.Completion c3 = CommandCompleter.complete("op woc", 6, profile, dataDir);
        CommandCompleter.Applied a3 = CommandCompleter.apply("op woc extra", c3, "wocao_321", false);
        check("行中补全保留后半段（实际 \"" + a3.text() + "\"）",
                a3.text().equals("op wocao_321 extra"));

        // full-width line: replacing the last token keeps earlier tokens
        CommandCompleter.Completion c4 = CommandCompleter.complete("gamemode surv", 13, profile, dataDir);
        CommandCompleter.Applied a4 = CommandCompleter.apply("gamemode surv", c4, "survival", false);
        check("多 token 行补全（实际 \"" + a4.text() + "\"）",
                a4.text().equals("gamemode survival"));

        System.out.println();
        System.out.println("=========== 7. 边界情况 ===========");
        // With no server selected there are no player names, but the target selectors every
        // command accepts are still valid suggestions, and nothing may throw.
        checkCompletes("null profile 不崩溃，仍给出选择器", "op ", 3, null, dataDir, "@a", "@p");
        checkNone("null profile 不包含玩家名", "op wocao", 8, null, dataDir);
        checkCompletes("null profile 仍能补命令名", "gam", 3, null, dataDir, "gamemode");
        checkCompletes("null dataDirectory 不崩溃", "gam", 3, profile, null, "gamemode");
        checkNone("null dataDirectory 不含自定义命令", "myp", 3, profile, null);
        CommandCompleter.Completion before = CommandCompleter.complete("", 0, profile, dataDir);
        check("空输入时 tokenStart=0（实际 " + before.getTokenStart() + "）",
                before.getTokenStart() == 0);
        // caret in the middle of a word still completes that word
        CommandCompleter.Completion mid = CommandCompleter.complete("gamemode", 3, profile, dataDir);
        check("光标在词中仍能补全", mid != null && mid.getCandidates().contains("gamemode"));

        System.out.println();
        System.out.println("==================================================");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("==================================================");
        if (failed > 0)
            System.exit(1);
    }

    private static void checkCompletes(String label, String text, int caret, ServerProfile profile,
                                       Path dataDir, String... expected) {
        CommandCompleter.Completion completion = CommandCompleter.complete(text, caret, profile, dataDir);
        if (completion == null) {
            check(label + "  [期望含 " + String.join(",", expected) + "，实际无结果]", false);
            return;
        }
        List<String> candidates = completion.getCandidates();
        StringBuilder missing = new StringBuilder();
        for (String want : expected) {
            if (!candidates.contains(want))
                missing.append(want).append(' ');
        }
        if (missing.isEmpty()) {
            check(label + "  [" + candidates.size() + " 个候选]", true);
        } else {
            check(label + "  [缺少: " + missing.toString().trim() + "，实际: " + candidates + "]", false);
        }
    }

    private static void checkNone(String label, String text, int caret, ServerProfile profile,
                                  Path dataDir) {
        CommandCompleter.Completion completion = CommandCompleter.complete(text, caret, profile, dataDir);
        boolean empty = completion == null || completion.getCandidates().isEmpty();
        check(label + (empty ? "" : "  [实际返回 " + completion.getCandidates() + "]"), empty);
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}
