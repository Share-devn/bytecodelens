package dev.share.bytecodelens;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.text.Font;

import java.util.Objects;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage stage) throws Exception {
        loadFonts();
        // Default to light theme — users expect a familiar IDE look on first launch.
        // WorkspaceState.darkTheme still persists the user's preference across sessions.
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Scene scene = new Scene(loader.load(), 1400, 860);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/css/app.css")).toExternalForm());

        stage.setTitle("BytecodeLens");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(680);

        dev.share.bytecodelens.util.Icons.apply(stage);

        stage.show();
        log.info("BytecodeLens started");
    }

    public static void main(String[] args) {
        // CLI mode: if argv starts with a known subcommand we never load JavaFX. Lets
        // headless callers (CI, batch jobs) skip the entire desktop runtime.
        if (args.length > 0 && isCliCommand(args[0])) {
            int code = new dev.share.bytecodelens.cli.Cli().run(args);
            System.exit(code);
        }
        launch(args);
    }

    private static boolean isCliCommand(String first) {
        return switch (first) {
            case "decompile", "analyze", "mappings", "help", "-h", "--help" -> true;
            default -> false;
        };
    }

    private static void loadFonts() {
        String[] paths = {
                "/fonts/JetBrainsMono-Regular.ttf",
                "/fonts/JetBrainsMono-Bold.ttf",
                "/fonts/JetBrainsMono-Italic.ttf"
        };
        for (String path : paths) {
            try (var in = App.class.getResourceAsStream(path)) {
                if (in == null) {
                    log.warn("Font resource not found: {}", path);
                    continue;
                }
                Font font = Font.loadFont(in, 12);
                if (font == null) {
                    log.warn("Failed to load font: {}", path);
                } else {
                    log.debug("Loaded font: {} ({})", font.getName(), path);
                }
            } catch (Exception ex) {
                log.warn("Failed to load font {}: {}", path, ex.getMessage());
            }
        }
    }
}
