package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.diff.ChangeType;
import dev.share.bytecodelens.diff.ClassDiff;
import dev.share.bytecodelens.diff.JarDiffResult;
import dev.share.bytecodelens.diff.MemberDiff;
import dev.share.bytecodelens.diff.ResourceDiff;
import dev.share.bytecodelens.service.BytecodePrinter;
import dev.share.bytecodelens.ui.highlight.BytecodeHighlighter;
import dev.share.bytecodelens.ui.views.CodeView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Objects;

public final class JarDiffStage {

    private final JarDiffResult result;
    private final Stage stage;
    private final TreeView<DiffNode> tree = new TreeView<>();
    private final BorderPane detailArea = new BorderPane();
    private final BytecodePrinter bytecodePrinter = new BytecodePrinter();

    public JarDiffStage(JarDiffResult result, boolean darkTheme) {
        this.result = result;
        this.stage = new Stage();
        stage.setTitle("Jar Diff — " + result.jarA().getFileName() + " vs " + result.jarB().getFileName());
        stage.setWidth(1400);
        stage.setHeight(860);

        buildTree();
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null) showDetail(now.getValue());
        });

        Label hint = new Label("Select an item to see details");
        hint.getStyleClass().add("diff-hint");
        detailArea.setCenter(new VBox(hint));

        SplitPane split = new SplitPane(tree, detailArea);
        split.setDividerPositions(0.32);
        split.getStyleClass().add("diff-split");

        BorderPane root = new BorderPane(split);
        root.setTop(buildHeader());
        root.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");

        Scene scene = new Scene(root);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/css/app.css")).toExternalForm());
        stage.setScene(scene);
        dev.share.bytecodelens.util.Icons.apply(stage);
    }

    public void show() {
        stage.show();
    }

    private HBox buildHeader() {
        JarDiffResult.Stats s = result.stats();
        HBox bar = new HBox(12);
        bar.setPadding(new Insets(8, 14, 8, 14));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("diff-header");

        Label title = new Label(result.jarA().getFileName() + " → " + result.jarB().getFileName());
        title.getStyleClass().add("diff-header-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label classes = new Label(String.format(
                "Classes: %d added / %d removed / %d modified",
                s.addedClasses(), s.removedClasses(), s.modifiedClasses()));
        classes.getStyleClass().add("diff-stat");

        Label resources = new Label(String.format(
                "Resources: %d added / %d removed / %d modified",
                s.addedResources(), s.removedResources(), s.modifiedResources()));
        resources.getStyleClass().add("diff-stat");

        Label elapsed = new Label(result.durationMs() + "ms");
        elapsed.getStyleClass().add("diff-stat-muted");

        bar.getChildren().addAll(title, spacer, classes, resources, elapsed);
        return bar;
    }

    private void buildTree() {
        DiffNode rootData = new DiffNode(DiffNode.Kind.ROOT, "All changes", null);
        TreeItem<DiffNode> rootItem = new TreeItem<>(rootData);
        rootItem.setExpanded(true);

        TreeItem<DiffNode> classesItem = new TreeItem<>(
                new DiffNode(DiffNode.Kind.GROUP, "Classes", null));
        classesItem.setExpanded(true);

        TreeItem<DiffNode> added = groupItem("Added", ChangeType.ADDED);
        TreeItem<DiffNode> removed = groupItem("Removed", ChangeType.REMOVED);
        TreeItem<DiffNode> modified = groupItem("Modified", ChangeType.MODIFIED);

        for (ClassDiff c : result.classes()) {
            switch (c.change()) {
                case ADDED -> added.getChildren().add(classItem(c));
                case REMOVED -> removed.getChildren().add(classItem(c));
                case MODIFIED -> modified.getChildren().add(classItem(c));
                case UNCHANGED -> {
                }
            }
        }
        if (!added.getChildren().isEmpty()) classesItem.getChildren().add(added);
        if (!removed.getChildren().isEmpty()) classesItem.getChildren().add(removed);
        if (!modified.getChildren().isEmpty()) classesItem.getChildren().add(modified);

        TreeItem<DiffNode> resourcesItem = new TreeItem<>(
                new DiffNode(DiffNode.Kind.GROUP, "Resources", null));
        resourcesItem.setExpanded(true);

        TreeItem<DiffNode> resAdded = groupItem("Added", ChangeType.ADDED);
        TreeItem<DiffNode> resRemoved = groupItem("Removed", ChangeType.REMOVED);
        TreeItem<DiffNode> resModified = groupItem("Modified", ChangeType.MODIFIED);
        for (ResourceDiff r : result.resources()) {
            TreeItem<DiffNode> item = new TreeItem<>(new DiffNode(DiffNode.Kind.RESOURCE, r.path(), r));
            switch (r.change()) {
                case ADDED -> resAdded.getChildren().add(item);
                case REMOVED -> resRemoved.getChildren().add(item);
                case MODIFIED -> resModified.getChildren().add(item);
                case UNCHANGED -> {
                }
            }
        }
        if (!resAdded.getChildren().isEmpty()) resourcesItem.getChildren().add(resAdded);
        if (!resRemoved.getChildren().isEmpty()) resourcesItem.getChildren().add(resRemoved);
        if (!resModified.getChildren().isEmpty()) resourcesItem.getChildren().add(resModified);

        rootItem.getChildren().addAll(classesItem, resourcesItem);
        tree.setRoot(rootItem);
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new DiffCell());
    }

    private static TreeItem<DiffNode> groupItem(String label, ChangeType change) {
        TreeItem<DiffNode> i = new TreeItem<>(new DiffNode(DiffNode.Kind.CATEGORY, label, change));
        i.setExpanded(true);
        return i;
    }

    private static TreeItem<DiffNode> classItem(ClassDiff c) {
        return new TreeItem<>(new DiffNode(DiffNode.Kind.CLASS, c.simpleName(), c));
    }

    private void showDetail(DiffNode node) {
        detailArea.getChildren().clear();
        if (node == null || node.payload() == null) {
            detailArea.setCenter(hintLabel("Select an item to see details"));
            return;
        }

        if (node.payload() instanceof ClassDiff cd) {
            detailArea.setCenter(buildClassDetail(cd));
        } else if (node.payload() instanceof ResourceDiff rd) {
            detailArea.setCenter(buildResourceDetail(rd));
        }
    }

    private javafx.scene.Node buildClassDetail(ClassDiff cd) {
        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        content.getStyleClass().add("diff-detail");

        Label title = new Label(cd.classFqn());
        title.getStyleClass().add("diff-detail-title");
        content.getChildren().add(title);

        Label status = new Label("Change: " + cd.change());
        status.getStyleClass().add("diff-" + cd.change().name().toLowerCase());
        content.getChildren().add(status);

        if (!cd.headerChanges().isEmpty()) {
            Label h = new Label("Class-level changes:");
            h.getStyleClass().add("diff-section-title");
            content.getChildren().add(h);
            for (String change : cd.headerChanges()) {
                Label l = new Label("  • " + change);
                l.getStyleClass().add("diff-detail-line");
                content.getChildren().add(l);
            }
        }

        if (cd.change() == ChangeType.MODIFIED) {
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            tabs.getStyleClass().add("diff-tabs");

            tabs.getTabs().add(new Tab("Methods", buildMemberList(cd.methods())));
            tabs.getTabs().add(new Tab("Fields", buildMemberList(cd.fields())));
            tabs.getTabs().add(new Tab("Bytecode", buildSideBySide(cd.bytesA(), cd.bytesB())));
            for (Tab t : tabs.getTabs()) t.setClosable(false);
            VBox.setVgrow(tabs, Priority.ALWAYS);
            content.getChildren().add(tabs);
        } else if (cd.change() == ChangeType.ADDED || cd.change() == ChangeType.REMOVED) {
            byte[] bytes = cd.change() == ChangeType.ADDED ? cd.bytesB() : cd.bytesA();
            CodeView view = new CodeView(BytecodeHighlighter::compute);
            try {
                view.setText(bytecodePrinter.print(bytes));
            } catch (Exception ex) {
                view.setText("// Failed to print bytecode: " + ex.getMessage());
            }
            VBox.setVgrow(view, Priority.ALWAYS);
            content.getChildren().add(view);
        }

        return content;
    }

    private javafx.scene.Node buildMemberList(java.util.List<MemberDiff> members) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(8));
        if (members.isEmpty()) {
            Label l = new Label("No changes");
            l.getStyleClass().add("diff-hint");
            box.getChildren().add(l);
            return box;
        }
        for (MemberDiff m : members) {
            if (m.change() == ChangeType.UNCHANGED) continue;
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(3, 8, 3, 8));

            FontIcon icon = new FontIcon(m.change() == ChangeType.ADDED ? "mdi2p-plus"
                    : m.change() == ChangeType.REMOVED ? "mdi2m-minus"
                    : "mdi2c-circle-medium");
            icon.setIconSize(14);
            icon.getStyleClass().add("diff-member-icon-" + m.change().name().toLowerCase());

            Label kind = new Label(m.kind() == MemberDiff.Kind.METHOD ? "method" : "field");
            kind.getStyleClass().add("diff-member-kind");

            Label label = new Label(m.label());
            label.getStyleClass().add("diff-member-label");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label detail = new Label(m.detail());
            detail.getStyleClass().add("diff-member-detail");

            row.getChildren().addAll(icon, kind, label, spacer, detail);
            row.getStyleClass().add("diff-member-row-" + m.change().name().toLowerCase());
            box.getChildren().add(row);
        }
        var scroll = new javafx.scene.control.ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("diff-member-scroll");
        return scroll;
    }

    private javafx.scene.Node buildSideBySide(byte[] a, byte[] b) {
        CodeView left = new CodeView(BytecodeHighlighter::compute);
        CodeView right = new CodeView(BytecodeHighlighter::compute);
        try {
            left.setText(a == null ? "// (not present)" : bytecodePrinter.print(a));
        } catch (Exception ex) {
            left.setText("// Error: " + ex.getMessage());
        }
        try {
            right.setText(b == null ? "// (not present)" : bytecodePrinter.print(b));
        } catch (Exception ex) {
            right.setText("// Error: " + ex.getMessage());
        }

        Label hdrA = new Label(result.jarA().getFileName().toString());
        Label hdrB = new Label(result.jarB().getFileName().toString());
        hdrA.getStyleClass().add("diff-side-header");
        hdrB.getStyleClass().add("diff-side-header");

        VBox leftBox = new VBox(hdrA, left);
        VBox rightBox = new VBox(hdrB, right);
        VBox.setVgrow(left, Priority.ALWAYS);
        VBox.setVgrow(right, Priority.ALWAYS);

        SplitPane sp = new SplitPane(leftBox, rightBox);
        sp.setDividerPositions(0.5);
        return sp;
    }

    private javafx.scene.Node buildResourceDetail(ResourceDiff rd) {
        VBox content = new VBox(6);
        content.setPadding(new Insets(12));
        content.getStyleClass().add("diff-detail");

        Label title = new Label(rd.path());
        title.getStyleClass().add("diff-detail-title");
        content.getChildren().add(title);

        Label status = new Label("Change: " + rd.change());
        status.getStyleClass().add("diff-" + rd.change().name().toLowerCase());
        content.getChildren().add(status);

        Label sizes = new Label(String.format("Size: %d → %d bytes", rd.sizeA(), rd.sizeB()));
        sizes.getStyleClass().add("diff-detail-line");
        content.getChildren().add(sizes);

        return content;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("diff-hint");
        l.setPadding(new Insets(20));
        return l;
    }

    private record DiffNode(Kind kind, String label, Object payload) {
        enum Kind { ROOT, GROUP, CATEGORY, CLASS, RESOURCE }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class DiffCell extends javafx.scene.control.TreeCell<DiffNode> {
        @Override
        protected void updateItem(DiffNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.label());
            FontIcon icon = switch (item.kind()) {
                case ROOT -> new FontIcon("mdi2c-compare");
                case GROUP -> new FontIcon("mdi2f-folder-outline");
                case CATEGORY -> {
                    ChangeType ct = (ChangeType) item.payload();
                    yield new FontIcon(switch (ct) {
                        case ADDED -> "mdi2p-plus-circle-outline";
                        case REMOVED -> "mdi2m-minus-circle-outline";
                        case MODIFIED -> "mdi2p-pencil-outline";
                        case UNCHANGED -> "mdi2e-equal";
                    });
                }
                case CLASS -> {
                    ClassDiff c = (ClassDiff) item.payload();
                    yield new FontIcon(switch (c.change()) {
                        case ADDED -> "mdi2p-plus";
                        case REMOVED -> "mdi2m-minus";
                        case MODIFIED -> "mdi2c-circle-medium";
                        case UNCHANGED -> "mdi2e-equal";
                    });
                }
                case RESOURCE -> {
                    ResourceDiff r = (ResourceDiff) item.payload();
                    yield new FontIcon(switch (r.change()) {
                        case ADDED -> "mdi2p-plus";
                        case REMOVED -> "mdi2m-minus";
                        case MODIFIED -> "mdi2c-circle-medium";
                        case UNCHANGED -> "mdi2e-equal";
                    });
                }
            };
            icon.setIconSize(13);
            icon.getStyleClass().add("diff-tree-icon");
            if (item.payload() instanceof ClassDiff c) {
                icon.getStyleClass().add("diff-icon-" + c.change().name().toLowerCase());
            } else if (item.payload() instanceof ResourceDiff r) {
                icon.getStyleClass().add("diff-icon-" + r.change().name().toLowerCase());
            } else if (item.payload() instanceof ChangeType ct) {
                icon.getStyleClass().add("diff-icon-" + ct.name().toLowerCase());
            }
            setGraphic(icon);
        }
    }
}
