package org.dsh.lunarofl;

import org.jackhuang.hmcl.task.FetchTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/// 把插件自己的下载速度汇报给启动器的「下载速度」显示。
///
/// <h2>为什么以前一直是 0 B/s</h2>
/// 原生下载弹窗左下角那行速度由 [org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane] 显示：
/// 它监听 [FetchTask#SPEED_EVENT]，而事件的值取自 `FetchTask` **内部**的私有计数器
/// （`downloadSpeed`）。那个计数器由一个每 1000 ms 触发一次的定时器
/// `getAndSet(0)` 读走并清零 —— 只有走 [FetchTask] 的下载才会往里累加字节。
///
/// <p>Lunar 客户端用插件自己的 `java.net.http.HttpClient` 下载，一个字节都不经过 FetchTask，
/// 于是计数器恒为 0，界面就一直是 `0 B/s`（哪怕进度条在动）。</p>
///
/// <h2>怎么汇报</h2>
/// 直接 `fireEvent` 一个瞬时值是**不对**的：定时器每秒也会发一次自己的事件，两者交错会让数字
/// 忽大忽小、甚至被清零的那次盖掉。正确的语义是「把字节增量累加到同一个计数器」，
/// 让启动器的定时器照常按秒汇总 —— 这也正是 `FetchTask` 自己的做法。
///
/// <p>计数器是私有字段，这里通过反射拿一次并缓存。拿不到时整体降级为「不上报速度」，
/// 绝不影响下载本身（速度显示只是锦上添花）。</p>
@NotNullByDefault
public final class LunarSpeedMeter {

    /// HMCL 内部那个每秒被读走并清零的字节计数器。
    private static final @Nullable AtomicLong SHARED_COUNTER = locateSharedCounter();

    private long lastBytes = Long.MIN_VALUE;
    private long lastReportTime;

    /// 反射定位 `FetchTask.downloadSpeed`。找不到就返回 null（降级）。
    private static @Nullable AtomicLong locateSharedCounter() {
        try {
            Field field = FetchTask.class.getDeclaredField("downloadSpeed");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof AtomicLong counter ? counter : null;
        } catch (Throwable ignored) {
            // 启动器换了实现/字段名：速度显示会退回 0，但下载不受影响
            return null;
        }
    }

    /// 这个运行环境里能不能上报速度（供诊断用）。
    public static boolean available() {
        return SHARED_COUNTER != null;
    }

    /// 喂入当前的累计已下载字节数；内部换算成增量并累加到启动器的计数器。
    ///
    /// <p>第一次调用只记录基准点（否则会把「从 0 到当前值」整段当成一秒的速度）。</p>
    public void accept(long cumulativeBytes) {
        AtomicLong counter = SHARED_COUNTER;
        if (counter == null)
            return;

        long now = System.currentTimeMillis();
        if (lastBytes == Long.MIN_VALUE) {
            lastBytes = cumulativeBytes;
            lastReportTime = now;
            return;
        }

        long delta = cumulativeBytes - lastBytes;
        if (delta <= 0) {
            // 没有新增字节：不累加任何东西，定时器下一拍自然显示 0
            lastBytes = cumulativeBytes;
            lastReportTime = now;
            return;
        }

        // 把增量直接累加进启动器的计数器：它的定时器会在下一拍把总和当作「本秒速度」显示。
        // 这样与 FetchTask 的语义完全一致，不会出现自己发事件与定时器事件交错的问题。
        counter.addAndGet(delta);
        lastBytes = cumulativeBytes;
        lastReportTime = now;
    }

    /// 下载结束：重置采样基准。计数器由启动器自己的定时器清空，这里不需要额外动作。
    public void finish() {
        lastBytes = Long.MIN_VALUE;
        lastReportTime = 0;
    }
}
