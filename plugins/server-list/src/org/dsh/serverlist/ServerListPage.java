package org.dsh.serverlist;

import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.dsh.serverlist.ListUi;
import org.dsh.serverlist.ServerListPlugin;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// A browsable list of Minecraft servers: live player counts and latency from the real
/// status protocol, version and game-type tags, search and category filters, and a detail
/// view behind every card. The catalog is bundled; the user can add their own servers and
/// they persist in the plugin data directory.
public final class ServerListPage extends VBox implements DecoratorPage {

    private static final String[] FILTERS = {
            "全部分类", "小游戏", "生存", "空岛", "冒险", "建造", "PvP", "综合"
    };

    private final Path dataDirectory;
    private final ExecutorService pingPool = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "server-list-ping");
        thread.setDaemon(true);
        return thread;
    });

    /// The area the page swaps between list / detail / form / directory.
    ///
    /// <p>A [TransitionPane] rather than a plain VBox: switching views then plays the
    /// launcher's own transition (Material 3 "forward" / "backward"), so opening
    /// 「发现更多服务器」 slides instead of snapping. The pane replaces its content node, so
    /// every view handed to [ServerListPage#showView] is wrapped in a container of its own.</p>
    private final TransitionPane contentView = new TransitionPane();
    private final VBox listContainer = new VBox(8);
    private final Label statusLabel = new Label(" ");
    private final JFXTextField searchField = new JFXTextField();
    private final JFXComboBox<String> categoryBox = new JFXComboBox<>();

    /// True once the launcher's scrolling pane has been found and configured.
    ///
    /// <p>Guards against re-running the lookup on every layout pass.</p>
    private boolean smoothScrollingEnabled;

    /// The list header, built once and reused.
    ///
    /// <p>It must not be rebuilt per navigation: building it registers listeners on
    /// [searchField] and [categoryBox], so rebuilding accumulated one more listener each time
    /// the user returned to the list — after a few round trips a single keystroke fired several
    /// refreshes and several full server-ping sweeps.</p>
    private VBox listHeader;
    private final Map<String, ServerPinger.Status> statuses = new ConcurrentHashMap<>();
    private final Map<String, Image> iconCache = new ConcurrentHashMap<>();
    private final Map<String, CardRefs> cards = new ConcurrentHashMap<>();
    private List<ServerCatalog.Entry> entries = List.of();
    private int pending = 0;
    private int online = 0;

    // ---- mcstat.org 在线目录（发现更多服务器）----
    private String mcstatKey = "";
    private int remotePage = 1;
    private int remoteTotalPages = 1;
    private int remoteTotal = 0;
    private final VBox remoteResults = new VBox(8);
    private final Label remoteStatus = new Label(" ");
    private final Label remotePageInfo = new Label(" ");
    private JFXTextField remoteSearchField;
    private JFXComboBox<String> remoteSortBox;
    private JFXComboBox<String> remoteTagBox;
    private javafx.scene.control.Button remotePrev;
    private javafx.scene.control.Button remoteNext;
    private JFXTextField mcstatKeyField;
    private HBox mcstatKeyBox;

    /// A card's mutable labels, so the ping results can update it in place.
    private record CardRefs(Label status, Label version, ImageView icon) {
    }

    /// 返回“服务器”模块选择页；由模块页传入，作为本页顶层的唯一返回按钮。
    private final Runnable backToModule;

    /// 当前是否停在本页的某个子视图（详情 / 添加表单 / 在线目录）上。
    ///
    /// <p>由 [ServerListPage#showView] 统一维护：向更深一层导航时置 {@code true}，回到列表时置
    /// {@code false}。窗口左上角返回箭头据此判断“先退回列表”还是“退回汇聚页”。</p>
    private boolean onSubView;

    /// 「退一层」的动作，与当前子视图一一对应（详情/表单 → 列表，在线目录详情 → 目录）。
    ///
    /// <p>页内不再画返回按钮，唯一可用的返回就是窗口左上角那个箭头（和 ESC），
    /// 它除了要知道“还有没有上一层”，还得知道“上一层是谁”。在线目录下面的服务器详情
    /// 就是这种情况：退回的应当是目录，而不是直接跳到已保存的服务器列表。</p>
    private Runnable subViewBack;

    /// 供汇聚页读取的页面状态。本页不是导航栈上的一页（栈上是 PluginPageProxy），
    /// 因此这里只需满足接口契约，标题与是否可返回由汇聚页自己的标题栏决定。
    private final ReadOnlyObjectWrapper<DecoratorPage.State> pageState =
            new ReadOnlyObjectWrapper<>(DecoratorPage.State.fromTitle("服务器列表"));

    public ServerListPage(Path dataDirectory) {
        this(dataDirectory, null);
    }

    public ServerListPage(Path dataDirectory, Runnable backToModule) {
        this.dataDirectory = dataDirectory;
        this.backToModule = backToModule;
        setSpacing(10);
        setPadding(new Insets(10));

        getChildren().add(contentView);
        showView(buildListView(), false);
        enableSmoothScrollingWhenShown();
        refresh();
    }

    /// 窗口左上角返回箭头的处理逻辑。
    ///
    /// <h2>为什么要覆写</h2>
    /// <p>标题栏的箭头属于启动器自己的导航，默认行为是弹出整个导航栈：即使本页是从
    /// 「服务器」汇聚页点进来的，也会被一路退出到主界面。汇聚页把它收到的返回请求转发给
    /// 本页（见 `ServerHubPage#handleBack`），于是这里只需回答“这一步我自己消化了没有”。</p>
    ///
    /// <p>返回值语义与 [org.jackhuang.hmcl.ui.decorator.DecoratorController] 保持一致：
    /// {@code false} 表示**已由本页处理**、不要再弹栈；{@code true} 表示**交给启动器弹栈**。</p>
    /// <ol>
    ///   <li>正停在某个子视图（详情 / 添加表单 / 在线目录 / 目录里的服务器详情）→ 退一层，
    ///       返回 {@code false}；页内没有自己的返回按钮，这一步是唯一的返回路径；</li>
    ///   <li>已在该列表顶层且由汇聚页托管 → 回到汇聚页，返回 {@code false}；</li>
    ///   <li>作为独立侧边栏入口（没有汇聚页）→ 返回 {@code true}，让启动器正常退出本功能。</li>
    /// </ol>
    @Override
    public boolean back() {
        if (onSubView) {
            Runnable step = subViewBack;
            if (step != null) {
                // 先清空再执行：退一层的那次 showView 会重新登记新的「上一层」，
                // 否则会残留上上个视图的动作，连按两次会跳错地方。
                subViewBack = null;
                step.run();
            } else {
                backToList();
            }
            return false;
        }
        if (backToModule != null) {
            backToModule.run();
            return false;
        }
        return true;
    }

    @Override
    public ReadOnlyObjectProperty<DecoratorPage.State> stateProperty() {
        return pageState.getReadOnlyProperty();
    }

    /// Switches the content area to one view, with the launcher's transition.
    ///
    /// <p>Every navigation in this page goes through here. Each view is wrapped in its own
    /// [VBox] because [TransitionPane] replaces a single node: without the wrapper the first
    /// view's children would be handed over as separate nodes and the transition would have
    /// nothing coherent to animate.</p>
    ///
    /// <p>Direction follows the navigation's sense — going deeper uses the forward animation,
    /// coming back uses its opposite — so 「发现更多服务器」 and the back button do not both
    /// slide the same way.</p>
    ///
    /// @param view   the view to display
    /// @param forward true for navigating deeper, false for returning
    private void showView(Node view, boolean forward) {
        onSubView = forward;
        VBox wrapper = new VBox(view);
        wrapper.setPadding(Insets.EMPTY);
        contentView.setContent(wrapper, forward
                ? ContainerAnimations.FORWARD
                : ContainerAnimations.BACKWARD);
    }

    /// The main list view: header plus the card column.
    private Node buildListView() {
        VBox view = new VBox(10);
        view.getChildren().add(buildListHeader());
        view.getChildren().add(listContainer);
        return view;
    }

    /// Turns on the launcher's animated scrolling for the page.
    ///
    /// <p>This page does not own a scroll pane: the launcher shell scrolls it, and that pane
    /// only becomes an ancestor after the page is attached to the scene. So the lookup runs on
    /// the first layout pass rather than in the constructor, where the page has no parent yet.
    /// The flag keeps it to one attempt per attachment.</p>
    private void enableSmoothScrollingWhenShown() {
        Runnable enable = () -> {
            if (smoothScrollingEnabled)
                return;
            javafx.scene.control.ScrollPane ancestor = ancestorScrollPane(this);
            if (ancestor == null)
                return;   // not attached yet; the next layout pass tries again
            smoothScrollingEnabled = true;
            try {
                org.jackhuang.hmcl.ui.FXUtils.smoothScrolling(ancestor);
            } catch (Throwable ignored) {
                // Smooth scrolling is cosmetic: a launcher that cannot provide it (an old
                // version, or animation disabled) still scrolls, just instantly.
            }
        };
        // parentProperty fires when the shell mounts this page; a zero-delay pulse covers the
        // case where it was already mounted before the listener was added.
        parentProperty().addListener((observable, oldParent, newParent) -> {
            if (newParent != null)
                javafx.application.Platform.runLater(enable);
        });
        javafx.application.Platform.runLater(enable);
    }

    /// The nearest ancestor scroll pane, or null when the page is not attached yet.
    ///
    /// @param node the node to start from
    private static javafx.scene.control.ScrollPane ancestorScrollPane(javafx.scene.Node node) {
        for (javafx.scene.Node current = node.getParent();
             current != null; current = current.getParent()) {
            if (current instanceof javafx.scene.control.ScrollPane pane)
                return pane;
        }
        return null;
    }

    // ------------------------------------------------------------ list view ---

    private VBox buildListHeader() {
        if (listHeader != null)
            return listHeader;

        VBox header = new VBox(10);

        searchField.setPromptText("搜索服务器名称或地址，例如 Hypixel / mc.earthmc.net");
        searchField.setOnAction(e -> refresh());
        searchField.textProperty().addListener((o, oldText, newText) -> refresh());

        categoryBox.getItems().setAll(FILTERS);
        categoryBox.getSelectionModel().selectFirst();
        categoryBox.setOnAction(e -> refresh());

        // 标题左侧不放箭头：本页（以及整个「服务器」模块）都不再画页内返回按钮，
        // 返回只由窗口左上角的原生箭头承担，这里再放一个只会重复。
        header.getChildren().add(ListUi.heading("服务器列表",
                "在线人数、延迟与版本由启动器直接向服务器发起状态查询获得，每分钟自动刷新一次。"));

        header.getChildren().add(ListUi.inputRow("搜索", "按名称或地址过滤", searchField));
        header.getChildren().add(ListUi.entryRow(null, "分类",
                "生存、小游戏、空岛……按玩法筛选", categoryBox));

        ComponentList actionList = new ComponentList();
        LineButton refreshButton = new LineButton();
        refreshButton.setTitle("刷新状态");
        refreshButton.setSubtitle("重新查询所有服务器的在线人数与延迟");
        refreshButton.setOnAction(e -> refresh());

        LineButton addButton = new LineButton();
        addButton.setTitle("添加服务器");
        addButton.setSubtitle("把你喜欢（或自己开的）服务器加入列表，永久保存");
        addButton.setOnAction(e -> showAddForm());

        LineButton discoverButton = new LineButton();
        discoverButton.setTitle("发现更多服务器");
        discoverButton.setSubtitle("连接 mcstat.org 在线目录，按人数/热度浏览 6000+ 社区服务器");
        discoverButton.setOnAction(e -> showRemote());

        actionList.getContent().setAll(refreshButton, addButton, discoverButton);
        header.getChildren().add(actionList);

        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        statusLabel.setWrapText(true);
        header.getChildren().add(statusLabel);

        listHeader = header;
        return header;
    }

    @Override
    public void refresh() {
        loadEntries();
        renderList();
        pingAll();
    }

    private void loadEntries() {
        List<ServerCatalog.Entry> merged = new ArrayList<>(ServerCatalog.bundled());
        merged.addAll(ServerCatalog.custom(dataDirectory));
        entries = merged;
    }

    private List<ServerCatalog.Entry> filtered() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String category = categoryBox.getSelectionModel().getSelectedIndex() <= 0 ? "" : categoryBox.getValue();
        List<ServerCatalog.Entry> result = new ArrayList<>();
        for (ServerCatalog.Entry entry : entries) {
            if (!query.isEmpty()
                    && !entry.name().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !entry.address().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !entry.description().toLowerCase(java.util.Locale.ROOT).contains(query))
                continue;
            if (!category.isEmpty() && !entry.categories().contains(category))
                continue;
            result.add(entry);
        }
        return result;
    }

    private void renderList() {
        cards.clear();
        listContainer.getChildren().clear();
        List<ServerCatalog.Entry> visible = filtered();
        int onlineCount = 0;
        for (ServerCatalog.Entry entry : visible) {
            ServerPinger.Status known = statuses.get(entry.address());
            if (known != null)
                onlineCount++;
            Node card = createCard(entry, known);
            listContainer.getChildren().add(card);
        }
        updateStatusLabel(visible.size(), onlineCount);
    }

    private void updateStatusLabel(int shown, int onlineCount) {
        int querying = pending;
        String text = "共 " + shown + " 个服务器 · 在线 " + onlineCount;
        if (querying > 0)
            text += " · 正在查询 " + querying + " 个…";
        statusLabel.setText(text);
    }

    private Node createCard(ServerCatalog.Entry entry, ServerPinger.Status status) {
        ImageView icon = createIconView(entry, 56);
        Label name = new Label(entry.name());
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;"
                + " -fx-text-fill: -monet-on-surface;");

        Label version = new Label(" ");
        version.setStyle("-fx-font-size: 10px; -fx-background-color: rgba(128,128,128,0.15);"
                + " -fx-background-radius: 4; -fx-padding: 1 6 1 6; -fx-text-fill: -fx-mid-text-color;");

        Label category = new Label(String.join(" / ", entry.categories()));
        category.setStyle("-fx-font-size: 10px; -fx-background-color: rgba(33,150,243,0.15);"
                + " -fx-background-radius: 4; -fx-padding: 1 6 1 6; -fx-text-fill: #1e88e5;");

        HBox tags = new HBox(6, version, category);
        tags.setAlignment(Pos.CENTER_LEFT);

        Label statusLine = new Label(" ");
        statusLine.setStyle("-fx-font-size: 12px;");
        statusLine.setPadding(new Insets(2, 0, 0, 0));

        Label description = new Label(entry.description());
        description.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        description.setWrapText(false);
        description.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        VBox text = new VBox(3, tags, name, statusLine, description);
        HBox.setHgrow(text, Priority.ALWAYS);

        // 卡片主体是 JFXButton（FLAT）：点按时的波纹与启动器原生按钮完全一致
        com.jfoenix.controls.JFXButton cardButton = new com.jfoenix.controls.JFXButton();
        cardButton.setButtonType(com.jfoenix.controls.JFXButton.ButtonType.FLAT);
        HBox cardContent = new HBox(12, icon, text);
        cardContent.setAlignment(Pos.CENTER_LEFT);
        cardButton.setGraphic(cardContent);
        cardButton.setStyle("-fx-background-color: -monet-surface-container; -fx-background-radius: 8;"
                + " -fx-padding: 8 12 8 12;");
        cardButton.setMaxWidth(Double.MAX_VALUE);
        cardButton.setAlignment(Pos.CENTER_LEFT);
        cardButton.setOnAction(e -> showDetail(entry));

        HBox card = new HBox(10, cardButton, enterGameButton(entry.address(), entry.name()));
        card.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cardButton, Priority.ALWAYS);

        cards.put(entry.address(), new CardRefs(statusLine, version, icon));
        applyStatus(entry, status);
        return card;
    }

    private void applyStatus(ServerCatalog.Entry entry, ServerPinger.Status status) {
        CardRefs refs = cards.get(entry.address());
        if (refs == null)
            return;
        if (status == null) {
            refs.status().setText("● 正在查询…");
            refs.status().setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-mid-text-color;");
            return;
        }
        String latencyColor = status.latencyMs() < 100 ? "#1a9b4b"
                : status.latencyMs() < 300 ? "#b26a00" : "#c62828";
        refs.status().setText("● 在线 " + status.playersOnline() + " / " + status.playersMax()
                + "　·　延迟 " + status.latencyMs() + " ms");
        refs.status().setStyle("-fx-font-size: 12px; -fx-text-fill: " + latencyColor + ";");
        String version = status.version();
        if (version.length() > 18)
            version = version.substring(0, 18) + "…";
        refs.version().setText(version);
        if (status.iconBase64() != null) {
            Image image = decodeIcon(entry.address(), status.iconBase64());
            if (image != null)
                refs.icon().setImage(image);
        }
    }

    private Image decodeIcon(String address, String base64) {
        return iconCache.computeIfAbsent(address, key -> {
            try {
                String data = base64.startsWith("data:image") && base64.contains(",")
                        ? base64.substring(base64.indexOf(',') + 1) : base64;
                byte[] bytes = java.util.Base64.getDecoder().decode(data);
                return new Image(new ByteArrayInputStream(bytes), 56, 56, true, true);
            } catch (Exception e) {
                return null;
            }
        });
    }

    /// Loads a remote (mcstat.org) icon: URL first; when that fails — the URL is dead,
    /// relative, or key-protected — fall back to the server's real favicon from the status
    /// protocol, so cards never sit permanently empty.
    private void loadRemoteIcon(ImageView view, String address, String iconUrl) {
        if (iconUrl != null && !iconUrl.isBlank()) {
            String url = iconUrl;
            if (!url.startsWith("http"))
                url = "https://mcstat.org" + (url.startsWith("/") ? "" : "/") + url;
            Image image = new Image(url, 48, 48, true, true, true);
            image.errorProperty().addListener((o, was, now) -> {
                if (now)
                    fallbackFavicon(view, address);
            });
            view.setImage(image);
            if (image.isError())
                fallbackFavicon(view, address);
            return;
        }
        fallbackFavicon(view, address);
    }

    /// Fills the view with the server's own favicon, fetched by a real status ping.
    private void fallbackFavicon(ImageView view, String address) {
        pingPool.execute(() -> {
            try {
                ServerPinger.Status status = ServerPinger.ping(address, defaultPort(address), 5000);
                if (status.iconBase64() == null)
                    return;
                String data = status.iconBase64().contains(",")
                        ? status.iconBase64().substring(status.iconBase64().indexOf(',') + 1)
                        : status.iconBase64();
                byte[] bytes = java.util.Base64.getDecoder().decode(data);
                Platform.runLater(() -> {
                    try {
                        view.setImage(new Image(new ByteArrayInputStream(bytes)));
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
                // the server is simply unreachable; the placeholder stays
            }
        });
    }

    /// 「进入游戏」：选择版本并以 QuickPlay 方式直连该服务器。
    private Node enterGameButton(String address, String name) {
        javafx.scene.control.Button button = FXUtils.newRaisedButton("进入游戏");
        button.setStyle("-fx-font-weight: bold;");
        button.setMinWidth(Region.USE_PREF_SIZE);
        // 吞掉鼠标事件，避免点击按钮的同时又触发卡片本身的“打开详情”
        button.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.scene.input.MouseEvent::consume);
        button.setOnAction(e -> ServerLauncher.chooseAndLaunch(this, address, name));
        return button;
    }

    private ImageView createIconView(ServerCatalog.Entry entry, int size) {
        ImageView view = new ImageView();
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Image cached = iconCache.get(entry.address());
        if (cached != null)
            view.setImage(cached);
        return view;
    }

    private void pingAll() {
        pending = 0;
        for (ServerCatalog.Entry entry : filtered()) {
            pending++;
            applyStatus(entry, null);
            pingPool.execute(() -> {
                try {
                    ServerPinger.Status status = ServerPinger.ping(entry.address(), defaultPort(entry.address()), 5000);
                    statuses.put(entry.address(), status);
                    Platform.runLater(() -> {
                        pending = Math.max(0, pending - 1);
                        applyStatus(entry, status);
                        updateStatusLabel(filtered().size(), countOnline());
                    });
                } catch (Exception e) {
                    statuses.remove(entry.address());
                    Platform.runLater(() -> {
                        pending = Math.max(0, pending - 1);
                        CardRefs refs = cards.get(entry.address());
                        if (refs != null) {
                            refs.status().setText("● 无法连接（服务器离线或网络不可达）");
                            refs.status().setStyle("-fx-font-size: 12px; -fx-text-fill: #c62828;");
                        }
                        updateStatusLabel(filtered().size(), countOnline());
                    });
                }
            });
        }
        updateStatusLabel(filtered().size(), countOnline());
    }

    private int countOnline() {
        int count = 0;
        for (ServerCatalog.Entry entry : filtered())
            if (statuses.get(entry.address()) != null)
                count++;
        return count;
    }

    private static int defaultPort(String address) {
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1) {
            try {
                return Integer.parseInt(address.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 25565;
    }

    // ---------------------------------------------------------- detail view ---

    /// 详情页头部：小图标在名称左侧——内置目录与 mcstat 目录共用这一个方法，
    /// 尺寸、间距、样式保证完全一致。图标本身不带任何边框或底色。
    private Node detailHeader(ImageView icon, Label name, HBox tags, Label statusLine) {
        HBox iconBox = new HBox(icon);
        iconBox.setAlignment(Pos.CENTER);
        iconBox.setPrefSize(56, 56);
        HBox header = new HBox(14, iconBox, new VBox(6, name, tags, statusLine));
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private void showDetail(ServerCatalog.Entry entry) {
        ServerPinger.Status status = statuses.get(entry.address());

        ImageView coverIcon = createIconView(entry, 56);

        Label name = new Label(entry.name());
        name.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        HBox tags = new HBox(8);
        tags.setAlignment(Pos.CENTER_LEFT);
        if (status != null) {
            Label version = new Label(status.version());
            version.setStyle("-fx-font-size: 11px; -fx-background-color: rgba(128,128,128,0.15);"
                    + " -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
            tags.getChildren().add(version);
        }
        for (String category : entry.categories()) {
            Label chip = new Label(category);
            chip.setStyle("-fx-font-size: 11px; -fx-background-color: rgba(33,150,243,0.15);"
                    + " -fx-background-radius: 4; -fx-padding: 2 8 2 8; -fx-text-fill: #1e88e5;");
            tags.getChildren().add(chip);
        }

        Label statusLine = new Label(status == null
                ? "● 当前无法连接（服务器离线、网络不可达或版本过旧）"
                : "● 在线 " + status.playersOnline() + " / " + status.playersMax()
                + " 人　·　连接延迟 " + status.latencyMs() + " ms");
        statusLine.setStyle("-fx-font-size: 13px; -fx-text-fill: "
                + (status == null ? "#c62828" : "#1a9b4b") + ";");
        statusLine.setPadding(new Insets(4, 0, 4, 0));

        Label description = new Label(entry.description());
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 13px;");

        Label addressLine = new Label("服务器地址：" + entry.address());
        addressLine.setStyle("-fx-font-size: 12px; -fx-font-family: 'Consolas','Courier New',monospace;");

        javafx.scene.control.Button copyButton = FXUtils.newRaisedButton("复制地址");
        copyButton.setMinWidth(Region.USE_PREF_SIZE);
        copyButton.setOnAction(e -> FXUtils.copyText(entry.address()));

        HBox actions = new HBox(10, enterGameButton(entry.address(), entry.name()), copyButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (entry.website() != null && !entry.website().isBlank()) {
            javafx.scene.control.Button siteButton = FXUtils.newRaisedButton("打开服务器网站");
            siteButton.setMinWidth(Region.USE_PREF_SIZE);
            siteButton.setOnAction(e -> FXUtils.openLink(entry.website()));
            actions.getChildren().add(siteButton);
        }
        if (entry.custom()) {
            javafx.scene.control.Button deleteButton = FXUtils.newRaisedButton("删除");
            deleteButton.setMinWidth(Region.USE_PREF_SIZE);
            deleteButton.setOnAction(e -> {
                List<ServerCatalog.Entry> custom = new ArrayList<>(ServerCatalog.custom(dataDirectory));
                custom.removeIf(existing -> Objects.equals(existing.address(), entry.address()));
                ServerCatalog.saveCustom(dataDirectory, custom);
                backToList();
            });
            actions.getChildren().add(deleteButton);
        }

        Label tip = new Label("把上方地址复制后，在游戏的多人游戏 → 添加服务器中粘贴即可连接。");
        tip.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        tip.setWrapText(true);

        VBox detail = new VBox(10,
                detailHeader(coverIcon, name, tags, statusLine),
                wrap(addressLine, copyButton),
                description, actions, tip);
        detail.setPadding(new Insets(10));

        showView(detail, true);
        subViewBack = this::backToList;
    }

    private HBox wrap(Node left, Node button) {
        HBox box = new HBox(10, left, button);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void backToList() {
        subViewBack = null;
        refresh();
        showView(buildListView(), false);
    }

    // ------------------------------------------------------------ add form ---

    private void showAddForm() {
        JFXTextField nameField = new JFXTextField();
        nameField.setPromptText("例如：我朋友的生存服");
        JFXTextField addressField = new JFXTextField();
        addressField.setPromptText("mc.example.com:25565");
        JFXComboBox<String> categoryField = new JFXComboBox<>();
        categoryField.getItems().setAll("生存", "小游戏", "空岛", "综合", "建造", "PvP", "冒险");
        categoryField.getSelectionModel().selectFirst();
        JFXTextField descriptionField = new JFXTextField();
        descriptionField.setPromptText("一句话介绍（可选）");

        VBox form = new VBox(10,
                ListUi.heading("添加服务器",
                        "把你自己的服务器加入列表，永久保存在本机；之后可以随时查看在线人数与延迟。"),
                ListUi.inputRow("名称", "显示在列表里的名字", nameField),
                ListUi.inputRow("地址", "服务器的 IP 或域名，可用冒号指定端口", addressField),
                ListUi.entryRow(null, "分类", "用于筛选", categoryField),
                ListUi.inputRow("简介", "可选", descriptionField));

        javafx.scene.control.Button saveButton = FXUtils.newRaisedButton("保存");
        saveButton.setMinWidth(Region.USE_PREF_SIZE);
        saveButton.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String address = addressField.getText() == null ? "" : addressField.getText().trim();
            if (name.isEmpty() || address.isEmpty()) {
                ServerListPlugin.error("添加服务器", "名称和地址都不能为空。");
                return;
            }
            List<ServerCatalog.Entry> custom = new ArrayList<>(ServerCatalog.custom(dataDirectory));
            custom.add(new ServerCatalog.Entry(name, address,
                    List.of(categoryField.getValue() == null ? "综合" : categoryField.getValue()),
                    descriptionField.getText() == null ? "" : descriptionField.getText().trim(),
                    null, true));
            ServerCatalog.saveCustom(dataDirectory, custom);
            ServerListPlugin.log("添加自定义服务器 " + name + "（" + address + "）");
            backToList();
        });

        // 回车直接保存，符合“填完就提交”的直觉
        nameField.setOnAction(e -> saveButton.fire());
        addressField.setOnAction(e -> saveButton.fire());
        descriptionField.setOnAction(e -> saveButton.fire());

        HBox saveRow = new HBox(saveButton);
        saveRow.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().add(saveRow);

        form.setPadding(new Insets(10));

        // 关键：把表单放进内容区。以前这里只是构建了 form 却没有挂到 contentView 上，
        // 于是点「添加服务器」什么都不会发生——表单被丢在内存里没人显示。
        showView(form, true);
        subViewBack = this::backToList;
    }

    // ------------------------------------------------- mcstat.org 在线目录 ---

    /// 在线目录浏览器：连接 mcstat.org 浏览 6000+ 社区服务器。
    /// 首次使用需要在 mcstat.org 免费申请 API Key 并粘贴一次（保存在本机）。
    private void showRemote() {
        mcstatKey = McStatClient.loadKey(dataDirectory);

        VBox pane = new VBox(10);
        pane.setPadding(new Insets(10));

        pane.getChildren().add(ListUi.heading("发现更多服务器",
                "数据来自 mcstat.org 的公共服务器目录。需要免费 API Key：在 mcstat.org 注册后复制，粘贴到下面即可。"));

        // ---- Key 设置 ----
        mcstatKeyField = new JFXTextField();
        mcstatKeyField.setPromptText("粘贴你的 mcstat.org API Key");
        if (!mcstatKey.isBlank())
            mcstatKeyField.setText(mcstatKey);
        javafx.scene.control.Button saveKeyButton = FXUtils.newRaisedButton("保存 Key");
        saveKeyButton.setMinWidth(Region.USE_PREF_SIZE);
        saveKeyButton.setOnAction(e -> {
            mcstatKey = mcstatKeyField.getText() == null ? "" : mcstatKeyField.getText().trim();
            McStatClient.saveKey(dataDirectory, mcstatKey);
            mcstatKeyBox.setVisible(false);
            mcstatKeyBox.setManaged(false);
            loadRemote();
        });
        mcstatKeyBox = ListUi.inputRow("API Key", "免费获取：mcstat.org → 注册 → API Keys", mcstatKeyField, saveKeyButton);
        mcstatKeyBox.setVisible(mcstatKey.isBlank());
        mcstatKeyBox.setManaged(mcstatKey.isBlank());
        pane.getChildren().add(mcstatKeyBox);

        // ---- 搜索 / 排序 ----
        remoteSearchField = new JFXTextField();
        remoteSearchField.setPromptText("在目录中搜索，例如 survival / economy / 玩法关键词");
        remoteSearchField.setOnAction(e -> loadRemote());
        remoteSortBox = new JFXComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "人数最多", "最热", "最多投票", "最新收录", "在线率最高"));
        remoteSortBox.getSelectionModel().selectFirst();
        remoteSortBox.setOnAction(e -> loadRemote());
        pane.getChildren().add(ListUi.entryRow(null, "排序", "结果按所选方式排序", remoteSortBox));

        // 类型标签：mcstat.org 的 tag 参数；可从常见值里选，也可以自由输入
        remoteTagBox = new JFXComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "全部", "survival", "minigames", "pvp", "skyblock", "economy", "towny",
                "creative", "roleplay", "anarchy", "vanilla", "modded", "factions",
                "bedwars", "smp", "lifesteal", "crossplay", "parkour"));
        remoteTagBox.setEditable(true);
        remoteTagBox.getSelectionModel().selectFirst();
        remoteTagBox.setOnAction(e -> loadRemote());
        pane.getChildren().add(ListUi.entryRow(null, "类型标签",
                "按玩法筛选（survival 生存 / minigames 小游戏 / economy 经济……），可自由输入",
                remoteTagBox));

        javafx.scene.control.Button remoteSearchButton = FXUtils.newRaisedButton("搜索");
        remoteSearchButton.setMinWidth(Region.USE_PREF_SIZE);
        remoteSearchButton.setOnAction(e -> loadRemote());
        pane.getChildren().add(ListUi.inputRow("搜索目录", "按名称/描述搜索", remoteSearchField,
                remoteSearchButton));

        // ---- 结果 ----
        remoteStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        remoteStatus.setWrapText(true);
        pane.getChildren().add(remoteStatus);
        pane.getChildren().add(remoteResults);

        // ---- 翻页 ----
        remotePrev = ListUi.button("上一页", () -> {
            if (remotePage > 1)
                loadRemote(remotePage - 1);
        });
        remoteNext = ListUi.button("下一页", () -> {
            if (remotePage < remoteTotalPages)
                loadRemote(remotePage + 1);
        });
        remotePageInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        HBox pager = new HBox(10, remotePrev, remotePageInfo, remoteNext);
        pager.setAlignment(Pos.CENTER);
        pane.getChildren().add(pager);

        showView(pane, true);
        subViewBack = this::backToList;
        if (!mcstatKey.isBlank())
            loadRemote();
    }

    private String remoteSortValue() {
        return switch (remoteSortBox == null ? 0 : remoteSortBox.getSelectionModel().getSelectedIndex()) {
            case 1 -> "hot";
            case 2 -> "votes";
            case 3 -> "recent";
            case 4 -> "uptime";
            default -> "players";
        };
    }

    private void loadRemote() {
        loadRemote(1);
    }

    private String remoteTagValue() {
        if (remoteTagBox == null)
            return "";
        Object value = remoteTagBox.getEditor().getText();
        String text = value == null ? "" : value.toString().trim();
        // 默认为「全部」：不附加任何 tag 过滤
        return text.isEmpty() || "全部".equals(text) ? "" : text;
    }

    private void loadRemote(int page) {
        remotePage = Math.max(1, page);
        remoteResults.getChildren().clear();
        remotePrev.setDisable(true);
        remoteNext.setDisable(true);
        remoteStatus.setText("正在从 mcstat.org 获取目录…");

        String query = remoteSearchField.getText() == null ? "" : remoteSearchField.getText().trim();
        String sort = remoteSortValue();
        String tag = remoteTagValue();
        int page0 = remotePage;
        new Thread(() -> {
            try {
                McStatClient.Page result = McStatClient.servers(mcstatKey, query, tag, sort, page0, 20);
                Platform.runLater(() -> renderRemote(result));
            } catch (Exception e) {
                Platform.runLater(() -> remoteStatus.setText("获取失败："
                        + (e.getMessage() == null ? String.valueOf(e) : e.getMessage())));
            }
        }, "server-manager-mcstat").start();
    }

    private void renderRemote(McStatClient.Page result) {
        remoteResults.getChildren().clear();
        remoteTotal = result.total();
        remoteTotalPages = result.totalPages();
        remotePage = result.page();
        remotePageInfo.setText("第 " + result.page() + " / " + result.totalPages() + " 页 · 共 " + result.total() + " 个");
        remotePrev.setDisable(remotePage <= 1);
        remoteNext.setDisable(remotePage >= remoteTotalPages);

        if (result.servers().isEmpty()) {
            remoteStatus.setText("这一页没有结果。换一页或换个关键词试试。");
            return;
        }
        remoteStatus.setText("本页 " + result.servers().size() + " 个 · 共 " + result.total() + " 个服务器");

        for (McStatClient.RemoteServer server : result.servers())
            remoteResults.getChildren().add(remoteCard(server));
    }

    private Node remoteCard(McStatClient.RemoteServer server) {
        ImageView icon = new ImageView();
        icon.setFitWidth(48);
        icon.setFitHeight(48);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        loadRemoteIcon(icon, server.address(), server.iconUrl());

        Label name = new Label(server.name());
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;"
                + " -fx-text-fill: -monet-on-surface;");

        String versionText = server.version().isBlank() ? "?" :
                (server.version().length() > 18 ? server.version().substring(0, 18) + "…" : server.version());
        Label version = new Label(versionText);
        version.setStyle("-fx-font-size: 10px; -fx-background-color: rgba(128,128,128,0.15);"
                + " -fx-background-radius: 4; -fx-padding: 1 6 1 6; -fx-text-fill: -fx-mid-text-color;");

        Label state = new Label(server.reachable() ? "● 在线 " + server.online() + " / " + server.max()
                : "● 离线");
        state.setStyle("-fx-font-size: 12px; -fx-text-fill: "
                + (server.reachable() ? "#1a9b4b" : "#c62828") + ";");
        state.setPadding(new Insets(2, 0, 0, 0));

        // mcstat reports player counts but not latency, so the delay is measured here, the
        // same way the saved list measures it: one status ping per server. It runs off the FX
        // thread and fills the label when it answers, so a slow or dead server never delays
        // the rest of the directory from appearing.
        Label latency = new Label();
        latency.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        latency.setPadding(new Insets(2, 0, 0, 0));
        if (server.reachable()) {
            latency.setText("延迟 测量中…");
            measureLatency(server.address(), latency);
        } else {
            latency.setText("延迟 —");
        }

        Label motd = new Label(server.motd().replace("\\n", " "));
        motd.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        motd.setWrapText(false);
        motd.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        VBox text = new VBox(3, new HBox(6, version, state, latency), name, motd);
        HBox.setHgrow(text, Priority.ALWAYS);

        com.jfoenix.controls.JFXButton cardButton = new com.jfoenix.controls.JFXButton();
        cardButton.setButtonType(com.jfoenix.controls.JFXButton.ButtonType.FLAT);
        HBox cardContent = new HBox(12, icon, text);
        cardContent.setAlignment(Pos.CENTER_LEFT);
        cardButton.setGraphic(cardContent);
        cardButton.setStyle("-fx-background-color: -monet-surface-container; -fx-background-radius: 8;"
                + " -fx-padding: 8 12 8 12;");
        cardButton.setMaxWidth(Double.MAX_VALUE);
        cardButton.setAlignment(Pos.CENTER_LEFT);
        cardButton.setOnAction(e -> showRemoteDetail(server));

        HBox card = new HBox(10, cardButton, enterGameButton(server.address(), server.name()));
        card.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cardButton, Priority.ALWAYS);
        return card;
    }

    /// Measures one directory server's latency and fills the label when it answers.
    ///
    /// <p>The measurement is the same status ping the saved list uses, so the two lists report
    /// comparable numbers. It runs on the shared background executor and reports back on the
    /// FX thread; a failure (offline, filtered, timed out) is shown as a dash rather than as a
    /// number that would be wrong.</p>
    ///
    /// @param address the server address, host or host:port
    /// @param target  the label to fill in
    private void measureLatency(String address, Label target) {
        pingPool.submit(() -> {
            String text;
            String color;
            try {
                ServerPinger.Status status = ServerPinger.ping(address, defaultPort(address), 4000);
                int ms = (int) status.latencyMs();
                text = "延迟 " + ms + " ms";
                // Same thresholds as the saved list, so the colours mean the same thing.
                color = ms < 100 ? "#1a9b4b" : ms < 300 ? "#b26a00" : "#c62828";
            } catch (Throwable t) {
                text = "延迟 —";
                color = "-fx-mid-text-color";
            }
            String finalText = text;
            String finalColor = color;
            javafx.application.Platform.runLater(() -> {
                target.setText(finalText);
                target.setStyle("-fx-font-size: 11px; -fx-text-fill: " + finalColor + ";");
            });
        });
    }

    private void showRemoteDetail(McStatClient.RemoteServer server) {
        // 与内置目录一致：小图标显示在名称左侧
        ImageView icon = new ImageView();
        icon.setFitWidth(56);
        icon.setFitHeight(56);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        loadRemoteIcon(icon, server.address(), server.iconUrl());

        Label name = new Label(server.name());
        name.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        HBox tags = new HBox(8);
        Label version = new Label(server.version().isBlank() ? "?" : server.version());
        version.setStyle("-fx-font-size: 11px; -fx-background-color: rgba(128,128,128,0.15);"
                + " -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
        tags.getChildren().add(version);
        for (String tag : server.tags()) {
            Label chip = new Label(tag);
            chip.setStyle("-fx-font-size: 11px; -fx-background-color: rgba(33,150,243,0.15);"
                    + " -fx-background-radius: 4; -fx-padding: 2 8 2 8; -fx-text-fill: #1e88e5;");
            tags.getChildren().add(chip);
        }

        Label statusLine = new Label(server.reachable()
                ? "● 在线 " + server.online() + " / " + server.max() + " 人"
                : "● 当前离线");
        statusLine.setStyle("-fx-font-size: 13px; -fx-text-fill: "
                + (server.reachable() ? "#1a9b4b" : "#c62828") + ";");
        statusLine.setPadding(new Insets(4, 0, 4, 0));

        // Same treatment as the list card: the delay is measured here rather than reported by
        // mcstat, so it appears once the ping answers.
        Label latencyLine = new Label();
        latencyLine.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-mid-text-color;");
        if (server.reachable()) {
            latencyLine.setText("延迟 测量中…");
            measureLatency(server.address(), latencyLine);
        } else {
            latencyLine.setText("延迟 —");
        }

        Label motd = new Label(server.motd().replace("\\n", " "));
        motd.setWrapText(true);
        motd.setStyle("-fx-font-size: 13px;");

        Label addressLine = new Label("服务器地址：" + server.address());
        addressLine.setStyle("-fx-font-size: 12px; -fx-font-family: 'Consolas','Courier New',monospace;");

        javafx.scene.control.Button copyButton = FXUtils.newRaisedButton("复制地址");
        copyButton.setMinWidth(Region.USE_PREF_SIZE);
        copyButton.setOnAction(e -> FXUtils.copyText(server.address()));

        javafx.scene.control.Button pageButton = FXUtils.newRaisedButton("在 mcstat.org 打开");
        pageButton.setMinWidth(Region.USE_PREF_SIZE);
        pageButton.setOnAction(e -> FXUtils.openLink(server.pageUrl()));

        HBox actions = new HBox(10, enterGameButton(server.address(), server.name()),
                copyButton, pageButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox detail = new VBox(10,
                detailHeader(icon, name, tags, statusLine),
                latencyLine, motd, wrap(addressLine, copyButton), actions);
        detail.setPadding(new Insets(10));
        showView(detail, true);
        // 目录里的服务器详情：退一层回到在线目录，而不是直接跳到已保存的列表。
        subViewBack = this::showRemote;
    }
}
