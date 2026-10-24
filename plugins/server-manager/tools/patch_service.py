# -*- coding: utf-8 -*-
# Patch server-manager: resolveVanilla + core repair on import + data dir getter.
import io

BASE = r"E:\game\gamea\mcl\HMCL\docs\plugin\server-manager\src\org\dsh\servermanager"

# ---- ServerVersions.resolveVanilla ----
p = BASE + r"\ServerVersions.java"
s = io.open(p, encoding="utf-8").read()
anchor = "    /// Downloads the vanilla server jar for the given version into `target`."
assert anchor in s
helper = '''    /// Finds a version entry by id in the vanilla manifest.
    public static VersionEntry resolveVanilla(String versionId) throws IOException {
        for (VersionEntry entry : fetchVanillaVersions())
            if (entry.getId().equals(versionId))
                return entry;
        throw new IOException("\\u7248\\u672c\\u6e05\\u5355\\u4e2d\\u6ca1\\u6709 " + versionId);
    }

'''
s = s.replace(anchor, helper + anchor, 1)
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("ServerVersions patched")

# ---- ServerManagerService: CoreRepair + importLocal + getDataDirectory ----
p = BASE + r"\ServerManagerService.java"
s = io.open(p, encoding="utf-8").read()

s = s.replace('''    private final Path serversRoot;''',
'''    /// What to do with the server core when importing an existing directory.
    public enum CoreRepair {
        /// keep everything as-is (missing jar is an error)
        NONE,
        /// download / overwrite a vanilla core
        VANILLA,
        /// download / overwrite a Paper core
        PAPER
    }

    private final Path serversRoot;''')

s = s.replace('''    public Path getServersRoot() {
        return serversRoot;
    }''',
'''    public Path getServersRoot() {
        return serversRoot;
    }

    public Path getDataDirectory() {
        return serversRoot.getParent();
    }''')

old = '''    /// Registers an existing server directory; the jar must already be present.
    public ServerProfile importLocal(String name, Path directory, String jarFile,
                                     String mcVersion, int port, String motd,
                                     int minMemory, int maxMemory, String javaPath,
                                     boolean eula, boolean broadcast) throws IOException {
        if (!Files.isRegularFile(directory.resolve(jarFile)))
            throw new IOException("\\u76ee\\u5f55\\u91cc\\u627e\\u4e0d\\u5230 " + jarFile);

        ServerProfile profile = newProfile(name, ServerProfile.Type.CUSTOM, mcVersion,
                port, motd, minMemory, maxMemory, javaPath, eula, broadcast);
        profile.setDirectory(directory.toAbsolutePath().toString());
        profile.setJarFile(jarFile);
        return finishCreate(profile);
    }'''
new = '''    /// Registers an existing server directory. With [CoreRepair#VANILLA] or
    /// [CoreRepair#PAPER] the server core is downloaded into the directory (fresh
    /// install when missing, overwrite-repair when present); `mcVersion` is
    /// required for a download.
    public ServerProfile importLocal(String name, Path directory, String jarFile,
                                     String mcVersion, int port, String motd,
                                     int minMemory, int maxMemory, String javaPath,
                                     boolean eula, boolean broadcast,
                                     CoreRepair coreRepair,
                                     ServerVersions.Progress progress) throws IOException {
        boolean hasJar = Files.isRegularFile(directory.resolve(jarFile));
        if (!hasJar && coreRepair == CoreRepair.NONE)
            throw new IOException("\\u76ee\\u5f55\\u91cc\\u627e\\u4e0d\\u5230 " + jarFile
                    + "\\uff1b\\u53ef\\u9009\\u62e9\\u201c\\u6838\\u5fc3\\u4fee\\u590d\\u201d\\u81ea\\u52a8\\u4e0b\\u8f7d\\uff0c\\u6216\\u9009\\u62e9\\u6b63\\u786e\\u7684\\u542f\\u52a8 jar");
        if (coreRepair != CoreRepair.NONE && (mcVersion == null || mcVersion.isBlank()))
            throw new IOException("\\u81ea\\u52a8\\u4e0b\\u8f7d\\u6838\\u5fc3\\u9700\\u8981\\u5148\\u586b\\u5199\\u6e38\\u620f\\u7248\\u672c\\u53f7");

        ServerProfile profile = newProfile(name, ServerProfile.Type.CUSTOM, mcVersion,
                port, motd, minMemory, maxMemory, javaPath, eula, broadcast);
        profile.setDirectory(directory.toAbsolutePath().toString());
        profile.setJarFile(jarFile);

        if (coreRepair == CoreRepair.PAPER) {
            ServerVersions.downloadPaperServer(mcVersion, profile.getJarPath(), progress);
        } else if (coreRepair == CoreRepair.VANILLA) {
            ServerVersions.VersionEntry entry = ServerVersions.resolveVanilla(mcVersion);
            ServerVersions.downloadVanillaServer(mcVersion, entry.getUrl(), profile.getJarPath(), progress);
        }

        return finishCreate(profile);
    }'''
assert old in s, "importLocal not found"
s = s.replace(old, new)
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("ServerManagerService patched")
