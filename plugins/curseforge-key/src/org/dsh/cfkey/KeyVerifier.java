package org.dsh.cfkey;

import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.ResponseCodeException;

/**
 * Checks a CurseForge API key before it is accepted.
 *
 * <h2>Why a key is worth verifying</h2>
 * <p>Without a check, a mistyped key is indistinguishable from a working one until the user
 * clicks a download source and gets an empty list — CurseForge answers 403 with
 * {@code Forbidden: API Key missing or invalid}, which the launcher reports only as a failed
 * search. Verifying up front turns that into an immediate, specific message.</p>
 *
 * <p>The check calls the cheapest endpoint that needs the key: the category list for
 * Minecraft. It is small, requires the same {@code X-API-KEY} header as everything else, and
 * does not depend on any particular addon existing.</p>
 */
final class KeyVerifier {

    /// CurseForge's API root.
    private static final String PREFIX = "https://api.curseforge.com";

    /// Minecraft's game id at CurseForge.
    private static final int GAME_ID = 432;

    /// The endpoint used to check a key.
    ///
    /// <p>Chosen because it is the one measured to answer {@code 200} with a valid key and
    /// {@code 403} without one. See [KeyVerifier#verify] for the two endpoints that looked
    /// right and were not.</p>
    private static final String GAME_URL = PREFIX + "/v1/games/" + GAME_ID;

    /// How long to wait for a verdict, in milliseconds.
    static final int TIMEOUT_MILLIS = 8000;

    private KeyVerifier() {
    }

    /// What the verification found.
    enum Verdict {
        /// The key works.
        VALID,

        /// CurseForge rejected the key.
        REJECTED,

        /// The request could not be made at all: offline, blocked, or timed out.
        UNREACHABLE
    }

    /// The outcome of a verification, with something to show the user.
    ///
    /// @param verdict what was found
    /// @param detail  a human-readable detail line, may be empty
    record Outcome(Verdict verdict, String detail) {

        /// True when the key may be used.
        boolean isValid() {
            return verdict == Verdict.VALID;
        }
    }

    /// Verifies {@code key} by calling CurseForge.
    ///
    /// <p>Runs on the calling thread and therefore blocks; callers must not invoke this on
    /// the JavaFX thread.</p>
    ///
    /// <h2>Why this endpoint</h2>
    /// <p>{@code /v1/categories} is <em>not</em> a valid check: it answers without any key
    /// at all, so a made-up key passes it — measured, and the reason this class first
    /// reported every key as valid. The search endpoint used here genuinely requires the
    /// header and answers {@code 403 Forbidden: API Key missing or invalid} without it,
    /// which is the same call the launcher makes when the source is used for real.</p>
    ///
    /// <p>The response body is inspected as well as the status code, because a body can
    /// arrive even on an error and "the request did not throw" is not proof of anything.
    /// </p>
    ///
    /// @param key the key to check
    /// @return the outcome, never null
    /// Verifies {@code key} by calling CurseForge.
    ///
    /// <p>Runs on the calling thread and therefore blocks; callers must not invoke this on
    /// the JavaFX thread.</p>
    ///
    /// <h2>Choosing the endpoint took three tries — all three were measured</h2>
    /// <ol>
    ///   <li>{@code /v1/categories} — <b>wrong</b>: it answers {@code 200} with no key at
    ///       all, so every string passed, including a made-up one.</li>
    ///   <li>{@code /v1/mods/search} — <b>also wrong</b>: it answers {@code 403} even for a
    ///       perfectly valid key. Measured, {@code /v1/mods/search} returns 403 with and
    ///       without a key for this account while every other endpoint accepts the same key,
    ///       so it is gated separately by CurseForge and is useless as a key check. Using it
    ///       told a user with working credentials that they had been rejected.</li>
    ///   <li>{@code /v1/games/432} — correct: {@code 200} with a valid key, {@code 403}
    ///       without one. That is exactly the discrimination a check needs.</li>
    /// </ol>
    ///
    /// @param key the key to check
    /// @return the outcome, never null
    static Outcome verify(String key) {
        if (key == null || key.trim().isEmpty()) {
            return new Outcome(Verdict.REJECTED, "API Key 为空");
        }
        try {
            Response response = rawGet(GAME_URL, key.trim());
            if (response.code() == 401 || response.code() == 403) {
                return new Outcome(Verdict.REJECTED,
                        "CurseForge 拒绝了这个 Key（HTTP " + response.code() + "）");
            }
            if (response.code() / 100 != 2) {
                return new Outcome(Verdict.UNREACHABLE, "CurseForge 返回 HTTP " + response.code());
            }
            if (isErrorPayload(response.body())) {
                return new Outcome(Verdict.REJECTED, "CurseForge 拒绝了这个 Key");
            }
            return new Outcome(Verdict.VALID, "");
        } catch (Throwable t) {
            return classify(t);
        }
    }

    /// Issues the request and returns the body, treating 401/403 as a normal answer.
    ///
    /// <p>The launcher's {@code ignoreHttpErrorCode} does not actually make an error
    /// response readable: {@code getInputStream()} still throws a bare
    /// {@code IOException("Server returned HTTP response code: 403 ...")} that carries no
    /// status code, so a caller cannot tell "wrong key" from "no network". A plain
    /// {@link java.net.HttpURLConnection} is used here instead, where the status code is
    /// always available and the error stream can be read.</p>
    ///
    /// @return the status code and body
    /// @throws Exception for anything other than an HTTP answer
    private static Response rawGet(String url, String key) throws Exception {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("X-API-KEY", key);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "HMCL-CurseForgeKeyPlugin");

        int code = connection.getResponseCode();
        java.io.InputStream stream =
                code / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(
                stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(code, body);
    }

    /// An HTTP answer.
    ///
    /// @param code the status code
    /// @param body the body, empty when there was none
    private record Response(int code, String body) {
    }

    /// Turns a raw response or an exception into a verdict.
    private static Outcome classify(Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (message.contains("403") || message.contains("401")) {
            return new Outcome(Verdict.REJECTED, "CurseForge 拒绝了这个 Key（HTTP 401/403）");
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("api key")) {
            return new Outcome(Verdict.REJECTED, "CurseForge 拒绝了这个 Key");
        }
        return new Outcome(Verdict.UNREACHABLE, "无法连接 CurseForge：" + message);
    }

    /// True when the response body is an error rather than data.
    ///
    /// <p>CurseForge answers a bad key with {@code {"error": ...}} or the bare text
    /// {@code Forbidden: API Key missing or invalid}; a good key gets a JSON array or an
    /// object with a {@code data} field.</p>
    private static boolean isErrorPayload(String body) {
        if (body == null) {
            return true;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("api key missing") || lower.contains("api key invalid")
                || lower.startsWith("forbidden")) {
            return true;
        }
        // A valid answer is a JSON document; a body starting with "<" is an HTML error page
        // from a proxy.
        return !(trimmed.startsWith("[") || trimmed.startsWith("{"));
    }
}
