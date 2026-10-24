/// Prints whether the launcher's probe sees a running Minecraft server on the given port.
public final class ProbeReal {
    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        System.out.println("port " + port
                + "  rawListener(core bridge)=" + org.jackhuang.hmcl.terracotta.TerracottaServerBridge.isPortListening(port)
                + "  isMinecraftServer(core bridge)=" + org.jackhuang.hmcl.terracotta.TerracottaServerBridge.isMinecraftServerRunning(port)
                + "  isMinecraftServer(plugin)=" + org.dsh.servermanager.ServerManagerService.isMinecraftServer(port));
    }
}
