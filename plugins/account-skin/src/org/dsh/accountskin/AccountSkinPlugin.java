package org.dsh.accountskin;

import javafx.scene.Node;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.plugin.PluginAccountAction;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;

/**
 * 账户皮肤切换插件：在账户列表的每个账户上注入“切换皮肤 / 切换披风”按钮。
 *
 * <p>皮肤来源支持 LittleSkin 皮肤库（在线）与本地文件（自定义）；
 * 微软账户走 Mojang 官方 API，离线账户通过 HMCL 本地皮肤源机制在游戏内生效。</p>
 *
 * <p>外置登录（authlib-injector / 皮肤站）账户不显示这两个按钮：
 * 这类账户的皮肤由皮肤站自己管理，插件注入的入口在那儿没有意义。</p>
 *
 * <p>插件同时会隐藏启动器自带的上传/编辑皮肤按钮，避免同一件事有两个入口。</p>
 */
public class AccountSkinPlugin extends HmclPlugin {

    private PluginAccountAction skinAction;
    private PluginAccountAction capeAction;

    /// 外置登录账户的皮肤由皮肤站管理，插件不提供切换入口。
    ///
    /// <p>判据是账户类型而不是登录方式名称：{@link AuthlibInjectorAccount} 覆盖所有
    /// 通过 authlib-injector 登录的第三方皮肤站账户。</p>
    static boolean supports(Account account) {
        return !(account instanceof AuthlibInjectorAccount);
    }

    @Override
    public void onEnable() {
        skinAction = new PluginAccountAction() {
            @Override
            public String getName() {
                return "切换皮肤";
            }

            @Override
            public Node createIcon() {
                return SVG.STYLE.createIcon();
            }

            @Override
            public boolean supports(Account account) {
                return AccountSkinPlugin.supports(account);
            }

            @Override
            public void run(Account account) {
                Controllers.dialog(new SkinCapeDialog(account, SkinCapeDialog.Mode.SKIN,
                        getContext().getDataDirectory()));
            }
        };

        capeAction = new PluginAccountAction() {
            @Override
            public String getName() {
                return "切换披风";
            }

            @Override
            public Node createIcon() {
                return SVG.DRESSER.createIcon();
            }

            @Override
            public boolean supports(Account account) {
                return AccountSkinPlugin.supports(account);
            }

            @Override
            public void run(Account account) {
                Controllers.dialog(new SkinCapeDialog(account, SkinCapeDialog.Mode.CAPE,
                        getContext().getDataDirectory()));
            }
        };

        PluginMenuService.registerAccountAction(skinAction);
        PluginMenuService.registerAccountAction(capeAction);
        // 插件自己提供皮肤入口，所以去掉启动器原生那个，避免重复。
        PluginMenuService.setAccountSkinUploadVisible(false);
        getContext().logInfo("账户皮肤切换插件已启用（原生的上传/编辑皮肤按钮已隐藏）");
    }

    @Override
    public void onDisable() {
        if (skinAction != null)
            PluginMenuService.unregisterAccountAction(skinAction);
        if (capeAction != null)
            PluginMenuService.unregisterAccountAction(capeAction);
        // 恢复启动器原生按钮，否则停用插件后这个功能会凭空消失。
        PluginMenuService.setAccountSkinUploadVisible(true);
        getContext().logInfo("账户皮肤切换插件已停用");
    }
}
