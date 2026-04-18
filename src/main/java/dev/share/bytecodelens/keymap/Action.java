package dev.share.bytecodelens.keymap;

/**
 * Named user-invokable action. Every menu item, toolbar button, and global shortcut
 * routes through one of these so the keymap UI has a single list to offer.
 *
 * <p>The {@code id} is the stable key persisted to disk in {@code keymap.json}; the
 * {@code label} is human-readable (currently English, will be localised later).
 * {@code category} groups actions in the keymap UI (File, Edit, Navigate, View, Help).</p>
 */
public record Action(String id, String label, String category, String defaultAccelerator) {

    /** Convenience — build an action without a default accelerator. */
    public static Action of(String id, String label, String category) {
        return new Action(id, label, category, null);
    }

    public static Action of(String id, String label, String category, String accel) {
        return new Action(id, label, category, accel);
    }
}
