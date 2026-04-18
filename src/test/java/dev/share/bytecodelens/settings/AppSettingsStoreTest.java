package dev.share.bytecodelens.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsStoreTest {

    @Test
    void newStoreStartsWithDefaultsWhenNoFile(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        assertEquals(AppSettings.UiTheme.LIGHT, store.get().appearance.uiTheme);
    }

    @Test
    void updatePersistsAndNotifies(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        AtomicInteger notified = new AtomicInteger();
        AtomicReference<AppSettings> received = new AtomicReference<>();
        store.addListener(s -> {
            notified.incrementAndGet();
            received.set(s);
        });

        AppSettings draft = store.get();
        draft.appearance.uiTheme = AppSettings.UiTheme.DARK;
        draft.decompiler.cacheCapacity = 1024;
        store.update(draft);

        assertEquals(1, notified.get());
        assertEquals(AppSettings.UiTheme.DARK, received.get().appearance.uiTheme);
        assertEquals(1024, received.get().decompiler.cacheCapacity);

        // New store over the same file picks up the persisted values.
        AppSettingsStore reopened = new AppSettingsStore(tmp.resolve("settings.json"));
        assertEquals(AppSettings.UiTheme.DARK, reopened.get().appearance.uiTheme);
        assertEquals(1024, reopened.get().decompiler.cacheCapacity);
    }

    @Test
    void getReturnsDefensiveCopy(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        AppSettings a = store.get();
        a.appearance.uiTheme = AppSettings.UiTheme.DARK;
        AppSettings b = store.get();
        assertEquals(AppSettings.UiTheme.LIGHT, b.appearance.uiTheme); // unaffected
    }

    @Test
    void listenerExceptionsAreSwallowed(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        AtomicInteger good = new AtomicInteger();
        store.addListener(s -> { throw new RuntimeException("bad listener"); });
        store.addListener(s -> good.incrementAndGet());

        AppSettings draft = store.get();
        draft.appearance.uiTheme = AppSettings.UiTheme.DARK;
        store.update(draft); // must not throw
        assertEquals(1, good.get());
    }

    @Test
    void removeListenerStopsNotifications(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        AtomicInteger count = new AtomicInteger();
        java.util.function.Consumer<AppSettings> lsn = s -> count.incrementAndGet();
        store.addListener(lsn);
        store.update(store.get());
        assertEquals(1, count.get());
        store.removeListener(lsn);
        store.update(store.get());
        assertEquals(1, count.get());
    }

    @Test
    void restoreDefaultsResetsAndNotifies(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        AppSettings draft = store.get();
        draft.decompiler.cacheCapacity = 99;
        store.update(draft);
        AtomicInteger notified = new AtomicInteger();
        store.addListener(s -> notified.incrementAndGet());
        store.restoreDefaults();
        assertEquals(256, store.get().decompiler.cacheCapacity);
        assertEquals(1, notified.get());
    }

    @Test
    void reloadPicksUpExternalChanges(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("settings.json");
        AppSettingsStore store = new AppSettingsStore(file);
        // Write externally, bypassing the store.
        AppSettings external = AppSettings.defaults();
        external.decompiler.cacheCapacity = 777;
        AppSettingsJson.writeAtomic(external, file);
        // Haven't reloaded yet — still old values.
        assertEquals(256, store.get().decompiler.cacheCapacity);
        store.reload();
        assertEquals(777, store.get().decompiler.cacheCapacity);
    }

    @Test
    void updateWithNullThrows(@TempDir Path tmp) {
        AppSettingsStore store = new AppSettingsStore(tmp.resolve("settings.json"));
        assertThrows(IllegalArgumentException.class, () -> store.update(null));
    }
}
