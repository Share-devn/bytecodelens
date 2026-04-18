package dev.share.bytecodelens.crypto;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds methods that look like String decryptors:
 *   static, takes at least one String, returns String, small body,
 *   no calls into user code beyond basic String / StringBuilder / Integer / Character ops.
 */
public final class DecryptCandidateFinder {

    private static final int MAX_INSN = 600;

    public Map<String, DecryptCandidate> findAll(LoadedJar jar) {
        Map<String, DecryptCandidate> out = new HashMap<>();
        for (ClassEntry entry : jar.classes()) {
            scanClass(entry, out);
        }
        return out;
    }

    private void scanClass(ClassEntry entry, Map<String, DecryptCandidate> out) {
        ClassNode node = new ClassNode();
        new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
            if (!isStringDecryptShape(m.desc)) continue;
            if (m.instructions == null) continue;
            int insnCount = m.instructions.size();
            if (insnCount < 5 || insnCount > MAX_INSN) continue;
            int score = scoreMethod(m);
            if (score <= 0) continue;

            DecryptCandidate c = new DecryptCandidate(
                    entry.internalName(), m.name, m.desc,
                    entry.bytes(), insnCount, score);
            out.put(key(entry.internalName(), m.name, m.desc), c);
        }
    }

    /** Decryptor signatures we handle: (String)String or (ILjava/lang/String;)Ljava/lang/String; */
    private static boolean isStringDecryptShape(String desc) {
        return "(Ljava/lang/String;)Ljava/lang/String;".equals(desc)
                || "(ILjava/lang/String;)Ljava/lang/String;".equals(desc)
                || "(Ljava/lang/String;I)Ljava/lang/String;".equals(desc);
    }

    /**
     * Score heuristic — higher = more likely a string decryptor.
     * Bonus for XOR/shift/add on char values, string creation, small size.
     * Penalty for reflection, exec, file io, synchronization, monitor.
     */
    private static int scoreMethod(MethodNode m) {
        int score = 0;
        boolean sawStringCtor = false;
        boolean sawArithOnChars = false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) continue;
            switch (op) {
                case Opcodes.IXOR, Opcodes.IAND, Opcodes.IOR, Opcodes.ISHR, Opcodes.IUSHR,
                     Opcodes.ISHL, Opcodes.IADD, Opcodes.ISUB -> sawArithOnChars = true;
                case Opcodes.NEWARRAY -> score += 1;
                default -> {}
            }
            if (insn instanceof MethodInsnNode mi) {
                String owner = mi.owner;
                if ("java/lang/String".equals(owner)) {
                    if ("<init>".equals(mi.name)) sawStringCtor = true;
                    score += 1;
                } else if ("java/lang/StringBuilder".equals(owner)
                        || "java/lang/Integer".equals(owner)
                        || "java/lang/Character".equals(owner)) {
                    // neutral
                } else if (owner.startsWith("java/io/")
                        || owner.startsWith("java/net/")
                        || owner.startsWith("java/nio/")
                        || "java/lang/Runtime".equals(owner)
                        || "java/lang/reflect/Method".equals(owner)
                        || "java/lang/Class".equals(owner) && "forName".equals(mi.name)) {
                    return 0; // decryptors don't do file/network/reflection
                } else if (owner.startsWith("java/")) {
                    score -= 1;
                } else {
                    // Calls into user classes are not supported by the simulator
                    score -= 2;
                }
            } else if (insn instanceof FieldInsnNode fi) {
                // Decryptors sometimes use static fields; tolerate but don't reward
                if (fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.PUTSTATIC) {
                    score += 0;
                }
            }
        }
        if (sawArithOnChars) score += 3;
        if (sawStringCtor) score += 2;
        return score;
    }

    public static String key(String owner, String name, String desc) {
        return owner + "#" + name + desc;
    }
}
