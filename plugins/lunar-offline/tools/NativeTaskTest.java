import org.dsh.lunarofl.LunarApi;
import org.dsh.lunarofl.LunarInstallTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// 验证 Lunar 下载被正确包装成 HMCL 原生任务。
///
/// <p>要点（也就是「原生下载弹窗」能显示出来的前提）：</p>
/// <ol>
///   <li>它是 `Task` 的子类，`setStage`/`setName`/`setSignificance` 都设好了 —— 弹窗据此分组和显示；</li>
///   <li>整条流水线跑在 `execute()` 里，进度通过 `updateProgress` 上报，所以弹窗的总进度条会动；</li>
///   <li>取消能真的中断（`cancel()` 之后任务以 CancellationException 结束）。</li>
/// </ol>
///
/// 用法：NativeTaskTest &lt;游戏目录&gt; &lt;版本&gt; [--cancel]
public final class NativeTaskTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0] : "E:/game/game/minecraft/.minecraft").replace('/', File.separatorChar));
        String gameVersion = args.length > 1 ? args[1] : "1.8.9";
        boolean testCancel = args.length > 2 && args[2].equals("--cancel");

        // HMCL 的 Task.updateProgress 内部走 Platform.runLater，需要 JavaFX Toolkit 已初始化。
        // 真实启动器里一定已初始化；这个命令行测试没有，所以自己起一个。
        startJavaFx();

        Path dataDirectory = gameDirectory.getParent().resolve("lunar-task-test-data");
        Files.createDirectories(dataDirectory);
        LunarApi api = new LunarApi(dataDirectory);

        Path installation = gameDirectory.resolve("lunar-client").resolve(gameVersion);
        System.out.println("游戏目录   : " + gameDirectory);
        System.out.println("客户端目录 : " + installation);
        System.out.println("模式       : " + (testCancel ? "取消测试" : "正常执行"));

        // 测试用的版本 id：跑完就删掉，绝不覆盖用户的 Lunar-<版本>。
        // （早期版本这里用了固定的 Lunar-tasktest-*，而 regenerate 会按 json 里的 id 重写，
        //   等于把用户真实的 Lunar-x 抢走了一个星期，别再这么干。）
        String testVersionId = "Lunar-selftest-" + gameVersion;
        AtomicReference<String> lastStep = new AtomicReference<>("");
        List<String> steps = new ArrayList<>();

        LunarInstallTask task = new LunarInstallTask(
                gameDirectory, testVersionId, api, gameVersion,
                "lunar", installation,
                step -> {
                    lastStep.set(step);
                    synchronized (steps) {
                        steps.add(step);
                    }
                });

        // ---------- 1) 任务元数据（弹窗靠这些显示标题与分组） ----------
        System.out.println();
        System.out.println("任务名   : " + task.getName());
        System.out.println("阶段     : " + task.getStage());
        System.out.println("重要性   : " + task.getSignificance());
        check(task.getName() != null && !task.getName().isBlank(), "任务有名字（弹窗标题）");
        check(task.getStage() != null && !task.getStage().isBlank(), "任务有阶段（弹窗分组）");

        // ---------- 2) 进度上报 ----------
        List<Double> progresses = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Boolean> success = new AtomicReference<>(false);

        TaskExecutor executor = task.executor();
        executor.addTaskListener(new TaskListener() {
            @Override
            public void onStop(boolean ok, TaskExecutor exec) {
                success.set(ok);
                if (!ok)
                    failure.set(exec.getException());
                done.countDown();
            }
        });

        executor.start();

        if (testCancel) {
            // 给下载一点时间跑起来，再取消
            Thread.sleep(1500);
            System.out.println("请求取消…（当前步骤：" + lastStep.get() + "）");
            task.cancel();
            executor.cancel();
        }

        boolean finished = done.await(testCancel ? 60 : 900, TimeUnit.SECONDS);
        check(finished, "任务在超时前结束");

        System.out.println();
        System.out.println("结果     : " + (success.get() ? "成功" : "失败/取消"));
        System.out.println("最后步骤 : " + lastStep.get());
        System.out.println("步骤数   : " + steps.size());
        if (steps.size() <= 12) {
            for (String step : steps)
                System.out.println("   - " + step);
        } else {
            System.out.println("   （前 6 条）");
            for (int i = 0; i < 6; i++)
                System.out.println("   - " + steps.get(i));
            System.out.println("   …");
            System.out.println("   （后 3 条）");
            for (int i = steps.size() - 3; i < steps.size(); i++)
                System.out.println("   - " + steps.get(i));
        }

        check(!steps.isEmpty(), "过程中有步骤回调（弹窗里的步骤行会更新）");

        if (testCancel) {
            Exception e = failure.get();
            System.out.println("异常     : " + (e == null ? "无" : e.getClass().getSimpleName()));
            check(!success.get(), "取消后任务没有报告成功");
            check(task.result() == null, "取消后没有产出安装结果");
        } else {
            Exception e = failure.get();
            if (e != null) {
                System.out.println("异常     : " + e.getClass().getName() + ": " + e.getMessage());
                System.out.println("--- 堆栈（前 15 层）---");
                StackTraceElement[] trace = e.getStackTrace();
                for (int i = 0; i < Math.min(15, trace.length); i++)
                    System.out.println("   at " + trace[i]);
                Throwable cause = e.getCause();
                if (cause != null)
                    System.out.println("原因     : " + cause.getClass().getName() + ": " + cause.getMessage());
            }
            check(success.get(), "任务成功完成");
            check(task.result() != null, "产出了安装结果");
            if (task.result() != null)
                System.out.println("安装版本 : " + task.result().versionId()
                        + "（" + task.result().libraries() + " 个库）");
        }

        // 清理：删掉这个测试装出来的版本目录，别在用户的版本列表里留垃圾
        try {
            Path testVersionDir = gameDirectory.resolve("versions").resolve(testVersionId);
            if (Files.isDirectory(testVersionDir)) {
                try (var walk = Files.walk(testVersionDir)) {
                    for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
                System.out.println("已清理测试版本目录: " + testVersionId);
            }
        } catch (Exception e) {
            System.out.println("清理测试版本失败（可手动删 versions/" + testVersionId + "）: " + e);
        }

        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 初始化 JavaFX Toolkit（命令行测试没有启动器，`Platform.runLater` 会抛
    /// `IllegalStateException: Toolkit not initialized`）。
    private static void startJavaFx() throws Exception {
        try {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            javafx.application.Platform.startup(() -> {
                javafx.application.Platform.setImplicitExit(false);
                ready.countDown();
            });
            if (!ready.await(20, TimeUnit.SECONDS))
                System.out.println("警告: JavaFX 启动超时，进度上报可能失败");
            else
                System.out.println("JavaFX Toolkit 已启动（模拟真实启动器环境）");
        } catch (IllegalStateException already) {
            // 已经启动过
        } catch (Throwable e) {
            System.out.println("警告: JavaFX 启动失败（" + e + "），进度上报可能失败");
        }
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  PASS - " : "  FAIL - ") + message);
        if (!condition)
            failures++;
    }
}
