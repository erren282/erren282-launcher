package org.dsh.servermanager;

import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;

/// Manages the plugins of one server: view, enable/disable, delete, and download new ones from
/// the built-in store.
///
/// <p>The page is a single column of uniform cards, identical in layout to the player page:
/// one card per installed plugin with 停用/启用、官网、删除 buttons on the right, then the
/// store's search field, filter and results in the same column at the same width.</p>
///
/// <p>Cards carry the plugin's icon: store results show the Modrinth icon directly (fetched in
/// one batched request per page), installed plugins are matched by name and cached on disk.</p>
@NotNullByDefault
public final class PluginManagerPage extends VBox {

    private final ServerManagerService service;
    private final ServerProfile profile;

    private final VBox column = new VBox(8);
    private final Label statusLabel = new Label(" ");
    private final VBox storeResults = new VBox(8);
    private final Label storeStatus = new Label(" ");

    private JFXTextField searchField;
    private JFXComboBox<String> filterBox;
    private JFXComboBox<String> categoryBox;
    private javafx.scene.control.Button prevPageButton;
    private javafx.scene.control.Button nextPageButton;
    private final Label pageInfo = new Label(" ");
    /// Zero-based page index; reset whenever a new search starts.
    private int storePage = 0;
    /// Total hits of the last search; drives the pager's label and bounds.
    private int storeTotal = 0;
    private static final int STORE_PAGE_SIZE = 20;
    private List<String> cores = List.of();
    private String mcVersion = "";

    public PluginManagerPage(ServerManagerService service, ServerProfile profile) {
        this.service = service;
        this.profile = profile;

        setSpacing(12);
        setPadding(new Insets(4, 0, 0, 0));

        // 页内不再画「返回服务端」：返回交给窗口左上角的原生箭头（ServerManagerPage#back）。

        // The core and version drive the store filter, so they are resolved up front.
        cores = PluginStore.coresFor(profile);
        mcVersion = JavaCompatibility.detectMcVersion(profile);

        getChildren().add(ManageUi.heading("插件管理",
                "管理「" + profile.getName() + "」的插件，或从插件商店下载新插件。"));

        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        statusLabel.setWrapText(true);
        getChildren().add(statusLabel);

        // The column is added directly: the launcher shell already scrolls the whole page, so
        // wrapping it in a ScrollPane would nest two scrollers and the store list would only
        // move with the inner one.
        getChildren().add(column);

        reload();
    }

    /// Called by the host page after this node has been added to the scene, so the scroll reset
    /// can find its ancestor scroll pane. Doing it in the constructor cannot work: the node has
    /// no parent there yet.
    void onShown() {
        ManageUi.scrollToTop(this);
    }

    /// Rebuilds the installed-plugin list and the store section.
    private void reload() {
        column.getChildren().clear();
        List<ServerPlugins.Plugin> plugins = ServerPlugins.list(profile);
        long enabled = plugins.stream().filter(ServerPlugins.Plugin::isEnabled).count();

        statusLabel.setText("核心：" + PluginStore.coreDisplayName(PluginStore.detectCore(profile))
                + "    Minecraft " + (mcVersion.isBlank() ? "未知" : mcVersion)
                + "    已安装 " + plugins.size() + " 个（启用 " + enabled + " 个）"
                + (service.isRunning(profile) ? "    ● 服务端运行中，改动需重启后生效" : ""));

        buildInstalledSection(plugins);
        buildStoreSection();
    }

    // ---------------------------------------------------------- installed ---

    private void buildInstalledSection(List<ServerPlugins.Plugin> plugins) {
        column.getChildren().add(ManageUi.heading("已安装的插件",
                "「停用」会把插件移到 plugins/.disabled（完全可逆）；「删除」只删插件文件，"
                        + "保留它的配置目录。"));

        if (plugins.isEmpty()) {
            column.getChildren().add(ManageUi.infoCard("还没有插件",
                    "用下面的插件商店搜索并下载，或把插件 jar 放进服务端的 plugins 目录。"));
            return;
        }

        for (ServerPlugins.Plugin plugin : plugins) {
            Node icon = PluginIcons.placeholder();
            column.getChildren().add(ManageUi.entryRow(icon,
                    (plugin.isEnabled() ? "" : "［已停用］") + plugin.getDisplayName(),
                    buildSubtitle(plugin),
                    ManageUi.button(plugin.isEnabled() ? "停用" : "启用",
                            () -> setEnabled(plugin, !plugin.isEnabled())),
                    ManageUi.button(plugin.hasWebsite() ? "官网" : "商店搜索",
                            () -> ServerManagerPlugin.openLink(plugin.getInfoUrl())),
                    ManageUi.dangerButton("删除", () -> confirmDelete(plugin))));

            // resolve the icon in the background; the card layout above is unaffected
            PluginIcons.loadAsync(
                    new PluginIcons.InstalledIcon(plugin.getName(), service.getDataDirectory()),
                    (ImageView) icon);
        }
    }

    private String buildSubtitle(ServerPlugins.Plugin plugin) {
        StringBuilder info = new StringBuilder();
        info.append(plugin.getFileName()).append("    ").append(PluginManagerPage.formatSize(plugin.getSizeBytes()));
        if (!plugin.getApiVersion().isBlank())
            info.append("    API ").append(plugin.getApiVersion());
        if (!plugin.getAuthors().isBlank())
            info.append("    ").append(plugin.getAuthors());
        if (!plugin.getDepends().isEmpty())
            info.append("    前置：").append(String.join(", ", plugin.getDepends()));
        if (plugin.hasWebsite())
            info.append("\n官网：").append(plugin.getWebsite());
        if (!plugin.getDescription().isBlank())
            info.append("\n").append(abbreviate(plugin.getDescription()));
        return info.toString();
    }

    private void setEnabled(ServerPlugins.Plugin plugin, boolean enabled) {
        runAsync(enabled ? "启用插件失败" : "停用插件失败", () -> {
            if (enabled)
                ServerPlugins.enable(profile, plugin.getFileName());
            else
                ServerPlugins.disable(profile, plugin.getFileName());
            ServerManagerPlugin.log("[插件管理] " + (enabled ? "启用 " : "停用 ") + plugin.getFileName());
            Platform.runLater(() -> {
                reload();
                ServerManagerPlugin.info(enabled ? "已启用" : "已停用",
                        plugin.getName() + (enabled
                                ? " 已放回 plugins 目录，重启服务端后加载。"
                                : " 已移入 plugins/.disabled，重启服务端后不再加载。"));
            });
        });
    }

    private void confirmDelete(ServerPlugins.Plugin plugin) {
        ServerManagerPlugin.confirm("删除插件",
                "确定删除「" + plugin.getName() + "」吗？\n\n"
                        + "只会删除 " + plugin.getFileName() + "，它的配置目录（plugins/"
                        + plugin.getName() + "/）会保留，重新安装后可继续使用。",
                () -> runAsync("删除插件失败", () -> {
                    ServerPlugins.delete(profile, plugin.getFileName());
                    ServerManagerPlugin.log("[插件管理] 删除 " + plugin.getFileName());
                    Platform.runLater(() -> {
                        reload();
                        ServerManagerPlugin.info("已删除", plugin.getName() + " 已从服务端移除。");
                    });
                }));
    }

    // -------------------------------------------------------------- store ---

    private void buildStoreSection() {
        column.getChildren().add(ManageUi.heading("插件商店",
                "搜索会按本服务端的 Minecraft " + (mcVersion.isBlank() ? "版本" : mcVersion)
                        + " 与 " + PluginStore.coreDisplayName(PluginStore.detectCore(profile))
                        + " 核心筛选；下载的插件直接装进「" + profile.getName() + "」的 plugins 目录。"
                        + "来源：Modrinth。"));

        searchField = new JFXTextField();
        searchField.setPromptText("搜索插件，例如 EssentialsX / Vault / WorldEdit");
        searchField.setOnAction(e -> searchStore(searchField.getText()));
        column.getChildren().add(ManageUi.inputRow("搜索插件", null, searchField,
                ManageUi.primaryButton("搜索", () -> searchStore(searchField.getText()))));

        filterBox = new JFXComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "仅显示适配当前服务端", "显示全部版本与核心"));
        filterBox.getSelectionModel().selectFirst();
        filterBox.setOnAction(e -> searchStore(searchField == null ? "" : searchField.getText()));
        column.getChildren().add(ManageUi.entryRow(null, "筛选",
                "默认只列出与本服务端版本、核心都兼容的插件", filterBox));

        categoryBox = new JFXComboBox<>();
        for (var entry : PluginStore.categories())
            categoryBox.getItems().add(entry.getValue());
        categoryBox.getSelectionModel().selectFirst();
        categoryBox.setOnAction(e -> searchStore(searchField == null ? "" : searchField.getText()));
        column.getChildren().add(ManageUi.entryRow(null, "分类",
                "按插件类型浏览：经济、小游戏、保护、管理……不只是搜索", categoryBox));

        storeStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        storeStatus.setWrapText(true);
        column.getChildren().add(storeStatus);
        column.getChildren().add(storeResults);

        // 翻页：上一页 / 页码 / 下一页，由 Modrinth 的 total_hits 驱动
        prevPageButton = ManageUi.button("上一页", () -> {
            if (storePage > 0)
                searchStore(searchField == null ? "" : searchField.getText(), storePage - 1);
        });
        nextPageButton = ManageUi.button("下一页", () -> {
            if ((storePage + 1) * STORE_PAGE_SIZE < storeTotal)
                searchStore(searchField == null ? "" : searchField.getText(), storePage + 1);
        });
        pageInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        HBox pager = new HBox(10, prevPageButton, pageInfo, nextPageButton);
        pager.setAlignment(javafx.geometry.Pos.CENTER);
        pager.setPadding(new javafx.geometry.Insets(6, 0, 0, 0));
        column.getChildren().add(pager);

        // show the most downloaded compatible plugins immediately, so the store is never blank;
        // this automatic load is not a user action and must not scroll the page to the store
        Platform.runLater(() -> searchStore("", 0, false));
    }

    /// Searches the store and renders the results.
    private void searchStore(String query) {
        searchStore(query, 0);
    }

    private void searchStore(String query, int page) {
        searchStore(query, page, true);
    }

    private void searchStore(String query, int page, boolean userDriven) {
        boolean filtered = filterBox == null || filterBox.getSelectionModel().getSelectedIndex() == 0;
        // an unknown Minecraft version cannot be filtered on, which the status line states below
        boolean hasVersion = mcVersion != null && !mcVersion.isBlank();
        String version = filtered && hasVersion ? mcVersion : null;
        List<String> searchCores = filtered ? cores : List.of();
        String category = selectedCategory();
        storePage = Math.max(0, page);

        // 旧结果保留到新结果就绪：此刻清空会让页面高度骤减，外层滚动猛地往上跳
        storeStatus.setText("正在搜索…");
        prevPageButton.setDisable(true);
        nextPageButton.setDisable(true);
        pageInfo.setText(" ");

        int offset = storePage * STORE_PAGE_SIZE;
        new Thread(() -> {
            try {
                PluginStore.SearchResult result = PluginStore.search(
                        query == null ? "" : query.trim(), version, searchCores,
                        category, STORE_PAGE_SIZE, offset);
                storeTotal = result.totalHits();
                List<PluginStore.Listing> listings = result.listings();
                // one batched request resolves the PNG icons for the whole result page
                List<String> ids = listings.stream().map(PluginStore.Listing::getProjectId).toList();
                Map<String, String> icons = PluginIcons.fetchStoreIcons(ids);
                boolean scrollTo = storePage > 0 || userDriven;
                Platform.runLater(() -> renderStoreResults(listings, icons, version, scrollTo));
            } catch (Exception e) {
                Platform.runLater(() -> storeStatus.setText("搜索失败："
                        + (e.getMessage() == null ? String.valueOf(e) : e.getMessage())));
            }
        }, "server-manager-plugin-search").start();
    }

    private String selectedCategory() {
        if (categoryBox == null)
            return "";
        int index = categoryBox.getSelectionModel().getSelectedIndex();
        List<Map.Entry<String, String>> categories = PluginStore.categories();
        return index >= 0 && index < categories.size() ? categories.get(index).getKey() : "";
    }

    private int resultTotalPages() {
        return Math.max(1, (storeTotal + STORE_PAGE_SIZE - 1) / STORE_PAGE_SIZE);
    }

    private void renderStoreResults(List<PluginStore.Listing> listings,
                                    Map<String, String> icons, String version, boolean scrollToResults) {
        storeResults.getChildren().clear();
        int totalPages = resultTotalPages();
        pageInfo.setText("第 " + (storePage + 1) + " / " + totalPages + " 页 · 共 " + storeTotal + " 个");
        prevPageButton.setDisable(storePage <= 0);
        nextPageButton.setDisable((storePage + 1) * STORE_PAGE_SIZE >= storeTotal);
        if (listings.isEmpty()) {
            storeStatus.setText("这一页没有结果。可以上一页、切换分类，或换个关键词再试。");
            return;
        }
        if (version == null) {
            storeStatus.setText("本页 " + listings.size() + " 个插件（未按游戏版本筛选 —— "
                    + (mcVersion == null || mcVersion.isBlank()
                    ? "没能识别本服务端的 Minecraft 版本" : "已按核心筛选")
                    + "，下载前请自行确认兼容性）");
        } else {
            storeStatus.setText("本页 " + listings.size() + " 个插件（已按 Minecraft " + version
                    + " 筛选，共 " + storeTotal + " 个）");
        }

        // 翻页/换分类后把结果列表顶部带回视口，用户停在新的结果页上
        if (scrollToResults)
            ManageUi.scrollToNode(storeStatus);

        for (PluginStore.Listing listing : listings) {
            // the batched lookup returns a PNG; fall back to the WebP URL only if it is absent,
            // in which case the loader resolves the PNG itself
            String png = icons.get(listing.getProjectId());
            Node icon = PluginIcons.placeholder();
            storeResults.getChildren().add(ManageUi.entryRow(icon,
                    listing.getTitle(),
                    buildStoreSubtitle(listing),
                    ManageUi.button("商店页面", () -> ServerManagerPlugin.openLink(listing.getPageUrl())),
                    ManageUi.primaryButton("下载到本服务端",
                            () -> downloadFromStore(listing, version))));
            PluginIcons.loadAsync(new PluginIcons.StoreIcon(
                    png != null ? png : listing.getIconUrl(),
                    service.getDataDirectory()), (ImageView) icon);
        }
    }

    private String buildStoreSubtitle(PluginStore.Listing listing) {
        StringBuilder info = new StringBuilder();
        info.append("适配版本：").append(summariseVersions(listing.getGameVersions()));
        info.append("    核心：").append(listing.getLoaders().isEmpty()
                ? "未知" : String.join(" / ", listing.getLoaders()));
        info.append("    下载 ").append(PluginManagerPage.formatCount(listing.getDownloads()));
        if (!listing.getAuthor().isBlank())
            info.append("    ").append(listing.getAuthor());
        String description = listing.getDescription();
        if (description != null && !description.isBlank())
            info.append("\n").append(abbreviate(description));
        return info.toString();
    }

    /// Condenses a version list, which often holds twenty entries.
    private static String summariseVersions(List<String> versions) {
        if (versions.isEmpty())
            return "未知";
        if (versions.size() <= 4)
            return String.join(" / ", versions);
        return versions.get(0) + " … " + versions.get(versions.size() - 1)
                + "（共 " + versions.size() + " 个）";
    }

    private static String abbreviate(String text) {
        return text.length() > 110 ? text.substring(0, 110) + "…" : text;
    }

    /// Confirms and installs one store plugin into this server.
    private void downloadFromStore(PluginStore.Listing listing, String version) {
        boolean filtered = filterBox == null || filterBox.getSelectionModel().getSelectedIndex() == 0;
        List<String> searchCores = filtered ? cores : List.of();

        ServerManagerPlugin.confirm("下载插件",
                "确定把「" + listing.getTitle() + "」下载到服务端「" + profile.getName() + "」吗？\n\n"
                        + "目标目录：" + ServerPlugins.pluginsDirectory(profile) + "\n"
                        + "适配版本：" + summariseVersions(listing.getGameVersions()) + "\n"
                        + "核心：" + (listing.getLoaders().isEmpty()
                        ? "未知" : String.join(" / ", listing.getLoaders())) + "\n\n"
                        + (service.isRunning(profile)
                        ? "服务端正在运行，下载后需要重启服务端才会加载。"
                        : "服务端未运行，下次启动时会加载。"),
                () -> installFromStore(listing, version, searchCores));
    }

    private void installFromStore(PluginStore.Listing listing, String version,
                                  List<String> searchCores) {
        storeStatus.setText("正在下载 " + listing.getTitle() + " …");
        new Thread(() -> {
            try {
                String fileName = PluginStore.installInto(profile, listing.getSlug(), version,
                        searchCores, (done, total) -> Platform.runLater(() ->
                                storeStatus.setText("正在下载 " + listing.getTitle() + " … "
                                        + PluginManagerPage.formatSize(done)
                                        + (total > 0 ? " / " + PluginManagerPage.formatSize(total) : ""))));
                ServerManagerPlugin.log("[插件商店] 已安装 " + fileName);
                Platform.runLater(() -> {
                    reload();
                    ServerManagerPlugin.info("安装完成",
                            listing.getTitle() + " 已安装为 " + fileName + "。\n\n"
                                    + (service.isRunning(profile)
                                    ? "重启服务端后生效。" : "启动服务端后生效。"));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    storeStatus.setText("下载失败："
                            + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
                    ServerManagerPlugin.error("下载插件失败",
                            e.getMessage() == null ? String.valueOf(e) : e.getMessage());
                });
            }
        }, "server-manager-plugin-download").start();
    }

    // ------------------------------------------------------------ helpers ---

    private void runAsync(String errorTitle, ThrowingAction task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Platform.runLater(() -> ServerManagerPlugin.error(errorTitle,
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            }
        }, "server-manager-plugins").start();
    }

    /// A task allowed to throw, so file operations need no try/catch at every call site.
    private interface ThrowingAction {
        void run() throws Exception;
    }

    static String formatSize(long bytes) {
        if (bytes <= 0)
            return "0 B";
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String formatCount(long count) {
        if (count >= 1_000_000)
            return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000)
            return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }
}
