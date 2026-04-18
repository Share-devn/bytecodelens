package dev.share.bytecodelens.crypto;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans the entire jar for LDC-constant -> INVOKESTATIC-decryptor patterns and
 * computes the decrypted value for each call-site using either the symbolic
 * interpreter (default) or reflection (when enabled and simulation fails).
 */
public final class StringDecryptor {

    private static final Logger log = LoggerFactory.getLogger(StringDecryptor.class);

    private final SymbolicInterpreter interpreter = new SymbolicInterpreter();

    public DecryptionResult decrypt(LoadedJar jar, boolean enableReflection) {
        long start = System.currentTimeMillis();
        Map<String, DecryptCandidate> candidates = new DecryptCandidateFinder().findAll(jar);
        log.info("Found {} decryptor candidates", candidates.size());

        List<DecryptedString> results = new ArrayList<>();
        int[] stats = new int[]{0, 0, 0, 0}; // callSites, simHits, reflectionHits, failures

        ReflectionExecutor reflection = enableReflection ? new ReflectionExecutor(jar) : null;

        for (ClassEntry entry : jar.classes()) {
            scanClass(entry, candidates, results, stats, reflection);
        }

        long elapsed = System.currentTimeMillis() - start;
        List<DecryptCandidate> candList = new ArrayList<>(candidates.values());
        return new DecryptionResult(results, candList, stats[0], stats[1], stats[2], stats[3], elapsed);
    }

    private void scanClass(ClassEntry entry,
                           Map<String, DecryptCandidate> candidates,
                           List<DecryptedString> out,
                           int[] stats,
                           ReflectionExecutor reflection) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES);
        } catch (Exception ex) {
            return;
        }
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            int currentLine = 0;
            AbstractInsnNode[] insns = m.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                AbstractInsnNode insn = insns[i];
                if (insn instanceof LineNumberNode ln) { currentLine = ln.line; continue; }
                if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC) {
                    String key = DecryptCandidateFinder.key(mi.owner, mi.name, mi.desc);
                    DecryptCandidate candidate = candidates.get(key);
                    if (candidate == null) continue;

                    // We need to reconstruct the args: LDC string (and optional int) just before the call
                    String encrypted = null;
                    Integer intArg = null;
                    if ("(Ljava/lang/String;)Ljava/lang/String;".equals(mi.desc)) {
                        encrypted = prevString(insns, i);
                    } else if ("(ILjava/lang/String;)Ljava/lang/String;".equals(mi.desc)) {
                        encrypted = prevString(insns, i);
                        intArg = prevIntBeforeString(insns, i);
                    } else if ("(Ljava/lang/String;I)Ljava/lang/String;".equals(mi.desc)) {
                        intArg = prevInt(insns, i);
                        encrypted = prevStringBeforeInt(insns, i);
                    }
                    if (encrypted == null) continue;
                    stats[0]++;

                    String decrypted = trySimulation(candidate, mi.desc, encrypted, intArg);
                    DecryptedString.Mode mode = DecryptedString.Mode.SIMULATION;
                    if (decrypted == null && reflection != null) {
                        decrypted = reflection.tryInvoke(mi.owner, mi.name, mi.desc, encrypted, intArg);
                        if (decrypted != null) mode = DecryptedString.Mode.REFLECTION;
                    }
                    if (decrypted == null) {
                        stats[3]++;
                        continue;
                    }
                    if (mode == DecryptedString.Mode.SIMULATION) stats[1]++;
                    else stats[2]++;

                    out.add(new DecryptedString(
                            entry.name(), m.name, m.desc,
                            encrypted, decrypted,
                            mi.owner, mi.name, mi.desc,
                            mode, currentLine));
                }
            }
        }
    }

    private String trySimulation(DecryptCandidate c, String desc, String encrypted, Integer intArg) {
        if ("(Ljava/lang/String;)Ljava/lang/String;".equals(desc)) {
            return interpreter.invokeStringToString(c.classBytes(), c.ownerInternal(), c.methodName(), c.methodDesc(), encrypted);
        }
        if ("(ILjava/lang/String;)Ljava/lang/String;".equals(desc) && intArg != null) {
            return interpreter.invokeIntStringToString(c.classBytes(), c.ownerInternal(), c.methodName(), c.methodDesc(), intArg, encrypted);
        }
        return null;
    }

    /** Walk back from the invoke and find the closest LDC String. */
    private static String prevString(AbstractInsnNode[] insns, int callIdx) {
        for (int i = callIdx - 1; i >= 0 && i > callIdx - 8; i--) {
            AbstractInsnNode in = insns[i];
            if (in instanceof LdcInsnNode l && l.cst instanceof String s) return s;
            if (in.getOpcode() >= 0) {
                // stop at other real instruction if it's not a potential int push
                int op = in.getOpcode();
                if (!(op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
                        || (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5))) {
                    return null;
                }
            }
        }
        return null;
    }

    /** For (I,String) desc: search for int push before the string push. */
    private static Integer prevIntBeforeString(AbstractInsnNode[] insns, int callIdx) {
        // walk back past the string push first
        int i = callIdx - 1;
        while (i >= 0 && !(insns[i] instanceof LdcInsnNode)) i--;
        i--;
        for (; i >= 0 && i > callIdx - 12; i--) {
            AbstractInsnNode in = insns[i];
            int op = in.getOpcode();
            if (op < 0) continue;
            if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return ((IntInsnNode) in).operand;
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
            if (in instanceof LdcInsnNode l && l.cst instanceof Integer n) return n;
            return null;
        }
        return null;
    }

    private static Integer prevInt(AbstractInsnNode[] insns, int callIdx) {
        for (int i = callIdx - 1; i >= 0 && i > callIdx - 6; i--) {
            AbstractInsnNode in = insns[i];
            int op = in.getOpcode();
            if (op < 0) continue;
            if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return ((IntInsnNode) in).operand;
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
            if (in instanceof LdcInsnNode l && l.cst instanceof Integer n) return n;
            return null;
        }
        return null;
    }

    private static String prevStringBeforeInt(AbstractInsnNode[] insns, int callIdx) {
        // walk back past int push, then find string
        int i = callIdx - 1;
        while (i >= 0) {
            AbstractInsnNode in = insns[i];
            int op = in.getOpcode();
            if (op < 0) { i--; continue; }
            if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
                    || (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
                    || (in instanceof LdcInsnNode l && l.cst instanceof Integer)) {
                i--;
                break;
            }
            return null;
        }
        for (; i >= 0 && i > callIdx - 12; i--) {
            AbstractInsnNode in = insns[i];
            if (in instanceof LdcInsnNode l && l.cst instanceof String s) return s;
            if (in.getOpcode() >= 0) return null;
        }
        return null;
    }
}
