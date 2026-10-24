package org.dsh.lunarofl;

import org.jackhuang.hmcl.task.Task;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// 把「下载 Lunar 客户端 + 安装成启动器版本」包装成 HMCL 的原生 [Task]。
///
/// <p>以前这一步是插件自己起一个线程、在页面里画一条孤立的进度条。现在改成原生任务：</p>
/// <ul>
///   <li>出现启动器标准的下载弹窗（[org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane]，
///       即原版游戏下载时那个窗口），带标题、总进度条、每个子步骤一行、以及「取消」按钮；</li>
///   <li>进度上报走 `updateProgress(count, total)`，所以总进度条是真实字节进度；</li>
///   <li>取消按钮真的能中断下载（[cancelled] 会被传到下载器里）。</li>
/// </ul>
///
/// <p>任务本身包住整条流水线：拉清单 → 下载客户端文件 → 解压 → 下载贴图/UI → 补齐运行库 →
/// 写版本 json。每一步都会 `setStage`，于是弹窗里能看到「正在做什么」。</p>
@NotNullByDefault
public final class LunarInstallTask extends Task<Void> {

    private final Path gameDirectory;
    private final String versionId;
    private final LunarApi api;
    private final String gameVersion;
    private final String moduleId;
    private final Path installation;

    /// 取消标志：原生弹窗的「取消」按钮会把它置 true，下载循环里逐块检查。
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /// 下载完成后的结果（安装完成后填好，供调用方读取）。
    private final AtomicReference<LunarVersionInstaller.@Nullable Result> installed = new AtomicReference<>();

    /// 每一步都要经 `updateProgress`，而它要求单调递增的 `count/total`；
    /// 这里用一个全局已处理字节数当 count，总字节数在拉到清单后才确定。
    private final AtomicLong doneBytes = new AtomicLong();
    private volatile long totalBytes = -1;

    /// 把下载速度汇报给启动器原生弹窗左下角的速度显示。
    private final LunarSpeedMeter speedMeter = new LunarSpeedMeter();

    /// 步骤回调（给页面同步刷新状态行用，可为 null）。
    private final @Nullable Consumer<String> stepListener;

    /// 当前阶段名（中文直接作为 stage：HMCL 对未知 stage key 会原样显示并记 ERROR 日志，
    /// 所以这里不编造 i18n key）。
    private static final String STAGE = "Lunar 客户端";

    public LunarInstallTask(Path gameDirectory, String versionId, LunarApi api,
                            String gameVersion, String moduleId, Path installation,
                            @Nullable Consumer<String> stepListener) {
        this.gameDirectory = gameDirectory;
        this.versionId = versionId;
        this.api = api;
        this.gameVersion = gameVersion;
        this.moduleId = moduleId;
        this.installation = installation;
        this.stepListener = stepListener;

        setStage(STAGE);
        setName("正在下载 Lunar 客户端");
        setSignificance(Task.TaskSignificance.MAJOR);
    }

    /// 安装结果（任务成功结束后可读）。
    public @Nullable LunarVersionInstaller.Result result() {
        return installed.get();
    }

    /// 请求取消（原生弹窗的取消按钮会调到这里）。
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void execute() throws Exception {
        try {
            LunarClientDownloader.Installed downloaded = LunarClientDownloader.download(
                    installation, api, gameVersion, moduleId,
                    this::onProgress,
                    cancelled::get);

            if (cancelled.get())
                throw new java.util.concurrent.CancellationException("用户取消了下载");

            step("正在安装到启动器");
            // 安装阶段不再下载：把速度归零，否则界面会一直停在最后一个下载速度上
            speedMeter.finish();
            // 安装阶段没有已知总量：用一个大数当分母，把已复制字节数映射成小幅推进，
            // 避免进度条在安装时突然从 100% 掉回 0。
            LunarVersionInstaller.Result result = LunarVersionInstaller.install(
                    gameDirectory, versionId, downloaded.launchData(),
                    (step, bytes) -> onInstallProgress(step, bytes));
            installed.set(result);

            step("已安装版本「" + result.versionId() + "」");
            updateProgress(1.0);
        } finally {
            // 成功、失败、取消都要归零：左下角的速度不该停在某个非零值上不再变化
            speedMeter.finish();
        }
    }

    /// 下载阶段的进度：`done/total` 直接映射到原生总进度条，同时把速度喂给启动器的速度显示。
    private void onProgress(String stepName, long done, long total) {
        step(stepName);
        if (total > 0)
            totalBytes = total;
        doneBytes.set(done);
        // 原生弹窗左下角的 `x B/s` 只认 FetchTask 内部计数器；我们的下载不经过它，
        // 不主动上报就永远显示 0 B/s。
        speedMeter.accept(done);
        reportProgress();
    }

    /// 安装（复制 jar）阶段：总量未知，用「已复制字节数 / 一个足够大的估计值」让进度条缓慢前进，
    /// 同时把阶段名切到具体动作，用户能看出在干什么。
    private void onInstallProgress(String stepName, long bytes) {
        step(stepName);
        // 安装阶段保留 10% 的进度空间（90% → 100%），按经验 200 MB 封顶
        double fraction = Math.min(0.99, bytes / (200.0 * 1024 * 1024));
        updateProgressImmediately(0.9 + 0.1 * fraction);
    }

    private void reportProgress() {
        long total = totalBytes;
        long done = doneBytes.get();
        if (total <= 0) {
            updateProgressImmediately(0.0);
            return;
        }
        // 下载占整个进度的 90%，剩下 10% 留给安装
        double fraction = Math.min(1.0, (double) done / total);
        updateProgressImmediately(0.9 * fraction);
    }

    /// 切换阶段名并同步给页面（原生弹窗里的任务名由 Task 自己维护，这里只更新页面状态行）。
    private void step(String name) {
        Consumer<String> listener = stepListener;
        if (listener != null) {
            try {
                listener.accept(name);
            } catch (Throwable ignored) {
                // 页面已关闭之类：进度照常走，不要因为刷新界面失败影响下载
            }
        }
    }
}
