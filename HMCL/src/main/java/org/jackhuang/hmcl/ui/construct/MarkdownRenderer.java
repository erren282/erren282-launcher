package org.jackhuang.hmcl.ui.construct;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.ui.FXUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Renders a subset of Markdown into a JavaFX [TextFlow]: headings, bold / italic,
/// inline code, fenced code blocks, bullet and numbered lists, block quotes,
/// horizontal rules and clickable links. Chosen over a WebView so the plugin needs
/// no javafx-web module, which the launcher does not ship.
public final class MarkdownRenderer {

    /// One styled inline run; `link` is non-null for links.
    public record Span(String style, String text, String link) {
        static Span text(String t) { return new Span(null, t, null); }
    }

    private static final Pattern INLINE = Pattern.compile("(\\*\\*(?<bold>.+?)\\*\\*)|(`(?<code>[^`]+)`)|(\\*(?<italic>[^*\\s][^*]*?)\\*)|(\\[(?<label>[^\\]]+)\\]\\((?<url>[^)\\s]+)\\))");

    private MarkdownRenderer() {
    }

    /// Parses one line of markdown into styled spans (pure; testable headlessly).
    public static List<Span> parseInline(String line) {
        List<Span> spans = new ArrayList<>();
        Matcher matcher = INLINE.matcher(line);
        int plain = 0;
        while (matcher.find()) {
            if (matcher.start() > plain)
                spans.add(Span.text(line.substring(plain, matcher.start())));
            if (matcher.group("bold") != null)
                spans.add(new Span("bold", matcher.group("bold"), null));
            else if (matcher.group("code") != null)
                spans.add(new Span("code", matcher.group("code"), null));
            else if (matcher.group("italic") != null)
                spans.add(new Span("italic", matcher.group("italic"), null));
            else
                spans.add(new Span("link", matcher.group("label"), matcher.group("url")));
            plain = matcher.end();
        }
        if (plain < line.length())
            spans.add(Span.text(line.substring(plain)));
        return spans;
    }

    /// Collapses a model reply into a single continuous passage: section headings become
    /// inline bold lead-ins and blank lines are dropped, so the window shows one block of
    /// text instead of a stack of paragraphs.
    public static String singlePassage(String markdown) {
        if (markdown == null)
            return "";
        String text = markdown.replace("\r\n", "\n")
                .replaceAll("(?m)^\\s*#{1,6}\\s*(.+?)\\s*$", "**$1**");
        return text.replaceAll("\n{2,}", "\n").strip();
    }

    /// Renders markdown text into a scrollable node.
    public static Node render(String markdown) {
        TextFlow flow = new TextFlow();
        update(flow, markdown);
        ScrollPane scroll = new ScrollPane(flow);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        scroll.setPrefSize(640, 360);
        return scroll;
    }

    /// Rebuilds an existing TextFlow in place — used for streaming updates where a
    /// fresh node would reset the scroll position.
    public static void update(TextFlow flow, String markdown) {

        String[] lines = (markdown == null ? "" : markdown).replace("\r\n", "\n").split("\n", -1);

        boolean inCode = false;
        VBox codeBox = null;
        List<Node> pendingBulletBuffer = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();

            if (line.startsWith("```")) {
                if (inCode) {
                    inCode = false;
                    if (codeBox != null && !codeBox.getChildren().isEmpty()) {
                        flow.getChildren().add(codeBox);
                        flow.getChildren().add(new Text(System.lineSeparator()));
                    }
                    codeBox = null;
                    continue;
                }
                flushBullets(pendingBulletBuffer, flow);
                inCode = true;
                codeBox = new VBox(1);
                codeBox.setStyle("-fx-background-color: rgba(128,128,128,0.12); -fx-background-radius: 4; -fx-padding: 6 8 6 8;");
                continue;
            }
            if (inCode && codeBox != null) {
                Text code = new Text(line.isEmpty() ? " " : line);
                code.setStyle("-fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12;");
                codeBox.getChildren().add(code);
                continue;
            }

            // headings
            if (line.matches("^#{1,6} .*")) {
                flushBullets(pendingBulletBuffer, flow);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#')
                    level++;
                Text heading = new Text(line.substring(level).trim());
                int size = switch (Math.min(level, 4)) {
                    case 1 -> 20;
                    case 2 -> 17;
                    case 3 -> 15;
                    default -> 14;
                };
                heading.setStyle("-fx-font-weight: bold; -fx-font-size: " + size + ";");
                flow.getChildren().addAll(heading, new Text(System.lineSeparator()));
                continue;
            }

            // horizontal rule
            if (line.matches("^(---+|\\*\\*\\*+|___+)\\s*$")) {
                flushBullets(pendingBulletBuffer, flow);
                Text rule = new Text("─────────────────────────────────────");
                rule.setStyle("-fx-fill: #999;");
                flow.getChildren().addAll(rule, new Text(System.lineSeparator()));
                continue;
            }

            // block quote
            if (line.startsWith("> ")) {
                flushBullets(pendingBulletBuffer, flow);
                Text bar = new Text("│ ");
                bar.setStyle("-fx-fill: #2196f3;");
                Text quote = styledText("quote", line.substring(2));
                quote.setStyle("-fx-fill: #555;");
                flow.getChildren().addAll(bar, quote, new Text(System.lineSeparator()));
                continue;
            }

            // lists
            if (line.matches("^\\s*([-*+]) .*")) {
                String content = line.replaceFirst("^\\s*[-*+] ", "");
                pendingBulletBuffer.add(bulletLine("  • ", content, false));
                continue;
            }
            if (line.matches("^\\s*\\d+\\. .*")) {
                String marker = line.trim().split("\\. ", 2)[0] + ". ";
                pendingBulletBuffer.add(bulletLine("  " + marker, line.trim().substring(marker.length()), true));
                continue;
            }

            flushBullets(pendingBulletBuffer, flow);

            // blank line -> spacing
            if (line.isBlank()) {
                flow.getChildren().add(new Text(System.lineSeparator()));
                continue;
            }

            // normal paragraph
            for (Node node : inlineNodes(line))
                flow.getChildren().add(node);
            flow.getChildren().add(new Text(System.lineSeparator()));
        }
        flushBullets(pendingBulletBuffer, flow);
        // an unclosed fence still renders what it collected
        if (codeBox != null && !codeBox.getChildren().isEmpty())
            flow.getChildren().add(codeBox);
    }

    /// Bullets are buffered so consecutive items sit on adjacent lines.
    private static void flushBullets(List<Node> buffer, TextFlow flow) {
        for (Node node : buffer)
            flow.getChildren().add(node);
        if (!buffer.isEmpty())
            flow.getChildren().add(new Text(System.lineSeparator()));
        buffer.clear();
    }

    private static Node bulletLine(String marker, String content, boolean numbered) {
        TextFlow line = new TextFlow();
        Text mark = new Text(marker);
        mark.setStyle("-fx-fill: " + (numbered ? "#2196f3" : "#888") + ";");
        line.getChildren().add(mark);
        line.getChildren().addAll(inlineNodes(content));
        return line;
    }

    private static List<Node> inlineNodes(String line) {
        List<Node> nodes = new ArrayList<>();
        for (Span span : parseInline(line)) {
            Text text = styledText(span.style(), span.text());
            if (span.link() != null) {
                text.setStyle((text.getStyle() == null ? "" : text.getStyle())
                        + "-fx-fill: #1e88e5; -fx-underline: true;");
                text.setCursor(Cursor.HAND);
                String url = span.link();
                text.setOnMouseClicked(e -> FXUtils.openLink(url));
            }
            nodes.add(text);
        }
        return nodes;
    }

    private static Text styledText(String style, String content) {
        Text text = new Text(content);
        switch (style == null ? "" : style) {
            case "bold" -> text.setStyle("-fx-font-weight: bold;");
            case "italic" -> text.setStyle("-fx-font-style: italic;");
            case "code" -> text.setStyle("-fx-font-family: 'Consolas','Courier New',monospace; "
                    + "-fx-font-size: 12; -fx-background-color: rgba(128,128,128,0.15);");
            case "quote", "link" -> { /* colors applied by callers */ }
            default -> { }
        }
        return text;
    }
}
