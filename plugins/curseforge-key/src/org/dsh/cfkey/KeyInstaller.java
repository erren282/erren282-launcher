package org.dsh.cfkey;

import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.util.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Puts an API key into the launcher's {@code CurseForgeRemoteAddonRepository.API_KEY}.
 *
 * <h2>The problem</h2>
 * <p>The launcher decides whether CurseForge exists at all from one field:</p>
 * <pre>
 *   public static final String API_KEY =
 *       System.getProperty("hmcl.curseforge.apikey",
 *           JarUtils.getAttribute("hmcl.curseforge.apikey", ""));
 *
 *   public static boolean isAvailable() { return !API_KEY.isEmpty(); }
 * </pre>
 * <p>On a build without a key compiled in, that field is {@code ""}, {@code isAvailable()}
 * is false, and the download page never even lists CurseForge. To let the user supply a key
 * at runtime the field has to be replaced.</p>
 *
 * <h2>Why this needs more than {@code Field.set}</h2>
 * <p>{@code API_KEY} is {@code static final}, so {@link Field#set} throws
 * {@code IllegalAccessException} (measured, not assumed). Since Java 12 the old trick of
 * clearing the {@code final} bit through {@code Field.modifiers} no longer works either —
 * that field no longer exists. What does work is writing the field's storage directly with
 * {@code sun.misc.Unsafe}, which is why this class uses it.</p>
 *
 * <p>{@code Unsafe} is terminally deprecated and will be removed in a future JDK, so the
 * failure mode is handled explicitly: if the write cannot be done, {@link #available()}
 * reports it and the caller tells the user instead of silently doing nothing.</p>
 */
final class KeyInstaller {

    /// The field that has to be replaced.
    private static final String FIELD_NAME = "API_KEY";

    private KeyInstaller() {
    }

    /// True when a key is currently in effect.
    static boolean available() {
        try {
            return CurseForgeRemoteAddonRepository.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /// The key currently in effect, or an empty string.
    static String currentKey() {
        try {
            return CurseForgeRemoteAddonRepository.API_KEY;
        } catch (Throwable t) {
            return "";
        }
    }

    /// Replaces the launcher's API key.
    ///
    /// <p>The value is also set as a system property, which is the launcher's own documented
    /// override. That alone does not help a running launcher — the field is read once during
    /// class initialisation — but it means the key survives if anything re-reads the
    /// property, and it keeps the two sources consistent.</p>
    ///
    /// @param key the key to install; must not be blank
    /// @return a result describing what happened, never null
    static Result install(String key) {
        if (key == null || key.trim().isEmpty()) {
            return Result.failed("API Key 不能为空");
        }
        String value = key.trim();

        try {
            System.setProperty("hmcl.curseforge.apikey", value);
        } catch (Throwable ignored) {
            // A security manager could refuse this; the field write below is what matters.
        }

        Field field;
        try {
            field = CurseForgeRemoteAddonRepository.class.getDeclaredField(FIELD_NAME);
        } catch (Throwable t) {
            return Result.failed("这个启动器版本里没有 API_KEY 字段，无法设置。", t);
        }
        try {
            field.setAccessible(true);
        } catch (Throwable t) {
            return Result.failed("无法访问 API_KEY 字段：" + t.getClass().getSimpleName(), t);
        }

        // Fast path: a field that is no longer final (or a future launcher that made it
        // writable) can be set normally.
        if (!Modifier.isFinal(field.getModifiers())) {
            try {
                field.set(null, value);
                return verify(value);
            } catch (Throwable ignored) {
                // Fall through to the storage-level write.
            }
        }

        try {
            writeThroughUnsafe(field, value);
        } catch (Throwable t) {
            return Result.failed("无法写入 API_KEY（当前 Java 版本可能已移除 sun.misc.Unsafe）。", t);
        }

        // Writing API_KEY is only half the job — see syncNetworkApiKeys.
        boolean synced = syncNetworkApiKeys(value);
        Result result = verify(value);
        if (!result.ok()) {
            return result;
        }
        if (!synced) {
            return Result.failed("API Key 已写入，但启动器的网络层未能同步，请求不会带上 Key。");
        }
        return result;
    }

    /// Refreshes the launcher's own copy of the key.
    ///
    /// <h2>Why this is required</h2>
    /// <p>{@code NetworkUtils} does not read {@code API_KEY} when it sends a request. It
    /// snapshots it once, into its own table, during class initialisation:</p>
    /// <pre>
    ///   private static final List&lt;Pair&lt;String,String&gt;&gt; API_KEYS;
    ///   static {
    ///       if (CurseForgeRemoteAddonRepository.API_KEY.isEmpty())
    ///           API_KEYS = List.of();                       // ← permanent empty table
    ///       else
    ///           API_KEYS = List.of(pair("api.curseforge.com", ...), ...);
    ///   }
    /// </pre>
    /// <p>By the time a plugin runs, that class is already initialised with an empty table,
    /// because the build had no key at startup. Replacing {@code API_KEY} afterwards makes
    /// {@code isAvailable()} true — so CurseForge appears in the list — but every request is
    /// still sent <em>without</em> the header, and CurseForge answers 403. That is exactly the
    /// "correct key rejected" symptom: measured, {@code API_KEYS} stayed {@code []} while
    /// {@code API_KEY} held the new value.</p>
    ///
    /// <p>So the table has to be rebuilt as well. It is {@code private static final}, hence
    /// the same {@code Unsafe} write.</p>
    ///
    /// @param value the key that was just installed
    /// @return true when the network layer will now send the key
    private static boolean syncNetworkApiKeys(String value) {
        try {
            Class<?> networkUtils = Class.forName("org.jackhuang.hmcl.util.io.NetworkUtils");
            Field table = networkUtils.getDeclaredField("API_KEYS");
            table.setAccessible(true);

            // The same pairs the launcher would have built had the key been present at
            // startup: the API host and the CDN.
            List<Pair<String, String>> pairs = List.of(
                    Pair.pair("api.curseforge.com", value),
                    Pair.pair("forgecdn.net", value));

            try {
                table.set(null, pairs);
            } catch (Throwable notWritable) {
                writeThroughUnsafe(table, pairs);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /// Writes a static field's storage directly, bypassing the {@code final} modifier.
    ///
    /// @param field the field to write
    /// @param value the value to store
    /// @throws Exception when {@code Unsafe} is unavailable or refuses the write
    private static void writeThroughUnsafe(Field field, Object value) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
        Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        Method putObject = unsafeClass.getMethod("putObject",
                Object.class, long.class, Object.class);

        Object base = staticFieldBase.invoke(unsafe, field);
        long offset = (long) staticFieldOffset.invoke(unsafe, field);
        putObject.invoke(unsafe, base, offset, value);
    }

    /// Confirms the write actually took effect, by reading the field back and asking the
    /// launcher itself.
    ///
    /// @param expected the value that should now be in effect
    private static Result verify(String expected) {
        String actual = currentKey();
        if (!expected.equals(actual)) {
            return Result.failed("写入后读回的值不一致，设置没有生效。");
        }
        if (!available()) {
            return Result.failed("API Key 已写入，但启动器仍认为 CurseForge 不可用。");
        }
        return Result.succeeded();
    }

    /// What happened when a key was installed.
    record Result(boolean ok, String message, Throwable cause) {

        /// A success with no message needing to be shown.
        static Result succeeded() {
            return new Result(true, "", null);
        }

        /// A failure carrying a message for the user.
        ///
        /// @param message what went wrong, in the user's language
        static Result failed(String message) {
            return new Result(false, message, null);
        }

        /// A failure with an underlying exception for the log.
        static Result failed(String message, Throwable cause) {
            return new Result(false, message, cause);
        }
    }
}
