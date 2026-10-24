# -*- coding: utf-8 -*-
# Patch ServerManagerPage: Java path field -> scanned combo.
import io

P = r"E:\game\gamea\mcl\HMCL\docs\plugin\server-manager\src\org\dsh\servermanager\ServerManagerPage.java"
s = io.open(P, encoding="utf-8").read()

# field declaration
old = '''    private JFXTextField javaField;'''
assert old in s
s = s.replace(old, '''    private JFXComboBox<JavaScanner.JavaInfo> javaCombo;''', 1)

# detail form java row
old = '''        detailFields.add(new Label("Java 路径"), 0, row);
        HBox javaRow = new HBox(8);
        javaRow.setAlignment(Pos.CENTER_LEFT);
        javaField = new JFXTextField();
        javaField.setPromptText("留空使用启动器自带 Java；可指定 java.exe 或其目录");
        HBox.setHgrow(javaField, Priority.ALWAYS);
        javafx.scene.control.Button chooseJavaButton = FXUtils.newRaisedButton("选择");
        chooseJavaButton.setOnAction(e -> chooseJava());
        javaRow.getChildren().addAll(javaField, chooseJavaButton);
        detailFields.add(javaRow, 1, row++);'''
new = '''        detailFields.add(new Label("Java 路径"), 0, row);
        HBox javaRow = new HBox(8);
        javaRow.setAlignment(Pos.CENTER_LEFT);
        javaCombo = new JFXComboBox<>();
        javaCombo.setEditable(true);
        javaCombo.setPromptText("留空使用自带 Java；点“扫描”自动查找本机 Java");
        HBox.setHgrow(javaCombo, Priority.ALWAYS);
        javaCombo.setMaxWidth(Double.MAX_VALUE);
        javaCombo.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null)
                javaCombo.getEditor().setText(nv.getPath());
        });
        javafx.scene.control.Button chooseJavaButton = FXUtils.newRaisedButton("选择");
        chooseJavaButton.setOnAction(e -> chooseJava());
        javafx.scene.control.Button scanJavaButton = FXUtils.newRaisedButton("扫描");
        scanJavaButton.setOnAction(e -> scanJavas());
        javaRow.getChildren().addAll(javaCombo, chooseJavaButton, scanJavaButton);
        detailFields.add(javaRow, 1, row++);'''
assert old in s, "java row"
s = s.replace(old, new, 1)

# selectProfile
old = '''            javaField.setText(profile.getJavaPath());'''
assert old in s
s = s.replace(old, '''            javaCombo.getEditor().setText(profile.getJavaPath());''', 1)

# saveCurrent
old = '''            current.setJavaPath(javaField.getText().trim());'''
assert old in s
s = s.replace(old, '''            current.setJavaPath(javaCombo.getEditor().getText().trim());''', 1)

# chooseJava writes into the combo editor
old = '''        javafx.stage.File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null)
            javaField.setText(file.getAbsolutePath());'''
assert old in s
s = s.replace(old, '''        javafx.stage.File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null)
            javaCombo.getEditor().setText(file.getAbsolutePath());''', 1)

# scan support: add methods after chooseJava()
old = '''    // ------------------------------------------------------ room code ---'''
new = '''    /// Scans the computer for Java installations in the background and fills the combo.
    private void scanJavas() {
        new Thread(() -> {
            List<JavaScanner.JavaInfo> javas = JavaScanner.scan(service.getDataDirectory());
            Platform.runLater(() -> {
                javaCombo.getItems().setAll(javas);
                if (!javas.isEmpty()) {
                    String current = javaCombo.getEditor().getText();
                    if (current == null || current.isBlank())
                        appendConsoleLine("[server-manager] 已扫描到 " + javas.size() + " 个 Java，可直接下拉选择");
                }
            });
        }, "server-manager-java-scan").start();
    }

    // ------------------------------------------------------ room code ---'''
assert old in s
s = s.replace(old, new, 1)

# auto scan once the page is built
old = '''        reloadServerRows();
    }

    private void buildMainView() {'''
new = '''        reloadServerRows();
        scanJavas();
    }

    private void buildMainView() {'''
assert old in s
s = s.replace(old, new, 1)

# imports
if "import javafx.scene.control.ComboBox;" in s and "import com.jfoenix.controls.JFXComboBox;" not in s:
    s = s.replace("import com.jfoenix.controls.JFXCheckBox;", "import com.jfoenix.controls.JFXCheckBox;\nimport com.jfoenix.controls.JFXComboBox;")
if "import java.util.List;" not in s:
    s = s.replace("import java.nio.file.Path;", "import java.nio.file.Path;\nimport java.util.List;")

io.open(P, "w", encoding="utf-8", newline="").write(s)
print("ServerManagerPage patched")
