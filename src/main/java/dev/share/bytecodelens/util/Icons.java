package dev.share.bytecodelens.util;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared helper for attaching the BytecodeLens app icon to any {@link Stage}.
 *
 * <p>Loads and caches {@code /icons/app.png} (1024px) and {@code /icons/app-512.png} on
 * first access, then hands them out. JavaFX picks the closest-matching size for the
 * native window manager request (title bar, alt-tab, taskbar), so offering two sizes
 * avoids the blurry-when-scaled look on HiDPI displays.</p>
 */
public final class Icons {

    private Icons() {}

    private static final List<Image> CACHED = new ArrayList<>();
    private static boolean loaded = false;

    /** Install the bundled app icon onto {@code stage}. Silent no-op if icons are missing. */
    public static void apply(Stage stage) {
        if (stage == null) return;
        ensureLoaded();
        if (!CACHED.isEmpty()) {
            // setAll is idempotent — calling apply() twice on the same stage just replaces
            // the list with an identical one.
            stage.getIcons().setAll(CACHED);
        }
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        tryLoad("/icons/app.png");
        tryLoad("/icons/app-512.png");
    }

    private static void tryLoad(String path) {
        try (var in = Icons.class.getResourceAsStream(path)) {
            if (in == null) return;
            Image img = new Image(in);
            if (!img.isError()) CACHED.add(img);
        } catch (Exception ignored) {
            // Icon load failures are non-fatal — the stage just uses the JavaFX default.
        }
    }
}
