import org.dsh.servermanager.ServerManagerService;
import org.dsh.servermanager.ServerProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// Starts the user's real server profile with the exact backend the page button calls, and
/// reports what happens. If this works, a "cannot start" report is a UI-level no-op rather
/// than a backend failure — and the console output tells us which.
public final class RealProfileTest {

    public static void main(String[] args) throws Exception {
        Path realData = Path.of("E:/game/gamea/mcl/HMCL-test/.hmcl/plugins/data/org.dsh.server-manager");
        Path data = Files.createTempDirectory("real-profile-data");
        Files.copy(realData.resolve("servers.json"), data.resolve("servers.json"));

        ServerManagerService service = ServerManagerService.of(data);
        List<ServerProfile> profiles = service.getProfiles();
        if (profiles.isEmpty()) {
            System.out.println("FAIL - servers.json 里没有服务端");
            return;
        }
        ServerProfile profile = profiles.get(0);
        System.out.println("档案: " + profile.getName() + "  目录: " + profile.getDirectory());
        System.out.println("  jar=" + profile.getJarFile() + " 端口=" + profile.getPort()
                + " 内存=" + profile.getMinMemory() + "/" + profile.getMaxMemory()
                + " EULA=" + profile.isEulaAccepted() + " java=" + profile.getJavaPath());
        System.out.println("  jar 存在: " + Files.isRegularFile(profile.getJarPath()));

        List<String> console = new CopyOnWriteArrayList<>();
        service.addListener(new ServerManagerService.Listener() {
            @Override
            public void onLog(ServerProfile p, String line) {
                console.add(line);
            }
        });

        System.out.println("--- 调用 service.start(profile) ---");
        try {
            service.start(profile);
        } catch (Exception e) {
            System.out.println("FAIL - start 抛出: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        System.out.println("start 已返回，等待控制台出现 Done …");

        long deadline = System.currentTimeMillis() + 240_000;
        boolean done = false;
        while (System.currentTimeMillis() < deadline && !done) {
            for (String line : console)
                if (line.contains("Done (")) done = true;
            if (!service.isRunning(profile) ) break;
            Thread.sleep(1000);
        }
        System.out.println("运行中: " + service.isRunning(profile) + "  出现 Done: " + done);
        System.out.println("--- 控制台末 25 行 ---");
        int from = Math.max(0, console.size() - 25);
        for (String line : console.subList(from, console.size()))
            System.out.println(line);

        System.out.println("--- 停止服务端 ---");
        try {
            service.stop(profile);
        } catch (Exception e) {
            System.out.println("stop 抛出: " + e.getMessage());
        }
        long stopDeadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < stopDeadline && service.isRunning(profile))
            Thread.sleep(500);
        System.out.println("已停止: " + !service.isRunning(profile));
    }
}
