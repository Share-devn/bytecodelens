package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.detector.DetectionReport;
import dev.share.bytecodelens.detector.ObfuscatorSignature;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

public final class DetectionReportDialog {

    private final Stage stage;
    private final DetectionReport report;

    public DetectionReportDialog(DetectionReport report, boolean darkTheme) {
        this.report = report;
        this.stage = new Stage();
        stage.setTitle("Obfuscator Detection Report");
        stage.setWidth(780);
        stage.setHeight(620);

        VBox content = buildContent();
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("detection-scroll");

        BorderPane root = new BorderPane(scroll);
        root.setTop(buildHeader());
        root.setBottom(buildFooter());
        root.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");
        root.getStyleClass().add("detection-root");

        Scene scene = new Scene(root);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/css/app.css")).toExternalForm());
        stage.setScene(scene);
        dev.share.bytecodelens.util.Icons.apply(stage);
    }

    public void show() {
        stage.show();
    }

    private VBox buildHeader() {
        Label title = new Label("Obfuscator Detection Report");
        title.getStyleClass().add("detection-title");

        Label sub = new Label(String.format(
                "Analyzed %d classes, %d resources in %dms",
                report.classCount(), report.resourceCount(), report.durationMs()));
        sub.getStyleClass().add("detection-subtitle");

        VBox head = new VBox(4, title, sub);
        head.setPadding(new Insets(14, 18, 12, 18));
        head.getStyleClass().add("detection-header");
        return head;
    }

    private VBox buildContent() {
        VBox content = new VBox(6);
        content.setPadding(new Insets(12, 18, 12, 18));

        List<ObfuscatorSignature> high = report.high();
        List<ObfuscatorSignature> mid = report.medium();
        List<ObfuscatorSignature> low = report.low();

        if (!high.isEmpty()) {
            content.getChildren().add(sectionLabel("HIGH confidence  (> 70%)"));
            for (var s : high) content.getChildren().add(buildSignatureBox(s, "high"));
        }
        if (!mid.isEmpty()) {
            content.getChildren().add(sectionLabel("MEDIUM confidence  (30 – 70%)"));
            for (var s : mid) content.getChildren().add(buildSignatureBox(s, "medium"));
        }
        if (!low.isEmpty()) {
            content.getChildren().add(sectionLabel("LOW confidence  (< 30%)"));
            for (var s : low) content.getChildren().add(buildSignatureBox(s, "low"));
        }
        if (high.isEmpty() && mid.isEmpty() && low.isEmpty()) {
            Label none = new Label("No obfuscation patterns detected.");
            none.getStyleClass().add("detection-none");
            content.getChildren().add(none);
        }

        if (!report.notDetected().isEmpty()) {
            content.getChildren().add(sectionLabel("NOT DETECTED"));
            Label nd = new Label(String.join("    ", report.notDetected()));
            nd.getStyleClass().add("detection-notdetected");
            nd.setWrapText(true);
            content.getChildren().add(nd);
        }
        return content;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("detection-section-title");
        return l;
    }

    private VBox buildSignatureBox(ObfuscatorSignature s, String levelClass) {
        Label name = new Label(s.name());
        name.getStyleClass().add("detection-sig-name");

        Label conf = new Label(String.format("%.0f%%", s.confidence() * 100));
        conf.getStyleClass().addAll("detection-sig-conf", "detection-sig-conf-" + levelClass);

        Label family = new Label(s.family().name().replace('_', ' ').toLowerCase());
        family.getStyleClass().add("detection-sig-family");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox head = new HBox(10, name, family, spacer, conf);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox evidence = new VBox(3);
        for (var ev : s.evidence()) {
            FontIcon bullet = new FontIcon("mdi2c-check-circle-outline");
            bullet.setIconSize(12);
            bullet.getStyleClass().add("detection-evidence-bullet");
            Label line = new Label(ev.description());
            line.getStyleClass().add("detection-evidence");
            line.setWrapText(true);
            HBox row = new HBox(8, bullet, line);
            row.setAlignment(Pos.TOP_LEFT);
            evidence.getChildren().add(row);
        }

        VBox box = new VBox(6, head, evidence);
        box.setPadding(new Insets(10, 12, 10, 12));
        box.getStyleClass().addAll("detection-sig-box", "detection-sig-box-" + levelClass);
        return box;
    }

    private HBox buildFooter() {
        Button copy = new Button("Copy to clipboard");
        copy.setGraphic(icon("mdi2c-content-copy"));
        copy.getStyleClass().add("button-outlined");
        copy.setOnAction(e -> {
            Clipboard cb = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(report.asText());
            cb.setContent(cc);
        });

        Button save = new Button("Save report...");
        save.setGraphic(icon("mdi2c-content-save-outline"));
        save.getStyleClass().add("button-outlined");
        save.setOnAction(e -> saveReport());

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        HBox bar = new HBox(8, copy, save, sp, close);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("detection-footer");
        return bar;
    }

    private FontIcon icon(String literal) {
        FontIcon i = new FontIcon(literal);
        i.setIconSize(14);
        return i;
    }

    private void saveReport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save detection report");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));
        chooser.setInitialFileName("obfuscator-report.txt");
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), report.asText());
        } catch (Exception ignored) {
        }
    }
}
