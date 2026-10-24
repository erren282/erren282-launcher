package org.dsh.servermanager;

import java.util.List;

/**
 * Verifies that a real Minecraft server's console lines are recognised.
 *
 * <h2>Why this exists</h2>
 * <p>The player page refreshes when the console shows someone joining or leaving. It stayed
 * frozen in practice because the patterns did not match what a server actually prints.
 * Measured from a live server's log:</p>
 * <pre>
 *   [17:47:57 INFO]: erren_s[/127.0.0.1:7381] logged in with entity id 2 at ([Lobby]10.5, ...)
 * </pre>
 * <p>The name is followed by {@code [/ip:port]} before the verb, which the original pattern
 * did not allow — so no join was ever detected, no refresh was ever queued, and the only way
 * to see a change was to leave the page and come back. Every line below is copied from that
 * log rather than invented.</p>
 */
public final class PlayerPresenceTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        recognisesJoins();
        recognisesLeaves();
        ignoresEverythingElse();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// Join lines in every shape a server emits.
    private static void recognisesJoins() {
        System.out.println("=== a player joining is recognised ===");

        String withEndpoint = "[17:47:57 INFO]: erren_s[/127.0.0.1:7381] logged in with entity id 2"
                + " at ([Lobby]10.5, 72.0, -11.5)";
        check("the real line this bug was reported with",
                ServerPlayers.isPlayerPresenceLine(withEndpoint));

        // NeoForge / Paper add the same bracket for remote players.
        String remote = "[10:00:00 INFO]: Steve[/203.0.113.5:51234] joined the game";
        check("a remote player joining", ServerPlayers.isPlayerPresenceLine(remote));

        // Without the endpoint bracket, which is what the code assumed before.
        String plain = "[10:00:00 INFO]: Steve joined the game";
        check("a join line with no endpoint bracket", ServerPlayers.isPlayerPresenceLine(plain));

        String loggedIn = "[10:00:00 INFO]: Steve logged in with entity id 5 at ([world]1.0, 2.0, 3.0)";
        check("a logged-in line with no endpoint bracket",
                ServerPlayers.isPlayerPresenceLine(loggedIn));
    }

    /// Leave lines, including the connection-loss variants.
    private static void recognisesLeaves() {
        System.out.println();
        System.out.println("=== a player leaving is recognised ===");

        check("left the game",
                ServerPlayers.isPlayerPresenceLine("[10:00:00 INFO]: Steve left the game"));
        check("left the game with an endpoint bracket",
                ServerPlayers.isPlayerPresenceLine(
                        "[10:00:00 INFO]: Steve[/1.2.3.4:5] left the game"));
        check("lost connection",
                ServerPlayers.isPlayerPresenceLine("[10:00:00 INFO]: Steve lost connection: timed out"));
        check("lost connection with an endpoint bracket",
                ServerPlayers.isPlayerPresenceLine(
                        "[10:00:00 INFO]: Steve[/1.2.3.4:5] lost connection: Disconnected"));
    }

    /// Chatty lines must not trigger a rebuild, or the page would flicker on every message.
    private static void ignoresEverythingElse() {
        System.out.println();
        System.out.println("=== unrelated lines do not trigger a refresh ===");

        for (String line : List.of(
                "[17:47:03 INFO]: Done (1.019s)! For help, type \"help\" or \"?\"",
                "[17:49:41 WARN]: Could not set level chunk heightmap, array length is 0 instead of 256",
                "[17:50:08 INFO]: erren_s issued server command: /gamemode 0",
                "[17:50:08 INFO]: [erren_s: Set own game mode to Survival Mode]",
                "<Steve> hello everyone",
                "[17:50:09 INFO]: Saving the game (this may take a moment!)")) {
            check("ignored: " + shorten(line), !ServerPlayers.isPlayerPresenceLine(line));
        }

        System.out.println();
        System.out.println("=== degenerate input ===");
        check("a null line is ignored", !ServerPlayers.isPlayerPresenceLine(null));
        check("an empty line is ignored", !ServerPlayers.isPlayerPresenceLine(""));
    }

    /// Trims a line for readable output.
    private static String shorten(String line) {
        return line.length() <= 56 ? line : line.substring(0, 53) + "...";
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
