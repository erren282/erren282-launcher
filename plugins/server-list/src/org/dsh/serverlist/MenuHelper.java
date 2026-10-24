package org.dsh.serverlist;

import org.jackhuang.hmcl.ui.DialogUtils;

/// 关闭由「控制器对话框」打开的对话框面板。
///
/// <p>启动器的对话框关闭原本依赖在内容面板上冒泡的 [org.jackhuang.hmcl.ui.construct.DialogCloseEvent]；
/// 在对话框内容面板被重复压栈或事件未到达处理器时，点“取消”会失效（面板关不掉）。
/// 这里直接调用启动器自己的 [DialogUtils#close]，行为与原生对话框完全一致且不依赖事件传播。</p>
final class MenuHelper {

    private MenuHelper() {
    }

    /// Closes the dialog that hosts `content`, cleaning up the launcher's dialog state.
    static void closeDialog(javafx.scene.Node content) {
        if (content == null)
            return;
        try {
            if (content.getProperties().get(DialogUtils.PROPERTY_PARENT_DIALOG_REF) != null) {
                DialogUtils.close(content);
            }
        } catch (Throwable ignored) {
            // 已经关闭：忽略
        }
        // 兜底：直接关掉对话框（若上面的清理没生效）
        Object dialog = content.getProperties().get(DialogUtils.PROPERTY_PARENT_DIALOG_REF);
        if (dialog instanceof com.jfoenix.controls.JFXDialog jfxDialog && jfxDialog.isVisible()) {
            jfxDialog.close();
        }
    }
}
