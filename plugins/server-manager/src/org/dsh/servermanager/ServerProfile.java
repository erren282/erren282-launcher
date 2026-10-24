package org.dsh.servermanager;

/// A managed Minecraft server instance persisted to the plugin's `servers.json`.
public final class ServerProfile {

    public enum Type {
        VANILLA("原版 (Mojang)"),
        PAPER("Paper"),
        CUSTOM("导入的服务端");

        private final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private String id = "";
    private String name = "";
    private Type type = Type.VANILLA;
    private String mcVersion = "";
    private String directory = "";
    private String jarFile = "server.jar";
    private int port = 25565;
    private String motd = "A Minecraft Server";
    private int minMemory = 1024;
    private int maxMemory = 2048;
    private String javaPath = "";
    private boolean eulaAccepted = false;
    private String levelSeed = "";
    private String jvmArgs = "";
    // Boolean/Integer (boxed) on purpose: Gson skips constructors, so fields that are
    // absent in an old servers.json would otherwise read as false/0 instead of the
    // intended defaults. The getters treat null as the default.
    private Boolean broadcast;
    private Boolean terracottaRoom;
    private Boolean onlineMode;
    private Integer maxPlayers;
    private String difficulty;
    private String gamemode;
    private Integer viewDistance;
    private Integer simulationDistance;
    private Boolean pvp;
    private boolean allowFlight = false;
    private boolean whitelist = false;
    private boolean commandBlock = false;
    private long createdAt = System.currentTimeMillis();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String mcVersion) { this.mcVersion = mcVersion; }

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }

    public String getJarFile() { return jarFile; }
    public void setJarFile(String jarFile) { this.jarFile = jarFile; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getMotd() { return motd; }
    public void setMotd(String motd) { this.motd = motd; }

    public int getMinMemory() { return minMemory; }
    public void setMinMemory(int minMemory) { this.minMemory = minMemory; }

    public int getMaxMemory() { return maxMemory; }
    public void setMaxMemory(int maxMemory) { this.maxMemory = maxMemory; }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = javaPath; }

    public boolean isEulaAccepted() { return eulaAccepted; }
    public void setEulaAccepted(boolean eulaAccepted) { this.eulaAccepted = eulaAccepted; }

    public boolean isBroadcast() { return broadcast == null || broadcast; }
    public void setBroadcast(boolean broadcast) { this.broadcast = broadcast; }

    public boolean isTerracottaRoom() { return terracottaRoom == null || terracottaRoom; }
    public void setTerracottaRoom(boolean terracottaRoom) { this.terracottaRoom = terracottaRoom; }

    public boolean isOnlineMode() { return onlineMode == null || onlineMode; }
    public void setOnlineMode(boolean onlineMode) { this.onlineMode = onlineMode; }

    public int getMaxPlayers() { return maxPlayers == null ? 20 : maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public String getDifficulty() { return difficulty == null || difficulty.isBlank() ? "normal" : difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getGamemode() { return gamemode == null || gamemode.isBlank() ? "survival" : gamemode; }
    public void setGamemode(String gamemode) { this.gamemode = gamemode; }

    public int getViewDistance() { return viewDistance == null ? 10 : viewDistance; }
    public void setViewDistance(int viewDistance) { this.viewDistance = viewDistance; }

    public int getSimulationDistance() { return simulationDistance == null ? 10 : simulationDistance; }
    public void setSimulationDistance(int simulationDistance) { this.simulationDistance = simulationDistance; }

    public boolean isPvp() { return pvp == null || pvp; }
    public void setPvp(boolean pvp) { this.pvp = pvp; }

    public boolean isAllowFlight() { return allowFlight; }
    public void setAllowFlight(boolean allowFlight) { this.allowFlight = allowFlight; }

    public boolean isWhitelist() { return whitelist; }
    public void setWhitelist(boolean whitelist) { this.whitelist = whitelist; }

    public boolean isCommandBlock() { return commandBlock; }
    public void setCommandBlock(boolean commandBlock) { this.commandBlock = commandBlock; }

    public String getLevelSeed() { return levelSeed; }
    public void setLevelSeed(String levelSeed) { this.levelSeed = levelSeed; }

    public String getJvmArgs() { return jvmArgs; }
    public void setJvmArgs(String jvmArgs) { this.jvmArgs = jvmArgs; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    /// Path of the server jar inside the instance directory.
    public java.nio.file.Path getJarPath() {
        return java.nio.file.Paths.get(directory, jarFile);
    }
}
