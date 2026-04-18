package dev.share.bytecodelens.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Singleton façade over an {@link AppSettings} instance persisted at
 * {@code ~/.bytecodelens/settings.json}.
 *
 * <p>Thread-safe: {@link #get()} returns a snapshot copy, {@link #update} atomically
 * swaps the current state and fires listeners on the calling thread. Listeners must
 * not throw — we swallow their exceptions to keep the settings save path unkillable.</p>
 *
 * <p>Listener contract: registered via {@link #addListener}, invoked with the NEW
 * settings after every successful {@code update}. Also invoked on explicit
 * {@link #reload()}. Not invoked for read-only {@link #get()}.</p>
 */
public final class AppSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(AppSettingsStore.class);

    private static volatile AppSettingsStore instance;

    /** Default file path — {@code ~/.bytecodelens/settings.json}. */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".bytecodelens", "settings.json");
    }

    /** Lazy global instance backed by the default file path. */
    public static AppSettingsStore getInstance() {
        AppSettingsStore s = instance;
        if (s == null) {
            synchronized (AppSettingsStore.class) {
                if (instance == null) {
                    instance = new AppSettingsStore(defaultPath());
                }
                s = instance;
            }
        }
        return s;
    }

    /** Test hook — reset the singleton. Do not call from production. */
    public static void resetInstanceForTests(AppSettingsStore replacement) {
        synchronized (AppSettingsStore.class) {
            instance = replacement;
        }
    }

    private final Path path;
    private volatile AppSettings current;
    private final CopyOnWriteArrayList<Consumer<AppSettings>> listeners = new CopyOnWriteArrayList<>();

    public AppSettingsStore(Path path) {
        this.path = path;
        this.current = AppSettingsJson.readOrDefaults(path);
    }

    /** Current settings, as a defensive copy. */
    public AppSettings get() {
        return current.copy();
    }

    /** Raw (non-copied) live reference — internal use only. */
    AppSettings raw() { return current; }

    public Path path() { return path; }

    /**
     * Swap in a new settings instance, persist to disk, notify listeners.
     * Callers should build the draft from {@link #get()} to avoid aliasing the previous state.
     */
    public synchronized void update(AppSettings updated) {
        if (updated == null) throw new IllegalArgumentException("updated is null");
        this.current = updated.copy();
        try {
            AppSettingsJson.writeAtomic(this.current, path);
        } catch (IOException ex) {
            log.warn("Failed to persist settings to {}: {}", path, ex.getMessage());
        }
        notifyListeners();
    }

    /** Re-read from disk (e.g. after an external edit) and notify listeners. */
    public synchronized void reload() {
        this.current = AppSettingsJson.readOrDefaults(path);
        notifyListeners();
    }

    /** Reset to defaults and persist. Listeners fire with the fresh state. */
    public synchronized void restoreDefaults() {
        update(AppSettings.defaults());
    }

    public void addListener(Consumer<AppSettings> listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Consumer<AppSettings> listener) {
        if (listener != null) listeners.remove(listener);
    }

    private void notifyListeners() {
        AppSettings snapshot = current.copy();
        for (Consumer<AppSettings> l : listeners) {
            try { l.accept(snapshot); }
            catch (Throwable t) {
                log.warn("Settings listener threw: {}", t.toString());
            }
        }
    }
}
