package org.dsh.servermanager;

import javafx.scene.Node;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginDownloadTab;
import org.jackhuang.hmcl.plugin.PluginSubPage;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.io.File;
import java.io.IOException;

/// The server manager plugin: manages multiple Minecraft server instances in the
/// launcher, starts them with one click and broadcasts them on the LAN.
///
/// <p>It contributes two entries: the management page in the game section of the main
/// window sidebar, and the download wizard as a tab of the launcher's download page.</p>
public final class ServerManagerPlugin extends HmclPlugin {

    private static final String PAGE_TITLE = "服务端管理";
    private static final String TAB_TITLE = "服务端";

    private static ServerManagerService service;
    private static PluginContext pluginContext;
    private static PluginSubPage subPage;
    private static PluginDownloadTab downloadTab;

    @Override
    public void onEnable() {
        pluginContext = getContext();
        service = ServerManagerService.of(getContext().getDataDirectory());
        service.adoptRunningServers(); // pick up servers still running from the previous session
        getContext().logInfo("服务端管理器已启用，数据目录：" + getContext().getDataDirectory());


        downloadTab = new PluginDownloadTab() {
            @Override
            public String getTitle() {
                return TAB_TITLE;
            }

            @Override
            public SVG getIcon() {
                return SVG.DOWNLOAD;
            }

            @Override
            public Node createPage() {
                return new ServerDownloadPage(service);
            }
        };

        // 发布为“子页面”：没有汇聚页（如「服务器」模块）时，启动器会直接把它显示为
        // 主页面侧边栏入口；安装了汇聚页后则收进汇聚页内。
        subPage = new PluginSubPage("org.dsh.server-manager", "服务端管理",
                "开设、配置与广播你自己的服务端：原版 / Paper 下载、导入现有服务端、控制台与联机房间",
                org.jackhuang.hmcl.ui.SVG.PUBLIC,
                back -> new ServerManagerPage(service, back));
        PluginMenuService.registerSubPage(subPage);
        PluginMenuService.registerDownloadTab(downloadTab);
    }

    @Override
    public void onDisable() {
        if (subPage != null) {
            PluginMenuService.unregisterSubPage(subPage);
            subPage = null;
        }
        if (downloadTab != null) {
            PluginMenuService.unregisterDownloadTab(downloadTab);
            downloadTab = null;
        }
        getContext().logInfo("服务端管理器已停用（运行中的服务端不受影响）");
    }

    // ------------------------------------------------- shared UI helpers ---

    static PluginDownloadTab downloadTab() {
        return downloadTab;
    }

    /// The plugin context, used by the Java fix dialog for logging and dialogs.
    static PluginContext context() {
        return pluginContext;
    }

    /// Opens a URL in the system browser.
    static void openLink(String url) {
        PluginContext context = pluginContext;
        if (context == null || url == null || url.isBlank())
            return;
        try {
            context.openLink(url);
        } catch (Exception e) {
            context.logError("打开链接失败：" + url, e);
        }
    }

    /// Navigates to the management page in the main window sidebar.
    static void showServerManager() {
        PluginMenuService.showSubPage("org.dsh.server-manager");
    }

    /// Writes an action to the launcher log. The page's console is only visible inside the
    /// page, so without this a refused or failed start leaves no trace anywhere.
    public static void log(String message) {
        PluginContext context = pluginContext;
        if (context != null)
            context.logInfo(message);
    }

    public static void error(String title, String message) {
        Controllers.dialog(message == null || message.isEmpty() ? "未知错误" : message,
                title, MessageDialogPane.MessageType.ERROR);
    }

    static void info(String title, String message) {
        Controllers.dialog(message, title, MessageDialogPane.MessageType.INFO);
    }

    static void confirm(String title, String message, Runnable onConfirmed) {
        Controllers.confirm(message, title, onConfirmed, null);
    }

    static void openDirectory(File directory) {
        try {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory())
                throw new IOException("无法创建目录 " + directory);
            java.awt.Desktop.getDesktop().open(directory);
        } catch (Exception e) {
            error("打开目录失败", e.getMessage());
        }
    }
}
