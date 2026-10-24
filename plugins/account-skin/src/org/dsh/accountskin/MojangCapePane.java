package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.util.Lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微软账户的官方披风选择器：
 * 左侧是账户已拥有的披风列表（顶部是“无”选项，可取消穿戴披风）——显示官方名称（alias）
 * 与缩略图、预选当前使用中的披风，选中项用蓝色方框框住；右侧是 3D 整体预览
 * （普通/鞘翅形态可切换，使用账户自身皮肤）。选中后调用 capes/active 接口设为当前披风。
 */
public final class MojangCapePane extends StackPane {

    // 选中态：主题强调色圆角边框 + 同色浅底（随启动器主题变化）
    private static final String SELECTED_STYLE =
            "-fx-border-color: -monet-primary; -fx-border-width: 2; -fx-border-radius: 8;"
                    + " -fx-background-radius: 8; -fx-background-color: derive(-monet-primary, 90%); -fx-padding: 6;";
    private static final String UNSELECTED_STYLE =
            "-fx-border-color: transparent; -fx-border-width: 2; -fx-border-radius: 8;"
                    + " -fx-background-radius: 8; -fx-padding: 6;";
    // 形态切换按钮：药丸形分段按钮，主题配色
    private static final String TOGGLE_SELECTED_STYLE =
            "-fx-background-color: -monet-primary; -fx-text-fill: -monet-on-primary;"
                    + " -fx-background-radius: 18; -fx-padding: 4 16 4 16; -fx-font-size: 12px; -fx-cursor: hand;";
    private static final String TOGGLE_UNSELECTED_STYLE =
            "-fx-background-color: -monet-surface-container-high; -fx-text-fill: -monet-on-surface-variant;"
                    + " -fx-background-radius: 18; -fx-padding: 4 16 4 16; -fx-font-size: 12px; -fx-cursor: hand;";

    private final MicrosoftAccount account;
    private final Runnable onApplied;
    private final ListView<SkinApplier.CapeDto> listView = new ListView<>();
    private final Label statusLabel = new Label();
    private final ToggleGroup formGroup = new ToggleGroup();
    private final ElytraSkinCanvas previewCanvas = new ElytraSkinCanvas(220, 270);
    private final JFXButton applyButton = new JFXButton("设为当前披风");

    /// 已下载的披风贴图（按披风 id 缓存），同时用于列表缩略图与 3D 预览。
    private final Map<String, Image> textureCache = new HashMap<>();
    private Image accountSkin = TexturesLoader.getDefaultSkinImage();
    private boolean accountSlim = false;

    public MojangCapePane(MicrosoftAccount account, Runnable onApplied) {
        this.account = account;
        this.onApplied = onApplied;

        JFXDialogLayout layout = new JFXDialogLayout();
        getChildren().setAll(layout);

        layout.setHeading(new Label("选择官方披风 · " + account.getProfileName()));

        // ---- 左列：披风列表（缩略图 + 官方名称） ----
        listView.setPrefSize(290, 380);
        listView.setPlaceholder(new Label("该账户还没有已拥有的披风"));
        listView.setCellFactory(view -> new CapeCell());
        VBox left = new VBox(8, listView, statusLabel);

        // ---- 右列：整体 3D 预览 + 形态切换 ----
        ToggleButton normal = formToggleButton("普通形态", false, true);
        ToggleButton elytra = formToggleButton("鞘翅形态", true, false);
        HBox formButtons = new HBox(10, normal, elytra);
        formButtons.setAlignment(Pos.CENTER);
        Label hint = new Label("拖动旋转 · 滚轮缩放");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        StackPane canvasPane = new StackPane(previewCanvas);
        canvasPane.setPrefSize(224, 274);
        VBox right = new VBox(8, canvasPane, formButtons, hint);
        right.setAlignment(Pos.TOP_CENTER);
        right.setPadding(new Insets(0, 0, 0, 12));

        HBox body = new HBox(12, left, right);
        layout.setBody(body);

        applyButton.getStyleClass().add("dialog-accept");
        applyButton.setDisable(true);
        applyButton.setOnAction(e -> {
            SkinApplier.CapeDto selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null)
                apply(selected);
        });
        JFXButton cancelButton = new JFXButton("取消");
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        layout.setActions(applyButton, cancelButton);

        listView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            applyButton.setDisable(nv == null);
            applyButton.setText(nv != null && nv.id == null ? "取消穿戴披风" : "设为当前披风");
            updatePreview();
        });
        formGroup.selectedToggleProperty().addListener((obs, ov, nv) -> {
            if (ov != null)
                styleToggle((ToggleButton) ov, false);
            if (nv != null)
                styleToggle((ToggleButton) nv, true);
            updatePreview();
        });

        updatePreview();
        fetch();
    }

    // ==== 预览 ====

    /// 根据当前选择与形态按钮刷新 3D 预览。
    private void updatePreview() {
        Toggle toggle = formGroup.getSelectedToggle();
        boolean elytraForm = toggle != null && Boolean.TRUE.equals(toggle.getUserData());

        SkinApplier.CapeDto item = listView.getSelectionModel().getSelectedItem();
        Image cape = item == null ? null : capeImage(item);
        if (cape != null && (cape.isError() || cape.getProgress() < 1.0))
            cape = null; // 尚未加载完成：先展示账户皮肤，加载完成后会再次刷新
        previewCanvas.setModel(accountSkin, accountSlim, cape, elytraForm);
    }

    /// 下载账户当前使用的皮肤作为预览人物；失败时保持默认史蒂夫。
    private void loadAccountSkin(List<SkinApplier.SkinDto> skins) {
        if (skins == null)
            return;
        SkinApplier.SkinDto active = skins.stream()
                .filter(skin -> "ACTIVE".equalsIgnoreCase(skin.state))
                .findFirst().orElse(null);
        if (active == null || active.url == null || active.url.isEmpty())
            return;
        Image image = new Image(active.url, true);
        whenImageReady(image, () -> {
            if (image.isError() || image.getWidth() == 0)
                return;
            accountSkin = image;
            accountSlim = "SLIM".equalsIgnoreCase(active.variant);
            updatePreview();
        });
    }

    /// 返回披风贴图（异步加载，按披风 id 缓存）；加载完成后刷新列表缩略图与预览。
    private Image capeImage(SkinApplier.CapeDto cape) {
        if (cape.url == null || cape.url.isEmpty())
            return null;
        String key = cape.id != null ? cape.id : cape.url;
        Image cached = textureCache.get(key);
        if (cached != null)
            return cached;

        Image image = new Image(cape.url, true);
        textureCache.put(key, image);
        image.progressProperty().addListener((obs, ov, nv) -> {
            if (nv.doubleValue() >= 1.0) {
                listView.refresh();
                if (!image.isError())
                    updatePreview();
            }
        });
        return image;
    }

    private static void whenImageReady(Image image, Runnable action) {
        if (image.getProgress() >= 1.0) {
            action.run();
        } else {
            image.progressProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() >= 1.0)
                    action.run();
            });
        }
    }

    // ==== 列表数据 ====

    private void fetch() {
        statusLabel.setText("加载中…");
        Lang.thread(() -> {
            try {
                SkinApplier.MinecraftProfileDto profile = SkinApplier.fetchMojangProfile(account);
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    List<SkinApplier.CapeDto> capes = profile.capes != null ? profile.capes : List.of();
                    List<SkinApplier.CapeDto> items = new ArrayList<>(capes.size() + 1);
                    items.add(noneCape());
                    items.addAll(capes);
                    listView.setItems(FXCollections.observableArrayList(items));
                    SkinApplier.CapeDto active = activeCape();
                    listView.getSelectionModel().select(active != null ? active : items.get(0));
                    loadAccountSkin(profile.skins);
                });
            } catch (Throwable e) {
                Platform.runLater(() -> statusLabel.setText("加载失败：" + e.getMessage()));
            }
        }, "mojang-capes", true);
    }

    /// 当前列表数据中处于“使用中”的披风；未穿戴披风时为 null。
    private SkinApplier.CapeDto activeCape() {
        return listView.getItems().stream()
                .filter(cape -> "ACTIVE".equalsIgnoreCase(cape.state))
                .findFirst().orElse(null);
    }

    /// “无”选项（不穿戴任何披风）：id 为 null 作为标识，没有纹理与状态。
    private static SkinApplier.CapeDto noneCape() {
        SkinApplier.CapeDto cape = new SkinApplier.CapeDto();
        cape.alias = "无";
        return cape;
    }

    private void apply(SkinApplier.CapeDto cape) {
        boolean remove = cape.id == null;
        SkinApplier.CapeDto worn = activeCape();
        if (remove && worn == null) {
            // 本来就没有穿戴披风，无需请求
            fireEvent(new DialogCloseEvent());
            return;
        }

        statusLabel.setText(remove ? "正在取消披风…" : "正在切换…");
        applyButton.setDisable(true);
        Lang.thread(() -> {
            try {
                if (remove)
                    SkinApplier.deactivateMojangCape(account, worn.id);
                else
                    SkinApplier.activateMojangCape(account, cape);
                Platform.runLater(() -> {
                    fireEvent(new DialogCloseEvent());
                    onApplied.run();
                });
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    statusLabel.setText((remove ? "取消失败：" : "切换失败：") + e.getMessage());
                    applyButton.setDisable(false);
                });
            }
        }, "mojang-cape-apply", true);
    }

    // ==== 形态切换按钮 ====

    private ToggleButton formToggleButton(String text, boolean secondForm, boolean selected) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(formGroup);
        button.setUserData(secondForm);
        button.setSelected(selected);
        button.setMaxWidth(Double.MAX_VALUE);
        styleToggle(button, selected);
        return button;
    }

    private void styleToggle(ToggleButton button, boolean selected) {
        if (selected)
            button.setStyle(TOGGLE_SELECTED_STYLE);
        else
            button.setStyle(TOGGLE_UNSELECTED_STYLE);
    }

    // ==== 列表项 ====

    /// 披风的官方名称（alias）；接口未给出名称时退回 id 以免显示空白。
    private static String capeName(SkinApplier.CapeDto cape) {
        return cape.alias != null && !cape.alias.isEmpty() ? cape.alias : cape.id;
    }

    /// 列表项：披风缩略图 + 官方名称 + 使用状态；选中时用主题色蓝色方框框住整项。
    private final class CapeCell extends ListCell<SkinApplier.CapeDto> {
        private final Canvas thumbnail = new Canvas(40, 40);
        private final Label titleLabel = new Label();
        private final Label subtitleLabel = new Label();
        private final HBox box;

        CapeCell() {
            titleLabel.getStyleClass().add("title");
            titleLabel.setMaxWidth(170); // 过长名称以省略号截断，避免撑宽列表
            subtitleLabel.setStyle("-fx-text-fill: -monet-on-surface-variant; -fx-font-size: 11px;");
            VBox text = new VBox(2, titleLabel, subtitleLabel);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(10, thumbnail, text);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMaxWidth(Region.USE_PREF_SIZE); // 蓝色方框紧贴条目内容
            setAlignment(Pos.CENTER_LEFT);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPrefWidth(0);
            setStyle("-fx-background-color: transparent; -fx-padding: 2 8 2 8;");
        }

        @Override
        protected void updateItem(SkinApplier.CapeDto cape, boolean empty) {
            super.updateItem(cape, empty);
            if (empty || cape == null) {
                setGraphic(null);
                return;
            }

            if (cape.id == null) {
                // “无”选项：不穿戴任何披风
                titleLabel.setText(capeName(cape));
                subtitleLabel.setText(activeCape() != null ? "不穿戴披风" : "使用中");
                drawThumbnail(thumbnail, null);
            } else {
                titleLabel.setText(capeName(cape));
                subtitleLabel.setText("ACTIVE".equalsIgnoreCase(cape.state) ? "使用中" : "");
                drawThumbnail(thumbnail, capeImage(cape));
            }

            box.setStyle(isSelected() ? SELECTED_STYLE : UNSELECTED_STYLE);
            setGraphic(box);
        }

        private void drawThumbnail(Canvas canvas, Image image) {
            var g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            if (image == null || image.isError() || image.getProgress() < 1.0 || image.getWidth() == 0)
                return;

            g.setImageSmoothing(false);
            // 披风正面区域约占 (1,1)-(11,17)，按原图比例缩放居中
            double scale = image.getWidth() / 64.0;
            double w = 10 * scale, h = 16 * scale;
            double fit = Math.min((canvas.getWidth() - 4) / w, (canvas.getHeight() - 4) / h);
            g.drawImage(image, scale, scale, w, h,
                    (canvas.getWidth() - w * fit) / 2, (canvas.getHeight() - h * fit) / 2, w * fit, h * fit);
        }
    }
}
