package dev.share.bytecodelens.agent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Client side of the {@link BytecodeLensAgent} wire protocol. Holds one TCP connection
 * for the whole session and multiplexes op codes over it.
 *
 * <p>Thread-safe via an internal lock — multiple callers (e.g. JVM Inspector polling
 * in the background while user triggers DUMP_ALL from the UI) serialize transparently.
 * Closing releases the socket (the agent stays loaded in the target — it's just the
 * listener that ends).</p>
 */
public final class AttachClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    /**
     * One request/response exchange at a time. The wire protocol has no framing
     * beyond per-op sequencing, so interleaving e.g. DUMP_ALL and JVM_STATE on the
     * same socket produced the "malformed input around byte 15" failure we saw.
     */
    private final Object wireLock = new Object();

    public AttachClient(int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
        // No read timeout — live-session fetches of 30k+ classes legitimately take minutes
        // on first enumeration. The agent is localhost-only, so we trust it not to hang.
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** Enumerate every class currently loaded in the target JVM. */
    public List<String> listClasses() throws IOException {
        synchronized (wireLock) {
            out.writeByte(BytecodeLensAgent.OP_LIST);
            out.flush();
            int status = in.readUnsignedByte();
            if (status != BytecodeLensAgent.STATUS_OK) {
                throw new IOException("agent returned status " + status + " for LIST");
            }
            int n = in.readInt();
            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < n; i++) names.add(in.readUTF());
            return names;
        }
    }

    /**
     * Ask the agent for a full JVM state snapshot. Returns the raw JSON string; parsing
     * into {@link dev.share.bytecodelens.jvminspect.JvmStateSnapshot} happens above us.
     * Payload is length-prefixed UTF-8 (not writeUTF) because thread-heavy JVMs produce
     * snapshots well above the 64KB writeUTF limit.
     */
    public String fetchJvmState() throws IOException {
        synchronized (wireLock) {
            out.writeByte(BytecodeLensAgent.OP_JVM_STATE);
            out.flush();
            int status = in.readUnsignedByte();
            if (status != BytecodeLensAgent.STATUS_OK) {
                String msg = in.readUTF();
                throw new IOException("agent JVM_STATE failed: " + msg);
            }
            int len = in.readInt();
            byte[] bytes = in.readNBytes(len);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Fetch the bytecode for a single loaded class. Returns null if not found. */
    public byte[] getClass(String dottedName) throws IOException {
        synchronized (wireLock) {
            out.writeByte(BytecodeLensAgent.OP_GET);
            out.writeUTF(dottedName);
            out.flush();
            int status = in.readUnsignedByte();
            if (status == BytecodeLensAgent.STATUS_NOT_FOUND) return null;
            if (status != BytecodeLensAgent.STATUS_OK) {
                String msg = (status == BytecodeLensAgent.STATUS_ERROR) ? in.readUTF() : "status=" + status;
                throw new IOException("agent GET failed: " + msg);
            }
            int len = in.readInt();
            return in.readNBytes(len);
        }
    }

    /**
     * Stream every loadable class as (name, bytes) pairs. Callback invoked per class;
     * return {@code false} to abort the stream early. Much faster than LIST+GET loop
     * because the agent uses a single batched retransform pass rather than per-class
     * classloader lookups (which deadlock against custom classloaders).
     *
     * <p>Holds the wire lock for the entire dump — concurrent JVM_STATE polls are
     * queued and serve after the dump drains. This can block the Inspector's 2-second
     * refresh for the duration of DUMP_ALL (usually 10–30 s), but that's preferable to
     * a protocol collision crashing the session.</p>
     */
    public void dumpAll(java.util.function.BiPredicate<String, byte[]> consumer) throws IOException {
        synchronized (wireLock) {
            out.writeByte(BytecodeLensAgent.OP_DUMP_ALL);
            out.flush();
            int status = in.readUnsignedByte();
            if (status != BytecodeLensAgent.STATUS_OK) {
                throw new IOException("agent returned status " + status + " for DUMP_ALL");
            }
            while (true) {
                String name;
                int len;
                try {
                    name = in.readUTF();
                    len = in.readInt();
                } catch (java.io.EOFException eof) {
                    return;
                }
                if (name.isEmpty() && len == 0) return; // terminator
                byte[] bytes;
                try {
                    bytes = in.readNBytes(len);
                } catch (java.io.EOFException eof) {
                    return;
                }
                boolean keepGoing = consumer.test(name, bytes);
                if (!keepGoing) {
                    while (true) {
                        try {
                            String n = in.readUTF();
                            int l = in.readInt();
                            if (n.isEmpty() && l == 0) return;
                            in.skipNBytes(l);
                        } catch (java.io.EOFException eof) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /** Redefine one class in the target. Returns true on success, false + logs error otherwise. */
    public boolean redefine(String dottedName, byte[] newBytes) throws IOException {
        synchronized (wireLock) {
            out.writeByte(BytecodeLensAgent.OP_REDEF);
            out.writeUTF(dottedName);
            out.writeInt(newBytes.length);
            out.write(newBytes);
            out.flush();
            int status = in.readUnsignedByte();
            if (status == BytecodeLensAgent.STATUS_OK) return true;
            if (status == BytecodeLensAgent.STATUS_ERROR) {
                String msg = in.readUTF();
                throw new IOException("agent REDEF failed: " + msg);
            }
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            out.writeByte(BytecodeLensAgent.OP_BYE);
            out.flush();
            in.readUnsignedByte(); // ACK
        } catch (IOException ignored) {
        } finally {
            socket.close();
        }
    }
}
