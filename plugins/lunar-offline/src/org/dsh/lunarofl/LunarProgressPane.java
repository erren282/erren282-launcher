package org.dsh.lunarofl;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// Lunar 下载/安装的**醒目进度条**：正在做什么 + 一条粗进度条（带百分比）+ 明细
/// （已下载 / 总量 / 速度 / 剩余时间）。
///
/// <p>以前下载页只在状态行里写一行「正在下载客户端文件：19.2 MB / 179.7 MB」小字，180 MB 的下载
/// 进度很不显眼。这个组件把它换成：</p>
///
/// <pre>
/// 正在下载客户端文件                                    42%
/// ████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░
/// 76.1 MB / 179.7 MB · 3.2 MB/s · 剩余 32 秒 · 已用 1:24
/// </pre>
///
/// <p>用量：构造后加进界面（默认隐藏），下载时反复调 [#update]，结束后调 [#complete] 或 [#fail]。</p>
@NotNullByDefault
public final class LunarProgressPane extends VBox {

    /// 明细里只保留 1 位小数。
    private static final String MB_FORMAT = "%.1f MB";

    private final Label stepLabel = new Label();
    private final Label percentLabel = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label detailLabel = new Label();

    /// 速度采样：上次的时间戳与已完成字节数（用于算瞬时速度）。
    private long lastSampleTime;
    private long lastSampleBytes;
    /// 平滑后的速度（字节/秒）；0 表示还没测出来。
    private double bytesPerSecond;
    /// 本轮开始时间（用于「已用」）。
    private long startedTime;
    /// 上一次的步骤名，步骤变化时重置计时。
    private @Nullable String lastStep;

    public LunarProgressPane() {
        setSpacing(6);
        setPadding(new Insets(10, 12, 10, 12));
        // 背景用半透明灰：不依赖 HMCL 的主题变量，浅色/深色主题下都成立
        setStyle("-fx-background-color: rgba(127,127,127,0.10); -fx-background-radius: 8;");

        stepLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");
        percentLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -monet-primary;");
        percentLabel.setMinWidth(56);
        percentLabel.setAlignment(Pos.CENTER_RIGHT);

        bar.setPrefHeight(16);
        bar.setMaxWidth(Double.MAX_VALUE);
        // 显式给进度条上色（mоdena 的 .bar 就吃 -fx-accent）。这里不用 -monet-primary：
        // 那个主题变量只在 HMCL 自己的样式表里定义，脱离主题（例如工具里的预览、或样式表没加载）
        // 时解析不了，进度条会变成一条没有填充的灰槽 —— 进度条必须永远看得见。
        bar.setStyle("-fx-accent: #3b82f6;");
        HBox.setHgrow(bar, Priority.ALWAYS);

        HBox barRow = new HBox(10, bar, percentLabel);
        barRow.setAlignment(Pos.CENTER_LEFT);

        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");
        detailLabel.setWrapText(true);

        getChildren().setAll(stepLabel, barRow, detailLabel);

        // 空闲时不占位置
        setVisible(false);
        setManaged(false);
    }

    /// 进度条本体（给自动化测试/预览用）。
    public ProgressBar progressBar() {
        return bar;
    }

    /// 明细文字（给自动化测试/预览用）。
    public String detailText() {
        return detailLabel.getText();
    }

    /// 更新进度。
    ///
    /// @param step       这一步在做什么（例如「正在下载客户端文件」）
    /// @param doneBytes  已完成字节数
    /// @param totalBytes 总字节数；`<= 0` 表示总量未知（进度条转为不确定态）
    public void update(String step, long doneBytes, long totalBytes) {
        if (!isVisible()) {
            setVisible(true);
            setManaged(true);
        }

        long now = System.currentTimeMillis();
        if (!step.equals(lastStep)) {
            // 换步骤：重新计「已用时间」。
            // 下载各阶段的 done 是同一个全局计数器（换阶段继续累加），所以采样点要保留，
            // 否则换步骤后的第一次采样会拿新字节数减旧计数器的值，速度读数就失真了；
            // 而安装阶段是另一个回调、字节数从 0 重新开始 —— 那种情况必须重置速度重新测。
            boolean counterRestarted = doneBytes < lastSampleBytes;
            lastStep = step;
            startedTime = now;
            if (counterRestarted) {
                lastSampleTime = now;
                lastSampleBytes = doneBytes;
                bytesPerSecond = 0;
            }
        }

        // 速度：间隔太短就不采样，避免抖动；用指数平滑，读数稳定些
        if (now - lastSampleTime >= 250) {
            double instant = (doneBytes - lastSampleBytes) * 1000.0 / (now - lastSampleTime);
            if (instant >= 0) {
                bytesPerSecond = bytesPerSecond <= 0 ? instant : bytesPerSecond * 0.6 + instant * 0.4;
            }
            lastSampleTime = now;
            lastSampleBytes = doneBytes;
        }

        double percent = totalBytes > 0 ? Math.min(1.0, (double) doneBytes / totalBytes) : -1;
        bar.setProgress(percent);
        percentLabel.setText(percent < 0 ? "…" : Math.round(percent * 100) + "%");
        stepLabel.setText(step);
        detailLabel.setText(detail(percent, doneBytes, totalBytes, bytesPerSecond, now - startedTime));
    }

    /// 完成：进度条拉满并写一行结果，随后由调用方决定何时 [#hideProgress]。
    public void complete(String message) {
        update(message, Math.max(1, lastSampleBytes), Math.max(1, lastSampleBytes));
        percentLabel.setText("100%");
        stepLabel.setText(message);
        detailLabel.setText(lastSampleBytes > 0 ? "已完成 · " + mb(lastSampleBytes) : "已完成");
    }

    /// 失败：保留进度位置，但标题写明失败原因。
    public void fail(String message) {
        setVisible(true);
        setManaged(true);
        stepLabel.setText(message);
        detailLabel.setText(detailLabel.getText().isEmpty() ? "已中断" : detailLabel.getText() + " · 已中断");
    }

    /// 隐藏进度区（空闲或失败弹窗之后）。
    public void hideProgress() {
        setVisible(false);
        setManaged(false);
        lastStep = null;
        bytesPerSecond = 0;
    }

    /// 明细文本：`已下载 / 总量 · 速度 · 剩余时间 · 已用时间`。
    ///
    /// <p>拆成静态方法是为了能单独验证（见 tools 里的探针），不依赖 JavaFX 运行环境。</p>
    static String detail(double percent, long doneBytes, long totalBytes, double bytesPerSecond, long elapsedMillis) {
        StringBuilder text = new StringBuilder();
        text.append(mb(doneBytes));
        if (totalBytes > 0)
            text.append(" / ").append(mb(totalBytes));
        if (bytesPerSecond > 1024)
            text.append(" · ").append(mb((long) bytesPerSecond)).append("/s");

        long remaining = 0;
        if (totalBytes > 0 && percent >= 0 && bytesPerSecond > 1024) {
            remaining = (long) ((totalBytes - doneBytes) / bytesPerSecond);
            if (remaining > 0)
                text.append(" · 剩余 ").append(duration(remaining * 1000));
        }

        if (elapsedMillis >= 1000)
            text.append(" · 已用 ").append(duration(elapsedMillis));

        return text.toString();
    }

    /// 字节 → `12.3 MB`（小于 1 MB 时用 KB）。
    static String mb(long bytes) {
        if (bytes < 1024 * 1024)
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, MB_FORMAT, bytes / 1048576.0);
    }

    /// 毫秒 → `32 秒` / `1:24` / `1:02:03`。
    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60)
            return seconds + " 秒";
        if (seconds < 3600)
            return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
        return (seconds / 3600) + ":" + String.format(Locale.ROOT, "%02d:%02d",
                (seconds % 3600) / 60, seconds % 60);
    }
}
