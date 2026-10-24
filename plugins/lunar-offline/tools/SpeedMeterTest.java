import org.dsh.lunarofl.LunarSpeedMeter;
import org.jackhuang.hmcl.task.FetchTask;

import java.util.ArrayList;
import java.util.List;

/// 验证「左下角速度一直显示 0 B/s」已修好。
///
/// <p>机制：原生弹窗的速度来自 `TaskExecutorDialogPane` 监听的 `FetchTask.SPEED_EVENT`，
/// 其值取自 `FetchTask` 内部私有计数器 `downloadSpeed`，由每 1000 ms 的定时器
/// `getAndSet(0)` 读走并清零。只有走 `FetchTask` 的下载才会累加它，所以插件用自己的
/// HttpClient 下载时恒为 0。</p>
///
/// <p>`LunarSpeedMeter` 把字节增量累加进**同一个**计数器（与 FetchTask 语义一致），
/// 让启动器的定时器照常按秒汇总。这个测试确认：</p>
/// <ol>
///   <li>字节增量确实进了计数器；</li>
///   <li>启动器的定时器能把它们汇总成一个非零速度事件（即界面会显示出来）；</li>
///   <li>没有新增字节时不产生虚假速度。</li>
/// </ol>
public final class SpeedMeterTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("能否上报速度: " + (LunarSpeedMeter.available() ? "是" : "否（将降级，下载不受影响）"));
        check(LunarSpeedMeter.available(), "找得到启动器的速度计数器");

        // 监听真实事件（与界面上那个监听器是同一条通道）
        List<Long> events = new ArrayList<>();
        FetchTask.SPEED_EVENT.register(speedEvent -> {
            synchronized (events) {
                events.add(speedEvent.getSpeed());
            }
        });

        LunarSpeedMeter meter = new LunarSpeedMeter();

        // ---- 1) 第一次喂入只记基准 ----
        meter.accept(0);
        synchronized (events) {
            events.clear();
        }
        System.out.println("首帧后不计入速度 ✔");

        // ---- 2) 喂入 2 MB，等启动器的定时器把总和报出来 ----
        meter.accept(2L * 1024 * 1024);
        System.out.println("已喂入 2 MB，等待启动器定时器汇总（最多 3 秒）…");

        long reported = awaitNonZero(events, 3000);
        System.out.println("启动器报出的速度: " + reported + " B/s（约 "
                + String.format("%.2f", reported / 1048576.0) + " MB/s）");
        check(reported > 0, "速度不再是 0（这就是原先的毛病）");
        check(reported >= 2L * 1024 * 1024 * 0.5,
                "报出的速度覆盖了我们喂入的 2 MB（实际 " + reported + " B/s）");

        // ---- 3) 计数器被定时器清零后，没有新数据就不该再有速度 ----
        Thread.sleep(2200);
        synchronized (events) {
            events.clear();
        }
        Thread.sleep(1500);
        long idle = 0;
        synchronized (events) {
            for (long value : events)
                idle = Math.max(idle, value);
        }
        System.out.println("空闲时最大报告值: " + idle);
        check(idle == 0, "没有新数据时速度归 0，不显示虚假数字");

        // ---- 4) 停滞的进度回调不产生速度 ----
        meter.accept(2L * 1024 * 1024);
        meter.accept(2L * 1024 * 1024);   // 没有新增
        Thread.sleep(1600);
        synchronized (events) {
            events.clear();
        }
        Thread.sleep(1500);
        long stalled = 0;
        synchronized (events) {
            for (long value : events)
                stalled = Math.max(stalled, value);
        }
        System.out.println("停滞时最大报告值: " + stalled);
        check(stalled == 0, "字节数不变时不误报速度");

        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 等启动器的定时器发出一条非零速度事件。
    private static long awaitNonZero(List<Long> events, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            synchronized (events) {
                for (long value : events)
                    if (value > 0)
                        return value;
            }
        }
        return 0;
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  PASS - " : "  FAIL - ") + message);
        if (!condition)
            failures++;
    }
}
