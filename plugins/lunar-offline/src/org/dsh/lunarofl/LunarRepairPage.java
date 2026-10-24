package org.dsh.lunarofl;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.logging.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/// 「Lunar 启动修复」页面：显示上次启动失败的原因与插件已做的修复，列出已知故障，
/// 并提供「立即全面修复」。
///
/// <p>插件本来就会在启动前与启动后自动修（[LunarRepair]），这个页面是给用户看清楚
/// 「刚才为什么没起来、已经修了什么、还要不要重装」的地方。</p>
public final class LunarRepairPage extends VBox {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Label statusLabel = new Label(" ");
    private final VBox reportBox = new VBox(4);
    private final Button repairButton;

    public LunarRepairPage() {
        setSpacing(10);
        setPadding(new Insets(12));

        Label title = new Label("Lunar 启动修复");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");
        Label subtitle = new Label("插件会在启动前体检、启动失败后自动诊断并修复 Lunar 版本："
                + "Lunar 自己声明的运行库（versions.conf 的 dependencies）会按正确顺序装进 libraries/，"
                + "--ichorExternalFiles 归一成文件名，libraries 里的 classpath 副本与客户端目录对齐，"
                + "Java 要求与 GC 参数按启动器要求处理。这个页面显示最近一次结果，也可以手动触发全面修复。");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(Double.MAX_VALUE);

        repairButton = FXUtils.newRaisedButton("立即全面修复");
        repairButton.setOnAction(e -> repairAll());
        Button reloadButton = FXUtils.newRaisedButton("刷新");
        reloadButton.setOnAction(e -> reload());

        HBox actions = new HBox(10, repairButton, reloadButton, statusLabel);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, title, subtitle, actions, section("最近一次结果", reportBox),
                section("插件能修的已知故障", symptomBox()));
        content.setPadding(new Insets(4));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(scroll);

        reload();
    }

    /// 一个带标题的分区。
    private static VBox section(String heading, javafx.scene.Node body) {
        Label label = new Label(heading);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");
        VBox box = new VBox(6, label, body);
        box.setPadding(new Insets(8, 0, 0, 0));
        return box;
    }

    /// 「已知故障」列表：每条症状的标题 + 建议。
    private static VBox symptomBox() {
        VBox box = new VBox(6);
        for (LunarRepair.Symptom symptom : LunarRepair.symptoms()) {
            Label name = new Label("· " + symptom.title());
            name.setStyle("-fx-font-size: 13px; -fx-text-fill: -monet-on-surface;");
            Label advice = new Label("   " + symptom.advice());
            advice.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
            advice.setWrapText(true);
            advice.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().addAll(name, advice);
        }
        return box;
    }

    /// 读一次数据目录里的记录并刷新界面。
    private void reload() {
        LunarRepair.Report report = LunarRepair.last();
        reportBox.getChildren().clear();
        if (report == null) {
            reportBox.getChildren().add(hint("还没有失败记录。启动一次 Lunar 版本，出问题时这里会有内容。"));
        } else {
            reportBox.getChildren().add(line("版本", report.versionId()));
            reportBox.getChildren().add(line("时间", TIME_FORMAT.format(Instant.ofEpochMilli(report.time()))));
            reportBox.getChildren().add(line("诊断", report.title()));
            if (report.symptom() != null)
                reportBox.getChildren().add(line("症状", report.symptom()));
            if (!report.advice().isBlank())
                reportBox.getChildren().add(hint(report.advice()));
            if (!report.problems().isEmpty())
                reportBox.getChildren().add(list("体检发现", report.problems()));
            reportBox.getChildren().add(report.actions().isEmpty()
                    ? hint("这次没有可自动修复的项目。")
                    : list("已做的修复", report.actions()));
        }
        statusLabel.setText(" ");
    }

    /// 后台跑一次全面修复（会联网补运行库，别卡住 UI 线程）。
    private void repairAll() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            statusLabel.setText("没有选中游戏目录");
            return;
        }
        Path gameDirectory = repository.getBaseDirectory();
        repairButton.setDisable(true);
        statusLabel.setText("正在体检并修复…");
        Thread worker = new Thread(() -> {
            String message;
            try {
                List<LunarRepair.Report> reports = LunarRepair.repairAll(gameDirectory);
                message = reports.isEmpty()
                        ? "全部 Lunar 版本体检通过，没有需要修的。"
                        : "已修复 " + reports.size() + " 个版本：" + reports.get(0).summary();
            } catch (Throwable t) {
                Logger.LOG.warning("全面修复失败：" + t);
                message = "修复失败：" + t.getMessage();
            }
            String finalMessage = message;
            Platform.runLater(() -> {
                statusLabel.setText(finalMessage);
                repairButton.setDisable(false);
                reload();
            });
        }, "lunar-repair-all");
        worker.setDaemon(true);
        worker.start();
    }

    private static Label line(String key, String value) {
        Label label = new Label(key + "：" + value);
        label.setStyle("-fx-font-size: 13px; -fx-text-fill: -monet-on-surface;");
        label.setWrapText(true);
        return label;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static VBox list(String heading, List<String> items) {
        VBox box = new VBox(2);
        box.getChildren().add(hint(heading + "："));
        for (String item : items)
            box.getChildren().add(hint("   · " + item));
        return box;
    }
}
