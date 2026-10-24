/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginDescription;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.RequiredValidator;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.URLValidator;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The dialog for installing a plugin from a download URL.
///
/// <p>The jar is downloaded to a temporary directory, validated like any other plugin jar
/// and then handed to [PluginManager#install]. The file name shown in the plugin directory
/// is taken from the URL path when it is a sane jar name, and derived from the plugin
/// identifier otherwise.</p>
@NotNullByDefault
public final class OnlinePluginInstallPane extends JFXDialogLayout implements DialogAware {

    /// The largest accepted download size, guarding against oversized responses.
    private static final long MAX_DOWNLOAD_SIZE = 64L * 1024 * 1024;

    /// The pattern a file name taken from the URL path must match to be used as the jar name.
    private static final Pattern JAR_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar", Pattern.CASE_INSENSITIVE);

    private final JFXTextField txtUrl = new JFXTextField();
    private final Label lblWarning = new Label();
    private final SpinnerPane installSpinner = new SpinnerPane();
    private final JFXButton btnInstall = new JFXButton(i18n("button.install"));

    /// The display name of the plugin installed by the current action, for the success toast.
    private @Nullable String installedName;

    public OnlinePluginInstallPane() {
        setHeading(new Label(i18n("plugin.install.online")));

        txtUrl.setPromptText(i18n("plugin.install.online.url"));
        txtUrl.setOnAction(e -> onInstall());

        Label hintLabel = new Label(i18n("plugin.install.online.hint"));
        hintLabel.setWrapText(true);

        lblWarning.setWrapText(true);
        lblWarning.setStyle("-fx-text-fill: -monet-error;");
        lblWarning.setMinWidth(0);
        lblWarning.setPrefWidth(0);
        lblWarning.visibleProperty().bind(lblWarning.textProperty().isNotEmpty());
        lblWarning.managedProperty().bind(lblWarning.visibleProperty());

        // The JFX error line overflows below the field, so the gap must exceed its height
        VBox body = new VBox(14, txtUrl, hintLabel, lblWarning);
        lblWarning.maxWidthProperty().bind(body.widthProperty());
        setBody(body);

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        installSpinner.getStyleClass().add("small-spinner-pane");
        btnInstall.getStyleClass().add("dialog-accept");
        btnInstall.setOnAction(e -> onInstall());
        installSpinner.setContent(btnInstall);

        HBox actions = new HBox(cancelButton, installSpinner);
        setActions(actions);

        txtUrl.getValidators().addAll(new RequiredValidator(), new URLValidator());
        FXUtils.setValidateWhileTextChanged(txtUrl, true);
        btnInstall.disableProperty().bind(txtUrl.activeValidatorProperty().isNotNull());

        onEscPressed(this, () -> fireEvent(new DialogCloseEvent()));

        installSpinner.hideSpinner();
    }

    @Override
    public void onDialogShown() {
        txtUrl.requestFocus();
    }

    private void onInstall() {
        if (btnInstall.isDisabled())
            return;

        lblWarning.setText("");

        String url = txtUrl.getText().trim();

        setDisable(true);
        installSpinner.showSpinner();

        Task.runAsync(() -> installFromUrl(url))
                .whenComplete(Schedulers.javafx(), exception -> {
                    setDisable(false);
                    installSpinner.hideSpinner();

                    if (exception == null) {
                        fireEvent(new DialogCloseEvent());
                        Controllers.showToast(i18n("plugin.install.success", installedName));
                    } else {
                        LOG.warning("Failed to install plugin from " + url, exception);
                        lblWarning.setText(describe(exception));
                    }
                })
                .start();
    }

    /// Downloads the plugin jar at the given URL, installs it and reloads the plugins.
    private void installFromUrl(String url) throws IOException {
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IOException(i18n("plugin.install.online.invalid_url"));

        Path tempDirectory = Files.createTempDirectory("hmcl-plugin-");
        Path jar = tempDirectory.resolve("downloaded-plugin.jar");

        try {
            download(url, jar);

            PluginDescription description = PluginDescription.readFromJar(jar);

            String fileName = fileNameFromUrl(url);
            if (fileName == null)
                fileName = fallbackFileName(description);
            if (!fileName.equals(jar.getFileName().toString())) {
                Path renamed = tempDirectory.resolve(fileName);
                Files.move(jar, renamed, StandardCopyOption.REPLACE_EXISTING);
                jar = renamed;
            }

            PluginManager.install(jar);
            PluginManager.reload();

            installedName = description.getName();
        } finally {
            try {
                Files.deleteIfExists(jar);
                Files.deleteIfExists(tempDirectory);
            } catch (IOException e) {
                LOG.warning("Failed to clean up temporary plugin files in " + tempDirectory, e);
            }
        }
    }

    /// Downloads the file at the given URL into the target file.
    private static void download(String url, Path target) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP " + responseCode + " " + connection.getResponseMessage());

            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_SIZE)
                throw new IOException("File is too large: " + contentLength + " bytes");

            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
        }
    }

    /// Extracts the jar file name from the URL path, or `null` when it has none usable.
    private static @Nullable String fileNameFromUrl(String url) {
        String cleaned = url;

        int index = cleaned.indexOf('?');
        if (index >= 0)
            cleaned = cleaned.substring(0, index);
        index = cleaned.indexOf('#');
        if (index >= 0)
            cleaned = cleaned.substring(0, index);
        index = cleaned.lastIndexOf('/');
        if (index >= 0)
            cleaned = cleaned.substring(index + 1);

        return JAR_FILE_NAME.matcher(cleaned).matches() ? cleaned : null;
    }

    /// Builds an identifier-based file name for downloads whose URL carries no usable jar name.
    private static String fallbackFileName(PluginDescription description) {
        String version = description.getVersion();
        if (version == null || version.isBlank())
            return description.getId() + ".jar";

        return description.getId() + '-' + version.replaceAll("[^A-Za-z0-9._-]", "_") + ".jar";
    }

    /// Describes an exception with its message, falling back to the class name.
    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && !message.isBlank() ? message : throwable.getClass().getSimpleName();
    }
}
