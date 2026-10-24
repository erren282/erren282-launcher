package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.transform.Rotate;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.skin.SkinCube;
import org.jackhuang.hmcl.ui.skin.SkinHelper;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.Lang;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LittleSkin 皮肤库浏览器：
 * 左侧为带缩略图的分页列表（选中项用蓝色方框高亮），右侧为整体 3D 预览——
 * 皮肤可切换史蒂夫/艾利克斯形态，披风可切换普通/鞘翅形态，支持拖动旋转、滚轮缩放。
 *
 * <p>接口（Blessing Skin / LittleSkin）：</p>
 * <ul>
 *   <li>列表：GET /skinlib/list?filter={skin|cape}&amp;sort={upload_at|likes}&amp;page=N
 *       （Laravel 简单分页；<b>不带 keyword</b>，见 {@link LocalSearch} 为何如此）</li>
 *   <li>详情：GET /skinlib/info/{tid}（取得材质 hash）</li>
 *   <li>材质：GET /textures/{hash}</li>
 * </ul>
 */
public final class LittleSkinLibraryPane extends StackPane {

    public enum Filter {
        SKIN("skin"), CAPE("cape");

        final String value;

        Filter(String value) {
            this.value = value;
        }
    }

    /// One entry of the skin library list; `hash` is filled lazily via /skinlib/info/{tid}.
    public static class Item {
        public int tid;
        public String name;
        public String type;
        public String nickname;
        public String hash;
        public boolean hd;
    }

    /// Pagination envelope of the skin library API (Laravel simple pagination).
    public static class Page {
        public int current_page;
        public String next_page_url;
        public String prev_page_url;
        public List<Item> data;
    }

    /// Detail response of /skinlib/info/{tid}.
    public static class Info {
        public int tid;
        public String hash;
    }

    private static final String LIBRARY_BASE = "https://littleskin.cn";
    private static final String TEXTURES_BASE = "https://littleskin.cn/textures/";

    /// Fixed width of the list column. Every control in it is pinned to this width so
    /// the dialog keeps one size: a long status message used to stretch the column and
    /// the dialog grew until it covered the launcher window.
    private static final double LIST_WIDTH = 290;

    /// Bounds for the list's height.
    ///
    /// <p>The dialog used to hard-code 380 px, which made it 524 px tall overall — more
    /// than the launcher's minimum content height of 492 px, so at the smallest window
    /// it did not fit and the proportions looked wrong. The height now follows the
    /// available space, within these bounds.</p>
    private static final double LIST_MIN_HEIGHT = 180;
    private static final double LIST_MAX_HEIGHT = 380;

    /// Height of the status line beneath the list.
    private static final double STATUS_HEIGHT = 32;

    /// Padding the dialog layout adds around its body.
    private static final double DIALOG_PADDING = 24;
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

    private final Filter filter;
    private final Path cacheDir;
    private final Consumer<Path> onPicked;
    private final JFXTextField searchField = new JFXTextField();
    private final ListView<Item> listView = new ListView<>();
    private final Label pageLabel = new Label();
    private final Label statusLabel = new Label();
    private final JFXButton prevButton = new JFXButton("上一页");
    private final JFXButton nextButton = new JFXButton("下一页");
    private final JFXButton applyButton = new JFXButton("应用所选");
    private final ToggleGroup formGroup = new ToggleGroup();
    private final ElytraSkinCanvas previewCanvas = new ElytraSkinCanvas(220, 270);

    /// Downloaded texture images keyed by tid, shared between thumbnails and the 3D preview.
    private final Map<Integer, Image> textureCache = new HashMap<>();

    private volatile boolean loading = false;
    private int page = 1;
    private boolean hasNext = false;
    private boolean hasPrev = false;

    public LittleSkinLibraryPane(Filter filter, Path cacheDir, Consumer<Path> onPicked) {
        this(filter, cacheDir, onPicked, true, availableContentHeight());
    }

    /**
     * @param initialFetch whether to load the first page. The dialog passes true; the
     *                     layout test passes false, so no background thread keeps
     *                     posting work to the FX thread while sizes are measured.
     * @param availableHeight the vertical room for the whole dialog, from which the
     *                        list height is derived so it fits the smallest window
     */
    LittleSkinLibraryPane(Filter filter, Path cacheDir, Consumer<Path> onPicked,
                          boolean initialFetch, double availableHeight) {
        this.initialFetchEnabled = initialFetch;
        this.listHeight = listHeightFor(availableHeight);
        this.filter = filter;
        this.cacheDir = cacheDir;
        this.onPicked = onPicked;

        JFXDialogLayout layout = new JFXDialogLayout();
        getChildren().setAll(layout);

        layout.setHeading(new Label(filter == Filter.SKIN
                ? "LittleSkin 皮肤库 - 选择皮肤"
                : "LittleSkin 皮肤库 - 选择披风"));

        // ---- 左列：搜索 + 缩略图列表 + 翻页 ----
        searchField.setPromptText("按名称 / 作者筛选热门皮肤（留空为浏览全部）");
        searchField.setOnAction(e -> {
            page = 1;
            fetch();
        });
        HBox.setHgrow(searchField, Priority.ALWAYS);

        JFXButton searchButton = new JFXButton("搜索");
        searchButton.setOnAction(e -> {
            page = 1;
            fetch();
        });
        HBox top = new HBox(8, searchField, searchButton);

        // Fixed size, so the dialog is the same before and after a search: the list
        // must not be allowed to shrink when results are few, and the status label
        // below must not be allowed to widen it when a message is long.
        listView.setPrefSize(LIST_WIDTH, listHeight);
        listView.setMinSize(LIST_WIDTH, listHeight);
        listView.setMaxSize(LIST_WIDTH, listHeight);
        listView.setPlaceholder(placeholderLabel("没有找到皮肤"));
        listView.setCellFactory(view -> new ItemCell());

        // Wrapped and height-capped: without this a long status message stretched the
        // HBox and the whole dialog grew over the launcher window. The full text stays
        // reachable through the tooltip if it ever has to be clipped.
        statusLabel.setWrapText(true);
        statusLabel.setPrefWidth(LIST_WIDTH);
        statusLabel.setMinWidth(LIST_WIDTH);
        statusLabel.setMaxWidth(LIST_WIDTH);
        statusLabel.setMinHeight(STATUS_HEIGHT);
        statusLabel.setPrefHeight(STATUS_HEIGHT);
        statusLabel.setMaxHeight(STATUS_HEIGHT);
        statusLabel.setStyle("-fx-font-size: 11px;");

        prevButton.setOnAction(e -> {
            if (hasPrev && !loading) {
                page--;
                fetch();
            }
        });
        nextButton.setOnAction(e -> {
            if (hasNext && !loading) {
                page++;
                fetch();
            }
        });
        pageLabel.setText("第 1 页");
        HBox pager = new HBox(16, prevButton, pageLabel, nextButton);
        pager.setAlignment(Pos.CENTER);
        pager.setPadding(new Insets(4, 0, 0, 0));
        pager.setPrefWidth(LIST_WIDTH);
        pager.setMinWidth(LIST_WIDTH);
        pager.setMaxWidth(LIST_WIDTH);

        top.setPrefWidth(LIST_WIDTH);
        top.setMinWidth(LIST_WIDTH);
        top.setMaxWidth(LIST_WIDTH);

        VBox left = new VBox(8, top, listView, pager, statusLabel);
        left.setPrefWidth(LIST_WIDTH);
        left.setMinWidth(LIST_WIDTH);
        left.setMaxWidth(LIST_WIDTH);
        HBox.setHgrow(left, Priority.NEVER);

        // ---- 右列：整体 3D 预览 + 形态切换 ----
        HBox formButtons = new HBox(10);
        formButtons.setAlignment(Pos.CENTER);
        if (filter == Filter.SKIN) {
            ToggleButton steve = formToggleButton("史蒂夫形态", false, true);
            ToggleButton alex = formToggleButton("艾利克斯形态", true, false);
            formButtons.getChildren().setAll(steve, alex);
        } else {
            ToggleButton normal = formToggleButton("普通形态", false, true);
            ToggleButton elytra = formToggleButton("鞘翅形态", true, false);
            formButtons.getChildren().setAll(normal, elytra);
        }
        Label hint = new Label("拖动旋转 · 滚轮缩放");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        StackPane canvasPane = new StackPane(previewCanvas);
        canvasPane.setPrefSize(224, 274);
        canvasPane.setMinSize(224, 274);
        canvasPane.setMaxSize(224, 274);
        VBox right = new VBox(8, canvasPane, formButtons, hint);
        right.setAlignment(Pos.TOP_CENTER);
        right.setPadding(new Insets(0, 0, 0, 12));
        // Pinned as well: the dialog must keep one size in every state, so nothing in
        // either column is allowed to stretch it.
        double rightWidth = 224 + 12;
        right.setPrefWidth(rightWidth);
        right.setMinWidth(rightWidth);
        right.setMaxWidth(rightWidth);

        HBox body = new HBox(12, left, right);
        double bodyWidth = LIST_WIDTH + 12 + rightWidth;
        body.setPrefWidth(bodyWidth);
        body.setMinWidth(bodyWidth);
        body.setMaxWidth(bodyWidth);
        layout.setPrefWidth(bodyWidth + DIALOG_PADDING);
        layout.setMaxWidth(bodyWidth + DIALOG_PADDING);
        layout.setBody(body);

        applyButton.getStyleClass().add("dialog-accept");
        applyButton.setOnAction(e -> {
            Item selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null)
                downloadAndPick(selected);
        });
        JFXButton cancelButton = new JFXButton("取消");
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        layout.setActions(applyButton, cancelButton);

        listView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> updatePreview());
        formGroup.selectedToggleProperty().addListener((obs, ov, nv) -> {
            if (ov != null)
                styleToggle((ToggleButton) ov, false);
            if (nv != null)
                styleToggle((ToggleButton) nv, true);
            updatePreview();
        });

        if (initialFetchEnabled) {
            fetch();
        }
    }
    /// A wrapped, width-bounded label for the list's empty state.
    ///
    /// <p>An unbounded {@code Label} is as wide as its text, and a long message would
    /// stretch the surrounding column and with it the whole dialog. Wrapping keeps the
    /// dialog at one fixed size regardless of what the message says.</p>
    private static Label placeholderLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(LIST_WIDTH - 40);
        label.setStyle("-fx-font-size: 11px;");
        return label;
    }

    /// Skips the initial fetch. Used by the layout test, which needs the controls built
    /// but must not start network work that keeps posting to the FX thread — that
    /// starved the test's own {@code runLater} calls and looked like a hang.
    boolean initialFetchEnabled = true;

    /// The width every control in the list column is pinned to. Exposed for the layout
    /// test, which checks that pinning is what keeps the dialog one size.
    double listWidthForTest() {
        return LIST_WIDTH;
    }

    /// Height of the list for this dialog, chosen from the space available. See
    /// {@link #listHeightFor(double)}.
    private double listHeight = LIST_MAX_HEIGHT;

    /// The status line's text. Package-private so the layout test can drive it
    /// without reaching into the scene graph.
    void setStatusTextForTest(String text) {
        statusLabel.setText(text);
    }

    /// Chooses the list height for a given amount of vertical space.
    ///
    /// <p>The dialog has to fit the launcher's minimum content height (492 px). Its
    /// other parts — heading, actions, canvas, status line, paddings — take roughly
    /// 250 px, so the list gets whatever is left, clamped to a usable range. Before
    /// this the height was hard-coded at 380 px, which made the dialog 524 px tall and
    /// too big for the smallest window.</p>
    ///
    /// <param name="availableHeight">the room for the whole dialog</param>
    static double listHeightFor(double availableHeight) {
        double reserved = 250;
        double room = availableHeight - reserved;
        if (room < LIST_MIN_HEIGHT) {
            return LIST_MIN_HEIGHT;
        }
        return Math.min(room, LIST_MAX_HEIGHT);
    }

    /// Empties the result list, the state a fruitless search ends in.
    void clearListForTest() {
        loading = false;
        listView.setItems(FXCollections.observableArrayList());
    }

    /// Creates one toggle of the 形态 switch: `secondForm` 标记该按钮代表的形态
    /// （皮肤=艾利克斯纤细；披风=鞘翅），`selected` 为初始选中态。
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

    /// Updates the 3D preview from the current selection and 形态 toggle.
    private void updatePreview() {
        Toggle toggle = formGroup.getSelectedToggle();
        boolean secondForm = toggle != null && Boolean.TRUE.equals(toggle.getUserData());

        Item item = listView.getSelectionModel().getSelectedItem();
        Image defaultSkin = TexturesLoader.getDefaultSkinImage();
        if (item == null) {
            previewCanvas.setModel(defaultSkin, false, null, false);
            return;
        }

        resolveTexture(item, () -> {
            Image texture = textureCache.get(item.tid);
            if (texture == null || texture.isError() || texture.getProgress() < 1.0)
                return;
            if (filter == Filter.SKIN) {
                previewCanvas.setModel(texture, secondForm, null, false);
            } else {
                if (secondForm)
                    previewCanvas.setModel(defaultSkin, false, texture, true);  // 鞘翅形态
                else
                    previewCanvas.setModel(defaultSkin, false, texture, false); // 普通披风
            }
        });
    }

    // ==== 列表数据 ====

    /// Loads the list. An empty search box browses the library page by page; a
    /// non-empty one runs a local scan instead, because the site refuses anonymous
    /// server-side search (see {@link LocalSearch}).
    private void fetch() {
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
        if (keyword.isEmpty()) {
            fetchPage(page);
        } else {
            search(keyword);
        }
    }

    private void fetchPage(int requestedPage) {
        statusLabel.setText("加载中…");
        loading = true;
        prevButton.setDisable(true);
        nextButton.setDisable(true);
        applyButton.setDisable(true);
        Lang.thread(() -> {
            try {
                Page result = requestList(requestedPage);
                if (result == null || result.data == null)
                    throw new IllegalStateException("皮肤库返回为空");
                Platform.runLater(() -> {
                    loading = false;
                    page = result.current_page;
                    hasNext = result.next_page_url != null && !result.next_page_url.isEmpty();
                    hasPrev = result.prev_page_url != null && !result.prev_page_url.isEmpty();
                    pageLabel.setText("第 " + page + " 页");
                    prevButton.setDisable(!hasPrev);
                    nextButton.setDisable(!hasNext);
                    applyButton.setDisable(false);
                    statusLabel.setText("");
                    listView.setItems(FXCollections.observableArrayList(result.data));
                    loadTextures(result.data);
                });
            } catch (Throwable e) {
                loading = false;
                String message = describeFailure(e);
                Platform.runLater(() -> {
                    prevButton.setDisable(false);
                    nextButton.setDisable(false);
                    applyButton.setDisable(false);
                    statusLabel.setText(message);
                });
            }
        }, "littleskin-list", true);
    }

    /// Scans the most popular skins and filters them by name or author.
    ///
    /// <p>Pages are fetched in parallel batches, and matches are shown as they are
    /// found rather than only at the end: a serial scan of 40 pages measured at about
    /// seventeen seconds, and even parallelised the user should not have to watch a
    /// blank list while it runs.</p>
    private void search(String keyword) {
        statusLabel.setText("正在搜索…");
        loading = true;
        prevButton.setDisable(true);
        nextButton.setDisable(true);
        applyButton.setDisable(true);
        listView.setItems(FXCollections.observableArrayList());
        Lang.thread(() -> {
            LocalSearch.ParallelSource source = LocalSearch.parallel(this::requestListForSearch);
            try {
                LocalSearch.Scan scan = LocalSearch.scan(
                        source, keyword,
                        LocalSearch.DEFAULT_MAX_PAGES, LocalSearch.DEFAULT_MAX_MATCHES,
                        page_ -> Platform.runLater(() ->
                                statusLabel.setText("正在搜索…已扫描 " + page_ + " 页")),
                        LocalSearch.DEFAULT_BATCH,
                        // Show each batch's hits at once, so results appear while the
                        // rest of the scan is still running.
                        partial -> Platform.runLater(() -> {
                            if (loading) {
                                listView.setItems(FXCollections.observableArrayList(partial));
                                loadTextures(partial);
                            }
                        }));
                String summary = scan.describe(keyword);
                Platform.runLater(() -> {
                    loading = false;
                    // Paging does not apply to a local scan, so it stays disabled; the
                    // label says why. Clearing the box restores browsing.
                    hasNext = false;
                    hasPrev = false;
                    prevButton.setDisable(true);
                    nextButton.setDisable(true);
                    pageLabel.setText("搜索结果");
                    applyButton.setDisable(false);
                    statusLabel.setText(summary);
                    listView.setItems(FXCollections.observableArrayList(scan.matches()));
                    if (scan.matches().isEmpty()) {
                        listView.setPlaceholder(placeholderLabel(summary));
                    } else {
                        listView.setPlaceholder(placeholderLabel("没有找到皮肤"));
                        loadTextures(scan.matches());
                    }
                });
            } catch (Throwable e) {
                loading = false;
                String message = describeFailure(e);
                Platform.runLater(() -> {
                    prevButton.setDisable(false);
                    nextButton.setDisable(false);
                    applyButton.setDisable(false);
                    statusLabel.setText(message);
                });
            } finally {
                // The pool is per-search, so it must not leak.
                source.close();
            }
        }, "littleskin-search", true);
    }

    /// The vertical room for a dialog in the launcher's current window.
    ///
    /// <p>Read from the live window so the dialog adapts to the launcher's size instead
    /// of assuming a large one. Falls back to the launcher's minimum content height when
    /// the window cannot be read, which is the safe direction: a dialog that is too
    /// small still fits, one that is too big does not.</p>
    private static double availableContentHeight() {
        try {
            javafx.stage.Stage stage = Controllers.getStage();
            if (stage == null || stage.getHeight() <= 0) {
                return MIN_CONTENT_HEIGHT;
            }
            // The stage includes the decoration shadow the launcher adds on each side.
            return Math.max(MIN_CONTENT_HEIGHT, stage.getHeight() - 2 * DECORATION_SHADOW);
        } catch (Throwable t) {
            return MIN_CONTENT_HEIGHT;
        }
    }

    /// The launcher's minimum content height, from {@code Controllers.MIN_CONTENT_HEIGHT}.
    private static final double MIN_CONTENT_HEIGHT = 492;

    /// The launcher's custom decoration shadow, from
    /// {@code Controllers.CUSTOM_DECORATION_SHADOW_SIZE}.
    private static final double DECORATION_SHADOW = 8;

    /// One page in the given order: {@code "upload_at"} for browsing (newest first),
    /// {@code "likes"} for the local search (most popular first, which is what makes
    /// a bounded scan find skins people actually know).
    ///
    /// <p>The {@code keyword} parameter is deliberately <b>never</b> sent: LittleSkin
    /// answers a non-empty one with HTTP 403 for anonymous clients, which would turn
    /// every search into a failure. Searching is done locally instead — see
    /// {@link LocalSearch}.</p>
    private Page requestList(int requestedPage, String sort) throws Exception {
        String url = LIBRARY_BASE + "/skinlib/list?filter=" + filter.value
                + "&sort=" + sort
                + "&page=" + requestedPage;
        String json = HttpRequest.GET(url).accept("application/json").getString();
        return JsonUtils.fromJson(json, Page.class);
    }

    /// A page for browsing, newest first.
    private Page requestList(int requestedPage) throws Exception {
        return requestList(requestedPage, SORT_NEWEST);
    }

    /// A page for the local search: most popular first.
    private Page requestListForSearch(int requestedPage) throws Exception {
        return requestList(requestedPage, SORT_POPULAR);
    }

    /// Server-side orders. These are the only two the API accepts; anything else
    /// answers HTTP 500.
    private static final String SORT_NEWEST = "upload_at";
    private static final String SORT_POPULAR = "likes";

    /// Turns a failed request into something the user can act on.
    ///
    /// <p>Searching never reaches the server, so the only failures here are genuine
    /// outages. The launcher's {@code ResponseCodeException} embeds the whole URL and
    /// response body in its message, which is not something to show in a status
    /// label, so it is translated.</p>
    static String describeFailure(Throwable error) {
        String raw = error == null ? "" : String.valueOf(error.getMessage());
        int code = responseCodeOf(error);
        if (code == 403) {
            return "皮肤站拒绝了这次请求（HTTP 403），请稍后再试。";
        }
        if (code > 0) {
            return "加载失败：皮肤站返回 HTTP " + code + "。";
        }
        // Offline, DNS, TLS, timeout… the raw cause is short and useful here.
        String short_ = raw.length() > 160 ? raw.substring(0, 160) + "…" : raw;
        return "加载失败：" + (short_.isEmpty() ? error : short_);
    }

    /// The HTTP status behind an exception, or 0 when it was not an HTTP failure.
    private static int responseCodeOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.jackhuang.hmcl.util.io.ResponseCodeException response) {
                return response.getResponseCode();
            }
        }
        return 0;
    }

    /// Resolves the texture hash of each item in the background and starts the
    /// asynchronous image loading, so thumbnails pop in one by one.
    private void loadTextures(List<Item> items) {
        List<Item> pending = List.copyOf(items);
        Lang.thread(() -> {
            for (Item item : pending) {
                if (item.hash == null || item.hash.length() != 64) {
                    try {
                        String json = HttpRequest.GET(LIBRARY_BASE + "/skinlib/info/" + item.tid)
                                .accept("application/json").getString();
                        Info info = JsonUtils.fromJson(json, Info.class);
                        if (info == null || info.hash == null)
                            continue;
                        item.hash = info.hash;
                    } catch (Throwable e) {
                        continue; // 缩略图失败不影响列表使用；应用时会重试
                    }
                }
                startTextureLoad(item, null);
            }
        }, "littleskin-thumbs", true);
    }

    /// Starts the background loading of one texture image; re-renders the list and
    /// invokes `onReady` (on the FX thread) when the image is available.
    private void startTextureLoad(Item item, Runnable onReady) {
        if (item.hash == null)
            return;
        Image cached = textureCache.get(item.tid);
        if (cached != null) {
            if (onReady != null)
                whenImageReady(cached, onReady);
            return;
        }
        Image image = new Image(TEXTURES_BASE + item.hash, true);
        textureCache.put(item.tid, image);
        image.progressProperty().addListener((obs, ov, nv) -> {
            if (nv.doubleValue() >= 1.0) {
                listView.refresh();
                if (onReady != null)
                    onReady.run();
            }
        });
    }

    /// Ensures the texture hash of the item is known, then starts the image load.
    private void resolveTexture(Item item, Runnable onReady) {
        if (item.hash != null && item.hash.length() == 64) {
            startTextureLoad(item, onReady);
            return;
        }
        Lang.thread(() -> {
            try {
                String json = HttpRequest.GET(LIBRARY_BASE + "/skinlib/info/" + item.tid)
                        .accept("application/json").getString();
                Info info = JsonUtils.fromJson(json, Info.class);
                if (info == null || info.hash == null)
                    throw new IllegalStateException("获取材质信息失败");
                item.hash = info.hash;
                Platform.runLater(() -> startTextureLoad(item, onReady));
            } catch (Throwable e) {
                Platform.runLater(() -> statusLabel.setText("获取材质信息失败：" + e.getMessage()));
            }
        }, "littleskin-info", true);
    }

    private static void whenImageReady(Image image, Runnable action) {
        Runnable guarded = () -> {
            if (!image.isError() && image.getProgress() >= 1.0)
                action.run();
        };
        if (image.getProgress() >= 1.0 && Platform.isFxApplicationThread()) {
            guarded.run();
        } else {
            image.progressProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() >= 1.0)
                    action.run();
            });
        }
    }

    private void downloadAndPick(Item item) {
        statusLabel.setText("正在下载材质…");
        applyButton.setDisable(true);
        Path target = cacheDir.resolve("skinlib-" + item.tid + ".png");
        Lang.thread(() -> {
            try {
                String hash = item.hash;
                if (hash == null || hash.length() != 64) {
                    String json = HttpRequest.GET(LIBRARY_BASE + "/skinlib/info/" + item.tid)
                            .accept("application/json").getString();
                    Info info = JsonUtils.fromJson(json, Info.class);
                    if (info == null || info.hash == null)
                        throw new IllegalStateException("获取材质信息失败");
                    hash = info.hash;
                }
                Files.createDirectories(cacheDir);
                new FileDownloadTask(TEXTURES_BASE + hash, target).run();
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    applyButton.setDisable(false);
                    fireEvent(new DialogCloseEvent());
                    onPicked.accept(target);
                });
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    statusLabel.setText("下载失败：" + e.getMessage());
                    applyButton.setDisable(false);
                });
            }
        }, "littleskin-download", true);
    }

    /// List cell with a texture thumbnail and two-line text; the selected cell is
    /// highlighted with a blue rounded border around the whole row.
    private final class ItemCell extends ListCell<Item> {
        private final Canvas thumbnail = new Canvas(40, 40);
        private final Label titleLabel = new Label();
        private final Label subtitleLabel = new Label();
        private final HBox box;

        public ItemCell() {
            titleLabel.getStyleClass().add("title");
            subtitleLabel.getStyleClass().add("subtitle");
            subtitleLabel.setStyle("-fx-text-fill: -monet-on-surface-variant; -fx-font-size: 11px;");
            VBox text = new VBox(2, titleLabel, subtitleLabel);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(10, thumbnail, text);
            box.setAlignment(Pos.CENTER_LEFT);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPrefHeight(56);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setStyle(UNSELECTED_STYLE);
                return;
            }

            titleLabel.setText(item.name + (item.hd ? "  (HD)" : ""));
            String model;
            switch (item.type == null ? "" : item.type) {
                case "steve": model = "宽臂"; break;
                case "alex": model = "纤细"; break;
                case "cape": model = "披风"; break;
                default: model = String.valueOf(item.type);
            }
            subtitleLabel.setText(model
                    + (item.nickname != null ? " · by " + item.nickname : ""));

            Image image = textureCache.get(item.tid);
            drawThumbnail(thumbnail, image, item.type);
            setGraphic(box);
            setStyle(isSelected() ? SELECTED_STYLE : UNSELECTED_STYLE);
        }

        private void drawThumbnail(Canvas canvas, Image image, String type) {
            var g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            if (image == null || image.isError() || image.getProgress() < 1.0 || image.getWidth() == 0)
                return;

            g.setImageSmoothing(false);
            if ("cape".equals(type)) {
                // 披风正面区域约占 (1,1)-(11,17)，按原图比例缩放绘制
                double scale = image.getWidth() / 64.0;
                double w = 10 * scale, h = 16 * scale;
                double fit = Math.min((canvas.getWidth() - 4) / w, (canvas.getHeight() - 4) / h);
                g.drawImage(image, scale, scale, w, h,
                        (canvas.getWidth() - w * fit) / 2, (canvas.getHeight() - h * fit) / 2,
                        w * fit, h * fit);
            } else {
                // 皮肤：绘制头部正面（含帽层）
                TexturesLoader.drawAvatar(canvas, image);
            }
        }
    }
}
