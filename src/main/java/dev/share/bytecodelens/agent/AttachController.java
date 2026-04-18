package dev.share.bytecodelens.agent;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level "attach to a Java process" flow:
 * <ol>
 *     <li>{@link #listProcesses()} — walks {@link VirtualMachine#list()}</li>
 *     <li>{@link #attach(String)} — loads the BytecodeLens agent into the target</li>
 *     <li>the agent writes a port into {@code ~/.bytecodelens/agents/&lt;pid&gt;.port};
 *         we poll that file to learn where to connect</li>
 *     <li>return an {@link AttachClient} the caller uses to drive the agent</li>
 * </ol>
 */
public final class AttachController {

    private static final Logger log = LoggerFactory.getLogger(AttachController.class);

    public record TargetProcess(long pid, String displayName) {}

    public List<TargetProcess> listProcesses() {
        List<TargetProcess> out = new ArrayList<>();
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            try {
                long pid = Long.parseLong(d.id());
                // Skip ourselves — the user attaching BytecodeLens to itself is almost
                // certainly a mistake; show it but mark as self.
                String name = d.displayName();
                if (name == null || name.isEmpty()) name = "(unknown)";
                out.add(new TargetProcess(pid, name));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Load the agent into the given pid and return a live client.
     * Throws if attach fails or the agent never publishes its port.
     */
    public AttachClient attach(String pid) throws IOException {
        Path agentJar;
        try {
            agentJar = AgentJarBuilder.build();
        } catch (IOException ex) {
            throw new IOException("Cannot build agent jar: " + ex.getMessage(), ex);
        }

        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(pid);
        } catch (AttachNotSupportedException ex) {
            throw new IOException("Attach not supported for pid " + pid + ": " + ex.getMessage(), ex);
        }
        try {
            // port=0 tells the agent to pick any free port.
            vm.loadAgent(agentJar.toString(), "port=0");
        } catch (Exception ex) {
            throw new IOException("loadAgent failed for pid " + pid + ": " + ex.getMessage(), ex);
        } finally {
            try { vm.detach(); } catch (IOException ignored) {}
        }

        int port = waitForPortFile(Long.parseLong(pid));
        log.info("Attached to pid {}; agent listening on port {}", pid, port);
        return new AttachClient(port);
    }

    /** Poll ~/.bytecodelens/agents/&lt;pid&gt;.port until it appears or we time out (5s). */
    private int waitForPortFile(long pid) throws IOException {
        Path file = Path.of(System.getProperty("user.home"), ".bytecodelens", "agents", pid + ".port");
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                String content = Files.readString(file).trim();
                try {
                    return Integer.parseInt(content);
                } catch (NumberFormatException ex) {
                    throw new IOException("agent wrote invalid port: '" + content + "'");
                }
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        throw new IOException("Timed out waiting for agent to publish port for pid " + pid);
    }
}
