package org.dsh.serverhub;

import javafx.scene.Node;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginLauncherPage;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.ui.SVG;

/// 「服务器」汇聚插件：把已安装的服务器相关插件页面汇聚成主页面上的一个入口。
///
/// <p>它只注册一个**分组页**（[PluginLauncherPage#isPageGroup]）。启动器据此把各插件发布
/// 的子页面收进本页，不再单独显示；反过来说，卸载本插件后子页面会直接回到主页面侧边栏。
/// 卡片列表由 [PluginMenuService#getSubPages] 动态生成，因此任何后续新增的服务器类插件
/// 都会自动出现在这里。</p>
public class ServerHubPlugin extends HmclPlugin {

    private static PluginLauncherPage hubPage;

    @Override
    public void onEnable() {
        getContext().logInfo("服务器模块已启用");

        hubPage = new PluginLauncherPage() {
            @Override
            public String getTitle() {
                return "服务器";
            }

            @Override
            public SVG getIcon() {
                return SVG.PUBLIC;
            }

            /// 声明本页是汇聚页：子页面不再作为独立侧边栏入口显示。
            @Override
            public boolean isPageGroup() {
                return true;
            }

            @Override
            public Node createPage() {
                return new ServerHubPage();
            }
        };
        PluginMenuService.registerLauncherPage(hubPage);
    }

    @Override
    public void onDisable() {
        if (hubPage != null) {
            PluginMenuService.unregisterLauncherPage(hubPage);
            hubPage = null;
        }
        getContext().logInfo("服务器模块已停用");
    }
}
