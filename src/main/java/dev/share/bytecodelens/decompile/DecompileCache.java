package dev.share.bytecodelens.decompile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded LRU cache mapping {@code (internalName, engineName, bytesHash)} →
 * decompiled text. Lets the UI re-open a class instantly after it has been
 * rendered once, and lets the background warmer pre-compute neighbours of the
 * currently viewed class.
 *
 * <p>Keys include a content hash of the class bytes so a hot-reload (which
 * replaces the {@code byte[]} for the same {@code internalName}) automatically
 * invalidates without any explicit eviction call.</p>
 *
 * <p>Capacity defaults to 256 entries — covers a typical "browsing one package"
 * workflow without bounding heap on huge jars. Eviction is access-order LRU.</p>
 */
public final class DecompileCache {

    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final LinkedHashMap<String, String> map;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public DecompileCache() {
        this(DEFAULT_CAPACITY);
    }

    public DecompileCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        // accessOrder=true makes get() also reorder, so eviction follows true LRU.
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > DecompileCache.this.capacity;
            }
        };
    }

    /** Compose the cache key — exposed for tests; production callers use {@link #get}. */
    public static String key(String internalName, String engineName, byte[] classBytes) {
        return internalName + "|" + engineName + "|" + sha1Hex(classBytes);
    }

    public synchronized String get(String internalName, String engineName, byte[] classBytes) {
        String k = key(internalName, engineName, classBytes);
        String v = map.get(k);
        if (v != null) hits.incrementAndGet();
        else misses.incrementAndGet();
        return v;
    }

    public synchronized void put(String internalName, String engineName, byte[] classBytes, String text) {
        if (text == null) return;
        map.put(key(internalName, engineName, classBytes), text);
    }

    public synchronized int size() { return map.size(); }

    public int capacity() { return capacity; }

    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }

    public synchronized void clear() {
        map.clear();
        hits.set(0);
        misses.set(0);
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory in every JRE — fall back to length+hashCode if it ever isn't.
            return (bytes == null ? 0 : bytes.length) + ":" + (bytes == null ? 0 : java.util.Arrays.hashCode(bytes));
        }
    }
}
