package org.dsh.servermanager;

import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/// Decides whether the Java runtime configured for a server can actually start it.
///
/// <p>Three independent problems are recognised, in this order of severity:</p>
///
/// <ol>
///   <li><b>Java too old</b> — the major version is below the minimum the Minecraft version
///       needs (1.18+ wants 17, 1.20.5+ wants 21, 26.1+ wants 25). Such a server dies
///       immediately with `UnsupportedClassVersionError`.</li>
///   <li><b>Trust store too old</b> — the runtime cannot validate Mojang's current TLS
///       certificate chain, so a Paperclip server fails at `Downloading vanilla jar...`
///       with `PKIX path building failed`. Mojang's own Java 8 (`jre-legacy`, 8u51) has
///       exactly this defect, which is why a 1.8.8 Paper server looks "version matched"
///       but still refuses to start.</li>
///   <li><b>Java too new</b> — reported only as an advisory warning, because most legacy
///       servers do still boot on a modern runtime; it is the plugins and mods that break.</li>
/// </ol>
///
/// <p>The trust store is judged by loading the <i>target</i> runtime's `cacerts` and running a
/// real TLS handshake against Mojang. The set of trusted roots cannot be judged by name: some
/// perfectly working runtimes lack a root that others carry (for example a JDK 17 without
/// `DigiCert Global Root G2` still validates Mojang), while the actual failure mode is a
/// certificate-path failure. Only a genuine `PKIX`/`CertPathBuilder` failure is reported as a
/// broken trust store, so a plain network outage never raises a false alarm.</p>
@NotNullByDefault
public final class JavaCompatibility {

    /// How bad a mismatch is. `WARNING` still allows the server to start.
    public enum Severity {
        /// No problem detected.
        OK,
        /// The server will probably start, but the combination is risky.
        WARNING,
        /// The server cannot start; starting it would fail immediately.
        BLOCKING
    }

    /// Outcome of probing one runtime's certificate store against Mojang.
    private enum Trust {
        /// A handshake succeeded: the runtime can reach Mojang.
        TRUSTED,
        /// The handshake failed while building a certificate path: the store is too old.
        UNTRUSTED,
        /// Could not tell (offline, DNS failure, timeout). Never treated as a problem.
        UNKNOWN
    }

    private static final Pattern JAVA_VERSION = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"");
    /// `version=1.8.8` inside a Paperclip jar's `patch.properties`.
    private static final Pattern PAPERCLIP_VERSION = Pattern.compile("(?m)^version=([^\\r\\n]+)");
    /// Hosts probed for TLS trust. Both sit behind the same Microsoft/DigiCert chain.
    private static final String[] PROBE_HOSTS = {"launcher.mojang.com", "piston-meta.mojang.com"};
    private static final int PROBE_TIMEOUT_MS = 8_000;
    /// Trust results are cached per `cacerts` file (path + last-modified), because the probe
    /// opens sockets and is therefore too slow to repeat on every start click.
    private static final Map<String, Trust> TRUST_CACHE = new ConcurrentHashMap<>();

    private JavaCompatibility() {
    }

    /// The outcome of checking one server profile, with everything the UI needs to explain it.
    public static final class Verdict {
        private final Severity severity;
        private final String headline;
        private final String detail;
        private final int requiredMajor;
        private final int actualMajor;
        private final String actualVersion;
        private final String javaPath;
        private final String mcVersion;
        private final boolean trustBroken;

        Verdict(Severity severity, String headline, String detail, int requiredMajor, int actualMajor,
                String actualVersion, String javaPath, String mcVersion, boolean trustBroken) {
            this.severity = severity;
            this.headline = headline;
            this.detail = detail;
            this.requiredMajor = requiredMajor;
            this.actualMajor = actualMajor;
            this.actualVersion = actualVersion;
            this.javaPath = javaPath;
            this.mcVersion = mcVersion;
            this.trustBroken = trustBroken;
        }

        public Severity getSeverity() { return severity; }

        /// One-line summary of the problem.
        public String getHeadline() { return headline; }

        /// Multi-line explanation shown in the dialog body.
        public String getDetail() { return detail; }

        /// Lowest Java major version this Minecraft version supports, or 0 when unknown.
        public int getRequiredMajor() { return requiredMajor; }

        /// Major version of the currently configured runtime, or 0 when unknown.
        public int getActualMajor() { return actualMajor; }

        /// Full version string of the configured runtime, e.g. `1.8.0_51`.
        public String getActualVersion() { return actualVersion; }

        /// Executable of the configured runtime.
        public String getJavaPath() { return javaPath; }

        /// Minecraft version the check was made against.
        public String getMcVersion() { return mcVersion; }

        /// True when the runtime cannot validate Mojang's certificate chain.
        public boolean isTrustBroken() { return trustBroken; }

        /// Java major version that should be installed to fix the problem.
        public int getRecommendedMajor() {
            if (requiredMajor > 0)
                return requiredMajor;
            return actualMajor > 0 ? actualMajor : 17;
        }
    }

    /// Checks the Java runtime configured for `profile` against the server it will run.
    ///
    /// <p>Runs network I/O (the trust probe), so call it from a background thread.</p>
    public static Verdict check(ServerProfile profile) {
        String mcVersion = detectMcVersion(profile);
        int requiredMajor = requiredMajor(mcVersion);
        Path javaExe = resolveJavaExecutable(profile.getJavaPath());
        String javaPath = javaExe == null ? "" : javaExe.toString();
        String actualVersion = javaExe == null ? "" : readJavaVersion(javaExe);
        int actualMajor = majorOf(actualVersion);

        // Only a Paperclip-style jar that has not cached its vanilla jar needs to download
        // anything; once `cache/mojang_<version>.jar` exists the trust store is irrelevant.
        boolean needsDownload = needsVanillaDownload(profile, mcVersion);
        boolean trustBroken = false;
        if (javaExe != null && needsDownload)
            trustBroken = isTrustStoreBroken(javaExe);

        String versionText = actualVersion.isBlank() ? "未知版本" : actualVersion;
        String mcText = mcVersion.isBlank() ? "未知" : mcVersion;

        if (javaExe == null)
            return new Verdict(Severity.BLOCKING, "找不到 Java",
                    "为服务端「" + profile.getName() + "」配置的 Java 不存在：\n"
                            + (profile.getJavaPath() == null || profile.getJavaPath().isBlank()
                                    ? "（未配置，且启动器自身的 Java 也无法定位）"
                                    : profile.getJavaPath())
                            + "\n\n请选择或下载一个可用的 Java。",
                    requiredMajor, 0, "", javaPath, mcVersion, false);

        if (actualMajor > 0 && requiredMajor > 0 && actualMajor < requiredMajor) {
            return new Verdict(Severity.BLOCKING, "Java 版本过低",
                    "服务端「" + profile.getName() + "」是 Minecraft " + mcText + "，至少需要 Java "
                            + requiredMajor + "，\n但当前配置的是 Java " + versionText
                            + "（" + javaPath + "）。\n\n"
                            + "直接启动会立刻失败，并抛出 UnsupportedClassVersionError：\n"
                            + "该服务端的 class 文件版本高于此 Java 能加载的版本。\n\n"
                            + "请安装 Java " + requiredMajor + " 后重试。",
                    requiredMajor, actualMajor, actualVersion, javaPath, mcVersion, false);
        }

        if (trustBroken) {
            return new Verdict(Severity.BLOCKING, "Java 根证书库过旧",
                    "服务端「" + profile.getName() + "」是 Paper 核心，首次启动需要从 Mojang 下载原版核心，\n"
                            + "但当前 Java " + versionText + "（" + javaPath + "）无法验证 Mojang 现在的 HTTPS 证书。\n\n"
                            + "启动时会在 “Downloading vanilla jar...” 处失败，并报错：\n"
                            + "javax.net.ssl.SSLHandshakeException: PKIX path building failed\n\n"
                            + "原因是该 Java 自带的根证书库太旧，缺少签发 Mojang 证书所需的根证书。\n"
                            + "注意：Mojang 官方提供的 Java 8（jre-legacy，1.8.0_51）正是这种旧版本，\n"
                            + "所以必须换成较新的 Java 8 构建（如 Azul Zulu 8u4xx），而不是官方的那个。\n\n"
                            + "建议安装 Java 8 的最新构建，或改用 Java 17/21（本服务端同样可以运行）。",
                    requiredMajor, actualMajor, actualVersion, javaPath, mcVersion, true);
        }

        if (actualMajor >= 16 && requiredMajor > 0 && requiredMajor <= 8) {
            return new Verdict(Severity.WARNING, "Java 版本过高",
                    "服务端「" + profile.getName() + "」是较老的 Minecraft " + mcText + "，\n"
                            + "推荐使用 Java 8，但当前配置的是 Java " + versionText
                            + "（" + javaPath + "）。\n\n"
                            + "服务端本体通常仍能启动，但部分老插件、NMS 调用和反射逻辑\n"
                            + "在高版本 Java 上会失败（常见报错：NoSuchMethodError、\n"
                            + "InaccessibleObjectException、Unsupported class file major version）。\n\n"
                            + "如果启动后出现插件报错，建议改用 Java 8。",
                    requiredMajor, actualMajor, actualVersion, javaPath, mcVersion, false);
        }

        return new Verdict(Severity.OK, "Java 版本正常",
                "服务端「" + profile.getName() + "」（Minecraft " + mcText + "）\n"
                        + "使用 Java " + versionText + "（" + javaPath + "），配置正常。",
                requiredMajor, actualMajor, actualVersion, javaPath, mcVersion, false);
    }

    // ------------------------------------------------------------ versions ---

    /// Lowest Java major version required by a Minecraft version, or 0 when unknown.
    /// Delegates to the launcher's own table so the plugin cannot drift from HMCL.
    public static int requiredMajor(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank())
            return 0;
        try {
            GameJavaVersion minimum = GameJavaVersion.getMinimumJavaVersion(
                    GameVersionNumber.asGameVersion(mcVersion));
            if (minimum != null)
                return minimum.majorVersion();
            // Older than 1.13: no entry in the table, Java 8 is the practical minimum.
            return 8;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /// Reads the Minecraft version of a server, preferring the profile's recorded value and
    /// falling back to the server jar itself (`patch.properties` for Paperclip, `version.json`
    /// for modern vanilla).
    public static String detectMcVersion(ServerProfile profile) {
        String recorded = profile.getMcVersion();
        if (recorded != null && !recorded.isBlank())
            return recorded.trim();

        Path jar = profile.getJarPath();
        if (!Files.isRegularFile(jar))
            return "";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var patch = zip.getEntry("patch.properties");
            if (patch != null) {
                String text = readEntry(zip, patch);
                Matcher matcher = PAPERCLIP_VERSION.matcher(text);
                if (matcher.find())
                    return matcher.group(1).trim();
            }
            var versionJson = zip.getEntry("version.json");
            if (versionJson != null) {
                String text = readEntry(zip, versionJson);
                Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (matcher.find())
                    return matcher.group(1).trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /// Parses the major version out of a version string: `1.8.0_472` becomes 8, `21.0.3` becomes 21.
    public static int majorOf(String version) {
        if (version == null || version.isBlank())
            return 0;
        String cleaned = version.trim();
        // strip a leading non-numeric prefix such as `jdk-` or `zulu`
        int start = 0;
        while (start < cleaned.length() && !Character.isDigit(cleaned.charAt(start)))
            start++;
        if (start > 0)
            cleaned = cleaned.substring(start);
        String[] parts = cleaned.split("[.\\-_+]");
        try {
            if (parts.length >= 2 && "1".equals(parts[0]))
                return Integer.parseInt(parts[1]);
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- java ---

    /// Resolves a configured java home / executable to the launcher binary, mirroring what
    /// [ServerProcess] will actually execute. An empty value means the launcher's own Java.
    public static @Nullable Path resolveJavaExecutable(@Nullable String configured) {
        String candidate = configured == null || configured.isBlank()
                ? System.getProperty("java.home")
                : configured.trim();
        if (candidate == null || candidate.isBlank())
            return null;
        Path path;
        try {
            path = Path.of(candidate);
        } catch (RuntimeException ignored) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String executable = windows ? "java.exe" : "java";
        if (Files.isDirectory(path)) {
            Path resolved = path.resolve("bin").resolve(executable);
            return Files.isRegularFile(resolved) ? resolved : null;
        }
        return Files.isRegularFile(path) ? path : null;
    }

    /// Reads `JAVA_VERSION` from the runtime's `release` file; empty when unavailable.
    public static String readJavaVersion(Path javaExecutable) {
        Path home = javaHomeOf(javaExecutable);
        if (home == null)
            return "";
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release))
            return "";
        try {
            for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                Matcher matcher = JAVA_VERSION.matcher(line);
                if (matcher.find())
                    return matcher.group(1);
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /// The `java.home` a given `bin/java` executable belongs to.
    public static @Nullable Path javaHomeOf(Path javaExecutable) {
        Path bin = javaExecutable.getParent();
        return bin == null ? null : bin.getParent();
    }

    // --------------------------------------------------------------- trust ---

    /// True only when the runtime's certificate store is *proven* unable to validate Mojang's
    /// certificate chain. Unknown results (offline, timeout) return false.
    public static boolean isTrustStoreBroken(Path javaExecutable) {
        Path home = javaHomeOf(javaExecutable);
        if (home == null)
            return false;
        Path cacerts = findCacerts(home);
        if (cacerts == null)
            return false;

        String cacheKey;
        try {
            cacheKey = cacerts.toAbsolutePath() + "|" + Files.getLastModifiedTime(cacerts).toMillis();
        } catch (IOException e) {
            cacheKey = cacerts.toAbsolutePath().toString();
        }
        Trust cached = TRUST_CACHE.get(cacheKey);
        if (cached != null)
            return cached == Trust.UNTRUSTED;

        Trust trust = probeTrust(cacerts);
        TRUST_CACHE.put(cacheKey, trust);
        return trust == Trust.UNTRUSTED;
    }

    /// Handshakes with Mojang using the given store as the only source of trust anchors.
    private static Trust probeTrust(Path cacerts) {
        KeyStore store = loadKeyStore(cacerts);
        if (store == null)
            return Trust.UNKNOWN;

        SSLContext context;
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
        } catch (Exception e) {
            return Trust.UNKNOWN;
        }

        boolean anySucceeded = false;
        for (String host : PROBE_HOSTS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, 443), PROBE_TIMEOUT_MS);
                try (SSLSocket tls = (SSLSocket) context.getSocketFactory()
                        .createSocket(socket, host, 443, true)) {
                    tls.setSoTimeout(PROBE_TIMEOUT_MS);
                    tls.startHandshake();
                    anySucceeded = true;
                }
            } catch (Exception e) {
                if (isCertificatePathFailure(e))
                    return Trust.UNTRUSTED;
                // Network-level failure: this host proves nothing, try the next one.
            }
        }
        return anySucceeded ? Trust.TRUSTED : Trust.UNKNOWN;
    }

    /// True when the exception chain contains a certificate-path / validation failure,
    /// as opposed to a connection or protocol problem.
    private static boolean isCertificatePathFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof CertPathBuilderException || t instanceof CertificateException)
                return true;
            String name = t.getClass().getName();
            if (name.contains("ValidatorException") || name.contains("SunCertPathBuilderException"))
                return true;
            if (t.getMessage() != null && t.getMessage().contains("PKIX"))
                return true;
            if (t == t.getCause())
                break;
        }
        return false;
    }

    /// Locates the `cacerts` of a runtime; Java 8 keeps it under `jre/`.
    private static @Nullable Path findCacerts(Path javaHome) {
        Path direct = javaHome.resolve("lib").resolve("security").resolve("cacerts");
        if (Files.isRegularFile(direct))
            return direct;
        Path legacy = javaHome.resolve("jre").resolve("lib").resolve("security").resolve("cacerts");
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    /// Loads a trust store, trying the usual password and both container formats.
    private static @Nullable KeyStore loadKeyStore(Path cacerts) {
        for (String type : new String[]{"JKS", "PKCS12"}) {
            for (char[] password : new char[][]{"changeit".toCharArray(), new char[0]}) {
                try (InputStream in = Files.newInputStream(cacerts)) {
                    KeyStore store = KeyStore.getInstance(type);
                    store.load(in, password);
                    int count = 0;
                    Enumeration<String> aliases = store.aliases();
                    while (aliases.hasMoreElements()) {
                        aliases.nextElement();
                        count++;
                    }
                    if (count > 0)
                        return store;
                } catch (Exception ignored) {
                    // try the next combination
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------- installed ---

    /// Already-installed runtimes that could run this server: the major version is high enough
    /// and the certificate store is not provably broken.
    ///
    /// <p>Probing every candidate costs a handshake each, so run this off the UI thread.</p>
    public static List<JavaScanner.JavaInfo> findUsableJavas(ServerProfile profile, Path dataDirectory) {
        int required = requiredMajor(detectMcVersion(profile));
        List<JavaScanner.JavaInfo> result = new ArrayList<>();
        for (JavaScanner.JavaInfo info : JavaScanner.scan(dataDirectory)) {
            int major = majorOf(info.getVersion());
            if (required > 0 && major > 0 && major < required)
                continue;
            Path executable = resolveJavaExecutable(info.getPath());
            if (executable == null)
                continue;
            if (isTrustStoreBroken(executable))
                continue;
            result.add(info);
        }
        return result;
    }

    // -------------------------------------------------------------- helpers ---

    /// True when starting this server will make the core download something from Mojang:
    /// a Paperclip jar whose cached vanilla jar is absent.
    private static boolean needsVanillaDownload(ServerProfile profile, String mcVersion) {
        Path jar = profile.getJarPath();
        if (!Files.isRegularFile(jar))
            return false;
        if (!isPaperclipJar(jar))
            return false;
        if (mcVersion == null || mcVersion.isBlank())
            return true;
        Path cached = Path.of(profile.getDirectory(), "cache", "mojang_" + mcVersion + ".jar");
        return !Files.isRegularFile(cached);
    }

    /// A Paperclip jar carries `patch.properties` plus the Paperclip launcher classes.
    public static boolean isPaperclipJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry("patch.properties") != null
                    && zip.getEntry("io/papermc/paperclip/Paperclip.class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readEntry(ZipFile zip, java.util.zip.ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
