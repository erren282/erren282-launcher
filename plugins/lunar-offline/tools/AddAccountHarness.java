package org.dsh.lunarofl.wrapper;

import org.dsh.lunarofl.auth.LunarGameIpcServer;

/// 用与游戏相同的路径验证设备码登录界面：起服务器（注入 Swing UI），
/// 等模拟客户端发 AddAccount，30 秒后收尾退出。
public class AddAccountHarness {
    public static void main(String[] args) throws Exception {
        LunarGameIpcServer server = new LunarGameIpcServer(
                message -> System.out.println("[server] " + message),
                "INSTALLATION-ID",
                new SwingDeviceCodeUi());
        int port = server.start();
        System.out.println("READY port=" + port);
        Thread.sleep(30_000);
        System.out.println("harness exit");
        System.exit(0);
    }
}
