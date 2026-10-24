package org.dsh.serverlist;

import javafx.scene.Node;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginSubPage;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

/// 服务器列表插件：浏览社区服务器（实时人数/延迟/版本/图标）、搜索与分类筛选、
/// 一键进入游戏，并可连接 mcstat.org 目录发现更多服务器。
///
/// <p>本插件只发布一个“子页面”。启动器在没有任何汇聚页（如「服务器」模块）时会把它
/// 直接显示为主页面侧边栏入口；一旦安装了汇聚页，它就会被收进汇聚页内。</p>
public class ServerListPlugin extends HmclPlugin {

    private static final String SUB_PAGE_KEY = "org.dsh.server-list";

    private static PluginContext pluginContext;
    private static PluginSubPage subPage;

    @Override
    public void onEnable() {
        pluginContext = getContext();
        getContext().logInfo("服务器列表已启用，数据目录：" + getContext().getDataDirectory());

        subPage = new PluginSubPage(SUB_PAGE_KEY, "服务器列表",
                "浏览社区服务器：实时在线人数、连接延迟、版本与玩法筛选，支持搜索，"
                        + "还可连接 mcstat.org 发现 6000+ 服务器",
                SVG.GLOBE_BOOK,
                back -> new ServerListPage(getContext().getDataDirectory(), back));
        PluginMenuService.registerSubPage(subPage);
    }

    @Override
    public void onDisable() {
        if (subPage != null) {
            PluginMenuService.unregisterSubPage(subPage);
            subPage = null;
        }
        getContext().logInfo("服务器列表已停用");
    }

    /// 写入启动器日志。
    public static void log(String message) {
        PluginContext context = pluginContext;
        if (context != null)
            context.logInfo(message);
    }

    public static void error(String title, String message) {
        Controllers.dialog(message == null || message.isEmpty() ? "未知错误" : message,
                title, MessageDialogPane.MessageType.ERROR);
    }
}
