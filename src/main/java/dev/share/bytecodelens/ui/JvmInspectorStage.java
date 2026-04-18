package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.agent.AttachClient;
import dev.share.bytecodelens.jvminspect.JvmStateParser;
import dev.share.bytecodelens.jvminspect.JvmStateSnapshot;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live JVM inspector window. Fetches {@link JvmStateSnapshot}s from the agent on a
 * periodic schedule and drives a tabbed UI:
 *
 * <ul>
 *     <li><b>Overview</b> — live charts (heap, threads, CPU)</li>
 *     <li><b>Properties</b> — sorted key/value table of System.getProperties()</li>
 *     <li><b>System</b> — OS / CPU / swap</li>
 *     <li><b>Runtime</b> — classpath, args, start time, PID</li>
 *     <li><b>Classes</b> — loaded/unloaded/total counters</li>
 *     <li><b>Compilation</b> — JIT stats</li>
 *     <li><b>Memory</b> — heap + non-heap + per-pool breakdown (Eden, Old, Metaspace)</li>
 *     <li><b>GC</b> — count + accumulated time per collector</li>
 *     <li><b>Threads</b> — every thread with state, blocked/waited counts, full stack,
 *         deadlock banner when detected</li>
 * </ul>
 *
 * <p>Refresh cadence defaults to 2 seconds; a manual Refresh button forces an immediate
 * fetch. All tabs rebuild from the most recent snapshot to keep the code simple.</p>
 */
public final class JvmInspectorStage {

    private static final Logger log = LoggerFactory.getLogger(JvmInspectorStage.class);
    private static final Duration REFRESH_PERIOD = Duration.seconds(2);

    private final Stage stage = new Stage();
    private final AttachClient client;
    private final long pid;
    private final String processLabel;

    // Live state — the most recent snapshot, plus charts that retain history.
    private JvmStateSnapshot current;
    private final javafx.animation.Timeline refreshTimeline;

    // Overview chart series — keep last N samples so the graph has shape.
    private static final int MAX_SAMPLES = 60;
    private final XYChart.Series<Number, Number> heapSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> threadsSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
    private long sampleTick = 0;

    // Referenced tabs we repopulate on every refresh.
    private final TableView<KV> propsTable = new TableView<>();
    private final TextArea systemText = new TextArea();
    private final TextArea runtimeText = new TextArea();
    private final TextArea classesText = new TextArea();
    private final TextArea compilationText = new TextArea();
    private final TableView<PoolRow> poolTable = new TableView<>();
    private final TableView<GcRow> gcTable = new TableView<>();
    private final TableView<ThreadRow> threadTable = new TableView<>();
    private final TextArea threadStackText = new TextArea();
    private final Label deadlockBanner = new Label();
    private final Label statusLabel = new Label("connecting...");
    private final TextField propFilter = new TextField();
    private Map<String, String> lastProps = Map.of();

    public JvmInspectorStage(AttachClient client, long pid, String processLabel, boolean darkTheme) {
        this.client = client;
        this.pid = pid;
        this.processLabel = processLabel == null ? "pid " + pid : processLabel;

        BorderPane root = new BorderPane();
        root.getStyleClass().add("jvm-inspector-root");
        root.setTop(buildHeader());
        root.setCenter(buildTabs());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        root.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");
        stage.setTitle("JVM Inspector — " + this.processLabel);
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        dev.share.bytecodelens.util.Icons.apply(stage);

        // Charts are unparented until the Overview tab is built; assign names here so the
        // first refresh has something to push data into.
        heapSeries.setName("Heap used (MB)");
        threadsSeries.setName("Live threads");
        cpuSeries.setName("Process CPU %");

        refreshTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(REFRESH_PERIOD, e -> refreshAsync()));
        refreshTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);

        stage.setOnShown(e -> { refreshAsync(); refreshTimeline.play(); });
        stage.setOnHidden(e -> refreshTimeline.stop());
    }

    public void show() { stage.show(); }

    // ========================================================================
    // Top header — process identity + manual Refresh button.
    // ========================================================================

    private Runnable onImportClasses;

    /**
     * Wire the "Import classes for editing" button. Fires the heavy DUMP_ALL path on
     * the main controller — intentionally separate from the Inspector's fast refresh
     * so users who only want metrics don't pay the import cost.
     */
    public void setOnImportClasses(Runnable handler) { this.onImportClasses = handler; }

    private HBox buildHeader() {
        Label title = new Label(processLabel);
        title.getStyleClass().add("jvm-inspector-title");
        Label pidLbl = new Label("PID " + pid);
        pidLbl.getStyleClass().add("jvm-inspector-pid");

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setGraphic(new FontIcon("mdi2r-refresh"));
        refreshBtn.setOnAction(e -> refreshAsync());

        Button importBtn = new Button("Import classes for editing");
        importBtn.setGraphic(new FontIcon("mdi2d-download-outline"));
        importBtn.setTooltip(new Tooltip(
                "Fetch every loaded class from the target JVM so you can view and hot-swap them.\n"
                        + "Can take 10–30 seconds on large apps; not required for metrics browsing."));
        importBtn.setOnAction(e -> {
            if (onImportClasses != null) {
                importBtn.setDisable(true);
                importBtn.setText("Importing...");
                onImportClasses.run();
            }
        });

        deadlockBanner.getStyleClass().add("jvm-deadlock-banner");
        deadlockBanner.setVisible(false);
        deadlockBanner.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, title, pidLbl, deadlockBanner, spacer, importBtn, refreshBtn);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("jvm-inspector-header");
        return row;
    }

    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("jvm-inspector-status");
        HBox row = new HBox(statusLabel);
        row.setPadding(new Insets(4, 12, 4, 12));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("jvm-inspector-status-bar");
        return row;
    }

    // ========================================================================
    // Tab builders
    // ========================================================================

    private TabPane buildTabs() {
        TabPane tp = new TabPane();
        tp.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tp.getTabs().addAll(
                tab("Overview", "mdi2c-chart-line", buildOverviewTab()),
                tab("Properties", "mdi2f-format-list-bulleted", buildPropertiesTab()),
                tab("System", "mdi2m-monitor-dashboard", wrapScroll(systemText)),
                tab("Runtime", "mdi2c-cog-outline", wrapScroll(runtimeText)),
                tab("Classes", "mdi2c-code-braces", wrapScroll(classesText)),
                tab("Compilation", "mdi2f-flash", wrapScroll(compilationText)),
                tab("Memory", "mdi2m-memory", buildMemoryTab()),
                tab("GC", "mdi2d-delete-outline", buildGcTab()),
                tab("Threads", "mdi2t-timeline-clock-outline", buildThreadsTab()));
        configureReadOnly(systemText, runtimeText, classesText, compilationText, threadStackText);
        return tp;
    }

    private Tab tab(String title, String icon, javafx.scene.Node content) {
        Tab t = new Tab(title, content);
        t.setGraphic(new FontIcon(icon));
        return t;
    }

    private javafx.scene.Node buildOverviewTab() {
        // Three stacked LineCharts sharing category X axis (sample tick number). We use
        // NumberAxis on X so new samples push off the left naturally.
        LineChart<Number, Number> heapChart = newChart("Heap used (MB)");
        heapChart.getData().add(heapSeries);
        LineChart<Number, Number> threadsChart = newChart("Threads");
        threadsChart.getData().add(threadsSeries);
        LineChart<Number, Number> cpuChart = newChart("Process CPU %");
        cpuChart.getData().add(cpuSeries);
        VBox box = new VBox(10, heapChart, threadsChart, cpuChart);
        box.setPadding(new Insets(10));
        VBox.setVgrow(heapChart, Priority.ALWAYS);
        VBox.setVgrow(threadsChart, Priority.ALWAYS);
        VBox.setVgrow(cpuChart, Priority.ALWAYS);
        return box;
    }

    private LineChart<Number, Number> newChart(String title) {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setLabel("t");
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(true);
        LineChart<Number, Number> c = new LineChart<>(x, y);
        c.setTitle(title);
        c.setAnimated(false);
        c.setCreateSymbols(false);
        c.setLegendVisible(false);
        return c;
    }

    private javafx.scene.Node buildPropertiesTab() {
        propFilter.setPromptText("Filter key or value...");
        propFilter.textProperty().addListener((o, a, b) -> repaintProperties());
        TableColumn<KV, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().key));
        keyCol.setPrefWidth(320);
        TableColumn<KV, String> valCol = new TableColumn<>("Value");
        valCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().value));
        valCol.setPrefWidth(600);
        propsTable.getColumns().setAll(List.of(keyCol, valCol));
        propsTable.setPlaceholder(new Label("No properties"));
        VBox box = new VBox(8, propFilter, propsTable);
        VBox.setVgrow(propsTable, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private javafx.scene.Node buildMemoryTab() {
        TableColumn<PoolRow, String> name = new TableColumn<>("Pool");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        name.setPrefWidth(200);
        TableColumn<PoolRow, String> type = new TableColumn<>("Type");
        type.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type));
        TableColumn<PoolRow, String> used = sizeCol("Used", r -> r.used);
        TableColumn<PoolRow, String> committed = sizeCol("Committed", r -> r.committed);
        TableColumn<PoolRow, String> max = sizeCol("Max", r -> r.max);
        TableColumn<PoolRow, String> peak = sizeCol("Peak used", r -> r.peakUsed);
        poolTable.getColumns().setAll(List.of(name, type, used, committed, max, peak));
        poolTable.setPlaceholder(new Label("No pools"));
        VBox box = new VBox(8, poolTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(poolTable, Priority.ALWAYS);
        return box;
    }

    private <R> TableColumn<R, String> sizeCol(String header, java.util.function.ToLongFunction<R> fn) {
        TableColumn<R, String> c = new TableColumn<>(header);
        c.setCellValueFactory(cell -> new SimpleStringProperty(formatBytes(fn.applyAsLong(cell.getValue()))));
        c.setPrefWidth(110);
        return c;
    }

    private javafx.scene.Node buildGcTab() {
        TableColumn<GcRow, String> name = new TableColumn<>("Collector");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        name.setPrefWidth(260);
        TableColumn<GcRow, Number> count = new TableColumn<>("Collections");
        count.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().count));
        count.setPrefWidth(120);
        TableColumn<GcRow, String> time = new TableColumn<>("Total time");
        time.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().timeMs + " ms"));
        time.setPrefWidth(140);
        gcTable.getColumns().setAll(List.of(name, count, time));
        gcTable.setPlaceholder(new Label("No GC data"));
        VBox box = new VBox(8, gcTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(gcTable, Priority.ALWAYS);
        return box;
    }

    private javafx.scene.Node buildThreadsTab() {
        TableColumn<ThreadRow, Number> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().id));
        idCol.setPrefWidth(60);
        TableColumn<ThreadRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        nameCol.setPrefWidth(260);
        TableColumn<ThreadRow, String> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().state));
        stateCol.setPrefWidth(120);
        TableColumn<ThreadRow, Number> blockedCol = new TableColumn<>("Blocked");
        blockedCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().blocked));
        TableColumn<ThreadRow, Number> waitedCol = new TableColumn<>("Waited");
        waitedCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().waited));
        threadTable.getColumns().setAll(List.of(idCol, nameCol, stateCol, blockedCol, waitedCol));
        threadTable.setPlaceholder(new Label("No threads"));
        threadTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            threadStackText.setText(n == null ? "" : String.join("\n", n.stack));
        });

        Label stackHeader = new Label("Stack trace");
        stackHeader.getStyleClass().add("jvm-stack-header");
        threadStackText.setFont(Font.font("JetBrains Mono", 12));
        threadStackText.setEditable(false);

        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane();
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().addAll(threadTable, new VBox(4, stackHeader, threadStackText));
        split.setDividerPositions(0.55);
        VBox.setVgrow(threadStackText, Priority.ALWAYS);
        return split;
    }

    // ========================================================================
    // Refresh — fetch + update every tab
    // ========================================================================

    private void refreshAsync() {
        Thread t = new Thread(() -> {
            try {
                String json = client.fetchJvmState();
                JvmStateSnapshot snap = JvmStateParser.parse(json);
                Platform.runLater(() -> applySnapshot(snap));
            } catch (Exception ex) {
                log.debug("JVM state fetch failed", ex);
                Platform.runLater(() -> statusLabel.setText(
                        "fetch failed: " + ex.getMessage()));
            }
        }, "jvm-inspector-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void applySnapshot(JvmStateSnapshot snap) {
        if (snap == null) return;
        current = snap;
        statusLabel.setText("updated " + new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date(snap.fetchedAtMs())));

        pushChartSamples(snap);
        repaintProperties();
        repaintSystemTab();
        repaintRuntimeTab();
        repaintClassesTab();
        repaintCompilationTab();
        repaintMemoryTab();
        repaintGcTab();
        repaintThreadsTab();
    }

    private void pushChartSamples(JvmStateSnapshot snap) {
        sampleTick++;
        long x = sampleTick;
        if (snap.memory() != null && snap.memory().heap() != null) {
            long usedMB = snap.memory().heap().used() / (1024 * 1024);
            heapSeries.getData().add(new XYChart.Data<>(x, usedMB));
            if (heapSeries.getData().size() > MAX_SAMPLES)
                heapSeries.getData().remove(0);
        }
        if (snap.threads() != null) {
            threadsSeries.getData().add(new XYChart.Data<>(x, snap.threads().threadCount()));
            if (threadsSeries.getData().size() > MAX_SAMPLES)
                threadsSeries.getData().remove(0);
        }
        if (snap.os() != null) {
            double load = Math.max(0, snap.os().processCpuLoad()) * 100;
            cpuSeries.getData().add(new XYChart.Data<>(x, load));
            if (cpuSeries.getData().size() > MAX_SAMPLES)
                cpuSeries.getData().remove(0);
        }
    }

    private void repaintProperties() {
        if (current == null) return;
        lastProps = current.systemProperties() == null ? Map.of() : current.systemProperties();
        String needle = propFilter.getText() == null ? "" : propFilter.getText().trim().toLowerCase();
        List<KV> rows = new ArrayList<>();
        List<String> keys = new ArrayList<>(lastProps.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String k : keys) {
            String v = lastProps.get(k);
            if (!needle.isEmpty()
                    && !k.toLowerCase().contains(needle)
                    && !(v != null && v.toLowerCase().contains(needle))) {
                continue;
            }
            rows.add(new KV(k, v));
        }
        propsTable.setItems(FXCollections.observableArrayList(rows));
    }

    private void repaintSystemTab() {
        if (current == null || current.os() == null) { systemText.setText(""); return; }
        var os = current.os();
        StringBuilder sb = new StringBuilder();
        sb.append("Name:                 ").append(os.name()).append('\n');
        sb.append("Version:              ").append(os.version()).append('\n');
        sb.append("Arch:                 ").append(os.arch()).append('\n');
        sb.append("Available processors: ").append(os.availableProcessors()).append('\n');
        sb.append("System load avg:      ").append(os.systemLoadAverage()).append('\n');
        sb.append('\n');
        sb.append("Free physical memory: ").append(formatBytes(os.freeMemorySize())).append('\n');
        sb.append("Total physical mem:   ").append(formatBytes(os.totalMemorySize())).append('\n');
        sb.append("Committed virtual:    ").append(formatBytes(os.committedVirtualMemorySize())).append('\n');
        sb.append("Free swap:            ").append(formatBytes(os.freeSwapSpaceSize())).append('\n');
        sb.append("Total swap:           ").append(formatBytes(os.totalSwapSpaceSize())).append('\n');
        sb.append('\n');
        sb.append("Process CPU time:     ").append(os.processCpuTime()).append(" ns\n");
        sb.append("Process CPU load:     ").append(fmtPct(os.processCpuLoad())).append('\n');
        sb.append("System CPU load:      ").append(fmtPct(os.cpuLoad())).append('\n');
        systemText.setText(sb.toString());
    }

    private void repaintRuntimeTab() {
        if (current == null || current.runtime() == null) { runtimeText.setText(""); return; }
        var r = current.runtime();
        StringBuilder sb = new StringBuilder();
        sb.append("Name:                 ").append(r.name()).append('\n');
        sb.append("VM name:              ").append(r.vmName()).append('\n');
        sb.append("VM vendor:            ").append(r.vmVendor()).append('\n');
        sb.append("VM version:           ").append(r.vmVersion()).append('\n');
        sb.append("Spec:                 ").append(r.specName()).append(' ').append(r.specVersion())
                .append(" (").append(r.specVendor()).append(")\n");
        sb.append("PID:                  ").append(r.pid()).append('\n');
        sb.append("Start time:           ").append(new java.util.Date(r.startTime())).append('\n');
        sb.append("Uptime:               ").append(formatUptime(r.uptime())).append('\n');
        sb.append("Boot cp supported:    ").append(r.bootClassPathSupported()).append('\n');
        sb.append('\n');
        sb.append("Classpath:\n  ").append(r.classPath().replace(java.io.File.pathSeparator, "\n  ")).append('\n');
        sb.append("\nLibrary path:\n  ").append(r.libraryPath().replace(java.io.File.pathSeparator, "\n  ")).append('\n');
        sb.append("\nInput arguments:\n");
        for (String a : r.inputArguments()) sb.append("  ").append(a).append('\n');
        runtimeText.setText(sb.toString());
    }

    private void repaintClassesTab() {
        if (current == null || current.classLoading() == null) { classesText.setText(""); return; }
        var c = current.classLoading();
        StringBuilder sb = new StringBuilder();
        sb.append("Loaded now:           ").append(c.loadedClassCount()).append('\n');
        sb.append("Total ever loaded:    ").append(c.totalLoadedClassCount()).append('\n');
        sb.append("Unloaded:             ").append(c.unloadedClassCount()).append('\n');
        sb.append("Verbose:              ").append(c.verbose()).append('\n');
        classesText.setText(sb.toString());
    }

    private void repaintCompilationTab() {
        if (current == null || current.compilation() == null) { compilationText.setText(""); return; }
        var c = current.compilation();
        StringBuilder sb = new StringBuilder();
        if (!c.available()) sb.append("Compilation MXBean not available on this JVM.");
        else {
            sb.append("JIT compiler:         ").append(c.name()).append('\n');
            sb.append("Time monitoring:      ").append(c.timeMonitoringSupported()).append('\n');
            sb.append("Total compile time:   ").append(c.totalCompilationTimeMs()).append(" ms\n");
        }
        compilationText.setText(sb.toString());
    }

    private void repaintMemoryTab() {
        if (current == null || current.memoryPools() == null) return;
        List<PoolRow> rows = new ArrayList<>();
        // Prepend summary "heap" and "non-heap" synthetic rows so the user sees the
        // aggregate alongside the per-pool breakdown.
        var mem = current.memory();
        if (mem != null) {
            if (mem.heap() != null) rows.add(PoolRow.fromUsage("Heap (aggregate)", "HEAP", mem.heap(), null));
            if (mem.nonHeap() != null)
                rows.add(PoolRow.fromUsage("Non-heap (aggregate)", "NON_HEAP", mem.nonHeap(), null));
        }
        for (var p : current.memoryPools()) {
            rows.add(PoolRow.fromUsage(p.name(), p.type(), p.usage(), p.peak()));
        }
        poolTable.setItems(FXCollections.observableArrayList(rows));
    }

    private void repaintGcTab() {
        if (current == null || current.gc() == null) return;
        List<GcRow> rows = new ArrayList<>();
        for (var g : current.gc()) {
            rows.add(new GcRow(g.name(), g.collectionCount(), g.collectionTimeMs()));
        }
        rows.sort(Comparator.comparing((GcRow r) -> r.name));
        gcTable.setItems(FXCollections.observableArrayList(rows));
    }

    private void repaintThreadsTab() {
        if (current == null || current.threads() == null) return;
        var t = current.threads();
        List<ThreadRow> rows = new ArrayList<>();
        java.util.Set<Long> deadlocked = new java.util.HashSet<>(
                t.deadlocked() == null ? List.of() : t.deadlocked());
        for (var ti : t.list()) {
            rows.add(new ThreadRow(ti.id(), ti.name(), ti.state(),
                    ti.blockedCount(), ti.waitedCount(),
                    deadlocked.contains(ti.id()),
                    ti.stack()));
        }
        rows.sort(Comparator.comparing((ThreadRow r) -> r.name, String.CASE_INSENSITIVE_ORDER));
        threadTable.setItems(FXCollections.observableArrayList(rows));
        if (!deadlocked.isEmpty()) {
            deadlockBanner.setText("⚠ Deadlock detected in " + deadlocked.size() + " thread"
                    + (deadlocked.size() == 1 ? "" : "s"));
            deadlockBanner.setVisible(true);
            deadlockBanner.setManaged(true);
        } else {
            deadlockBanner.setVisible(false);
            deadlockBanner.setManaged(false);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static ScrollPane wrapScroll(TextArea ta) {
        ta.setWrapText(false);
        ScrollPane sp = new ScrollPane(ta);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        return sp;
    }

    private static void configureReadOnly(TextArea... areas) {
        for (TextArea a : areas) {
            a.setEditable(false);
            a.setFont(Font.font("JetBrains Mono", 12));
            a.setWrapText(false);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long rem = s % 60;
        return String.format("%dh %02dm %02ds", h, m, rem);
    }

    private static String fmtPct(double v) {
        if (v < 0) return "?";
        return String.format("%.1f%%", v * 100);
    }

    // ========================================================================
    // Row records
    // ========================================================================

    private record KV(String key, String value) {}

    private static final class PoolRow {
        final String name, type;
        final long used, committed, max, peakUsed;
        PoolRow(String name, String type, long used, long committed, long max, long peakUsed) {
            this.name = name; this.type = type;
            this.used = used; this.committed = committed; this.max = max;
            this.peakUsed = peakUsed;
        }
        static PoolRow fromUsage(String name, String type,
                                 JvmStateSnapshot.MemoryUsage usage,
                                 JvmStateSnapshot.MemoryUsage peak) {
            long u = usage == null ? 0 : usage.used();
            long c = usage == null ? 0 : usage.committed();
            long mx = usage == null ? -1 : usage.max();
            long p = peak == null ? -1 : peak.used();
            return new PoolRow(name, type, u, c, mx, p);
        }
    }

    private record GcRow(String name, long count, long timeMs) {}

    private record ThreadRow(long id, String name, String state,
                             long blocked, long waited,
                             boolean deadlocked,
                             List<String> stack) {}
}
