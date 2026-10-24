/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.account;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXRadioButton;
import com.jfoenix.effects.JFXDepthManager;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.collections.WeakListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.plugin.PluginAccountAction;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.util.javafx.BindingMapping;

import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class AccountListItemSkin extends SkinBase<AccountListItem> {

    /// Fills {@code actions} with one button per plugin action that applies to
    /// {@code account}, and hides the row when none do.
    ///
    /// <p>Rebuilt rather than bound. Binding a mapped list copies the mapped elements
    /// into the parent's children with {@code setAll}, and JavaFX rejects a null child,
    /// so an action that does not apply crashed the launcher with
    /// {@code NullPointerException: Children: child node is null}. Filtering here keeps
    /// inapplicable actions out entirely.</p>
    ///
    /// @param available the actions to consider, so this can be tested without the
    ///                  global registry
    static void fillPluginActions(HBox actions, Account account,
                                  List<PluginAccountAction> available) {
        List<Node> buttons = new ArrayList<>();
        for (PluginAccountAction action : available) {
            // An action that does not apply to this account contributes no button at
            // all, rather than one that refuses to work.
            if (!action.supports(account)) {
                continue;
            }
            JFXButton button = new JFXButton();
            button.getStyleClass().add("toggle-icon4");
            button.setGraphic(action.createIcon());
            FXUtils.installFastTooltip(button, action.getName());
            button.setOnAction(e -> action.run(account));
            buttons.add(button);
        }
        actions.getChildren().setAll(buttons);
        // Hidden when empty, so an account with no applicable actions does not reserve
        // the row's spacing for nothing.
        actions.setVisible(!buttons.isEmpty());
    }

    public AccountListItemSkin(AccountListItem skinnable) {
        super(skinnable);

        BorderPane root = new BorderPane();
        root.setCursor(Cursor.HAND);
        FXUtils.onClicked(root, skinnable::fire);

        JFXRadioButton chkSelected = new JFXRadioButton();
        chkSelected.setMouseTransparent(true);
        BorderPane.setAlignment(chkSelected, Pos.CENTER);
        chkSelected.selectedProperty().bind(skinnable.selectedProperty());
        root.setLeft(chkSelected);

        HBox center = new HBox();
        center.setSpacing(8);
        center.setAlignment(Pos.CENTER_LEFT);

        Canvas canvas = new Canvas(32, 32);
        TexturesLoader.bindAvatar(canvas, skinnable.getAccount());

        Label title = new Label();
        title.getStyleClass().add("title");
        title.textProperty().bind(skinnable.titleProperty());
        Label subtitle = new Label();
        subtitle.getStyleClass().add("subtitle");
        subtitle.textProperty().bind(skinnable.subtitleProperty());
        if (skinnable.getAccount() instanceof AuthlibInjectorAccount) {
            Tooltip tooltip = new Tooltip();
            AuthlibInjectorServer server = ((AuthlibInjectorAccount) skinnable.getAccount()).getServer();
            tooltip.textProperty().bind(BindingMapping.of(server, AuthlibInjectorServer::toString));
            FXUtils.installSlowTooltip(subtitle, tooltip);
        }
        VBox item = new VBox(title, subtitle);
        item.getStyleClass().add("two-line-list-item");
        BorderPane.setAlignment(item, Pos.CENTER);

        center.getChildren().setAll(canvas, item);
        root.setCenter(center);
        BorderPane.setMargin(center, new Insets(0, 0, 0, 8));

        HBox right = new HBox();
        right.setAlignment(Pos.CENTER_RIGHT);

        JFXButton btnMove = new JFXButton();
        SpinnerPane spinnerMove = new SpinnerPane();
        spinnerMove.getStyleClass().add("small-spinner-pane");
        btnMove.setOnAction(e -> {
            Account account = skinnable.getAccount();
            if (!Accounts.canMoveAccount(account)) {
                Controllers.confirmBackupAndOverwrite(i18n("account.storage.read_only"), () -> {
                    Accounts.forceOverwriteAccountFiles();
                    moveAccount(skinnable);
                });
                return;
            }

            moveAccount(skinnable);
        });
        btnMove.getStyleClass().add("toggle-icon4");
        if (skinnable.getAccount().isPortable()) {
            btnMove.setGraphic(SVG.PUBLIC.createIcon());
            FXUtils.installFastTooltip(btnMove, i18n("account.move_to_global"));
        } else {
            btnMove.setGraphic(SVG.OUTPUT.createIcon());
            FXUtils.installFastTooltip(btnMove, i18n("account.move_to_portable"));
        }
        spinnerMove.setContent(btnMove);
        right.getChildren().add(spinnerMove);

        JFXButton btnRefresh = FXUtils.newToggleButton4(SVG.REFRESH);
        SpinnerPane spinnerRefresh = new SpinnerPane();
        spinnerRefresh.getStyleClass().setAll("small-spinner-pane");
        spinnerRefresh.setPrefSize(30.0, 30.0);
        if (skinnable.getAccount() instanceof MicrosoftAccount && Accounts.OAUTH_CALLBACK.getClientId().isEmpty()) {
            btnRefresh.setDisable(true);
            FXUtils.installFastTooltip(spinnerRefresh, i18n("account.methods.microsoft.snapshot.tooltip"));
        }
        btnRefresh.setOnAction(e -> {
            spinnerRefresh.showSpinner();
            skinnable.refreshAsync()
                    .whenComplete(Schedulers.javafx(), ex -> {
                        spinnerRefresh.hideSpinner();

                        if (ex != null) {
                            Controllers.showToast(Accounts.localizeErrorMessage(ex));
                        }
                    })
                    .start();
        });
        FXUtils.installFastTooltip(btnRefresh, i18n("button.refresh"));
        spinnerRefresh.setContent(btnRefresh);
        right.getChildren().add(spinnerRefresh);

        // The launcher's own upload/edit-skin button. Plugins can turn it off, which is
        // how the account-skin plugin replaces it with its own richer flow instead of
        // leaving two upload entries side by side.
        if (PluginMenuService.isAccountSkinUploadVisible()) {
            JFXButton btnUpload = FXUtils.newToggleButton4(SVG.CHECKROOM);
            SpinnerPane spinnerUpload = new SpinnerPane();
            btnUpload.setOnAction(e -> {
                Task<?> uploadTask = skinnable.uploadSkin();
                if (uploadTask != null) {
                    spinnerUpload.showSpinner();
                    uploadTask
                            .whenComplete(Schedulers.javafx(), ex -> spinnerUpload.hideSpinner())
                            .start();
                }
            });
            FXUtils.installFastTooltip(btnUpload, i18n("account.skin.upload"));
            btnUpload.disableProperty().bind(Bindings.not(skinnable.canUploadSkin()));
            spinnerUpload.setContent(btnUpload);
            spinnerUpload.getStyleClass().add("small-spinner-pane");
            right.getChildren().add(spinnerUpload);
        }

        // Built by rebuilding the row's buttons rather than by binding a mapped list to
        // the children. A mapped list is bound with `Bindings.bindContent`, which calls
        // `setAll` on the children — and a parent rejects a null child, so an action
        // that does not apply to this account (returning null from the mapper) crashed
        // the launcher:
        //
        //     NullPointerException: Children: child node is null: parent = HBox
        //
        // Filtering here instead keeps inapplicable actions out entirely.
        HBox pluginActions = new HBox();
        pluginActions.managedProperty().bind(pluginActions.visibleProperty());
        fillPluginActions(pluginActions, skinnable.getAccount(),
                PluginMenuService.getAccountActions());
        // Plugins may register or unregister actions at any time; keep the row in step.
        // The listener is weak so a discarded skin cannot be kept alive by the registry.
        ListChangeListener<PluginAccountAction> actionsListener = change ->
                fillPluginActions(pluginActions, skinnable.getAccount(),
                        PluginMenuService.getAccountActions());
        WeakListChangeListener<PluginAccountAction> weakActionsListener =
                new WeakListChangeListener<>(actionsListener);
        PluginMenuService.getAccountActions().addListener(weakActionsListener);
        right.getChildren().add(pluginActions);

        JFXButton btnCopyUUID = FXUtils.newToggleButton4(SVG.CONTENT_COPY);
        btnCopyUUID.setOnAction(e -> FXUtils.copyText(skinnable.getAccount().getProfileID().toString()));
        FXUtils.installFastTooltip(btnCopyUUID, i18n("account.copy_uuid"));
        right.getChildren().add(btnCopyUUID);

        JFXButton btnRemove = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
        btnRemove.setOnAction(e -> Controllers.confirm(i18n("button.remove.confirm"), i18n("button.remove"), skinnable::remove, null));
        BorderPane.setAlignment(btnRemove, Pos.CENTER);
        FXUtils.installFastTooltip(btnRemove, i18n("button.delete"));
        right.getChildren().add(btnRemove);
        root.setRight(right);

        root.getStyleClass().add("card");
        root.setStyle("-fx-padding: 8 8 8 0;");
        JFXDepthManager.setDepth(root, 1);

        getChildren().setAll(root);
    }

    /// Moves the account between local and user account files.
    private static void moveAccount(AccountListItem skinnable) {
        Account account = skinnable.getAccount();
        Accounts.getAccounts().remove(account);
        if (account.isPortable()) {
            account.setPortable(false);
            if (!Accounts.getAccounts().contains(account))
                Accounts.getAccounts().add(account);
        } else {
            account.setPortable(true);
            if (!Accounts.getAccounts().contains(account)) {
                int idx = 0;
                for (int i = Accounts.getAccounts().size() - 1; i >= 0; i--) {
                    if (Accounts.getAccounts().get(i).isPortable()) {
                        idx = i + 1;
                        break;
                    }
                }
                Accounts.getAccounts().add(idx, account);
            }
        }
    }
}
