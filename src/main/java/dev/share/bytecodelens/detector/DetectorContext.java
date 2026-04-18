package dev.share.bytecodelens.detector;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.model.LoadedJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-computes common statistics about the loaded jar so individual detectors
 * don't have to re-scan the whole thing. Built once per detection run.
 */
public final class DetectorContext {

    private final LoadedJar jar;
    private final List<ClassNode> nodes = new ArrayList<>();

    // Pre-computed stats
    public int classCount;
    public int shortNamedClasses;   // simple name 1-2 chars
    public int zkmStyleClasses;     // [IlL1]{4+}
    public int allatoriStyleClasses; // long confusable names
    public int unicodeNamedClasses; // non-ASCII in names
    public int classesWithNativeMethods;
    public int staticStringDecryptCandidates;
    public int tableSwitchHeavyMethods; // methods with large tableswitch
    public int invokedynamicSites;
    public int encryptedStringLiterals; // LDCs with low entropy or control chars
    public int totalStringLiterals;
    public double avgStringEntropy;

    public final Set<String> ldcStrings = new HashSet<>();
    public final Set<String> invokeDynamicBootstrapOwners = new HashSet<>();
    public final Set<String> classInternalNames = new HashSet<>();

    /**
     * Classes belonging to BytecodeLens itself should be ignored by detectors
     * to avoid self-false-positives when analysing our own shipped jar.
     */
    private static boolean isBytecodeLensOwnClass(String internalName) {
        return internalName.startsWith("dev/share/bytecodelens/");
    }

    public DetectorContext(LoadedJar jar) {
        this.jar = jar;
    }

    public LoadedJar jar() {
        return jar;
    }

    public List<ClassNode> classNodes() {
        return nodes;
    }

    public void build() {
        long entropySum = 0;
        int entropySamples = 0;
        int countedClasses = 0;

        for (ClassEntry entry : jar.classes()) {
            boolean isSelf = isBytecodeLensOwnClass(entry.internalName());
            if (!isSelf) {
                classInternalNames.add(entry.internalName());
            }
            String simple = entry.simpleName();
            if (!isSelf) {
                if (simple.length() <= 2 && simple.matches("[a-zA-Z]+")) shortNamedClasses++;
                if (simple.matches("[IlL1]{4,}")) zkmStyleClasses++;
                if (simple.length() > 20 && simple.matches("[IlO01]+")) allatoriStyleClasses++;
                if (containsNonAscii(simple)) unicodeNamedClasses++;
            }

            ClassNode node = new ClassNode();
            try {
                new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES);
            } catch (Exception ex) {
                continue;
            }
            if (!isSelf) nodes.add(node);

            boolean hasNative = false;
            if (node.methods != null && !isSelf) {
                for (MethodNode m : node.methods) {
                    if ((m.access & Opcodes.ACC_NATIVE) != 0) hasNative = true;
                    if (looksLikeStringDecryptor(m)) staticStringDecryptCandidates++;
                    if (m.instructions == null) continue;
                    int switchCases = 0;
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof TableSwitchInsnNode ts) {
                            int cases = ts.labels == null ? 0 : ts.labels.size();
                            if (cases > switchCases) switchCases = cases;
                        } else if (insn instanceof InvokeDynamicInsnNode idy) {
                            invokedynamicSites++;
                            if (idy.bsm != null && idy.bsm.getOwner() != null) {
                                invokeDynamicBootstrapOwners.add(idy.bsm.getOwner());
                            }
                        } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                            totalStringLiterals++;
                            ldcStrings.add(s);
                            double e = shannonEntropy(s);
                            if (e >= 4.5 && s.length() >= 8) encryptedStringLiterals++;
                            if (containsControlChars(s)) encryptedStringLiterals++;
                            entropySum += (long) (e * 1000);
                            entropySamples++;
                        }
                    }
                    if (switchCases > 50) tableSwitchHeavyMethods++;
                }
            }
            if (hasNative && !isSelf) classesWithNativeMethods++;
            if (!isSelf) countedClasses++;
        }
        classCount = countedClasses;
        avgStringEntropy = entropySamples == 0 ? 0 : (entropySum / (double) entropySamples) / 1000.0;
    }

    public JarResource findResource(String suffix) {
        for (JarResource r : jar.resources()) {
            if (r.path().endsWith(suffix)) return r;
        }
        return null;
    }

    public boolean hasResourceMatching(String substring) {
        for (JarResource r : jar.resources()) {
            if (r.path().contains(substring)) return true;
        }
        return false;
    }

    public List<JarResource> resourcesByKind(JarResource.ResourceKind kind) {
        List<JarResource> out = new ArrayList<>();
        for (JarResource r : jar.resources()) {
            if (r.kind() == kind) out.add(r);
        }
        return out;
    }

    public double ratio(int numerator) {
        return classCount == 0 ? 0 : numerator / (double) classCount;
    }

    private static boolean containsNonAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return true;
        }
        return false;
    }

    private static boolean containsControlChars(String s) {
        int ctl = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') ctl++;
        }
        return ctl > 2 && ctl > s.length() / 10;
    }

    private static double shannonEntropy(String s) {
        if (s == null || s.isEmpty()) return 0;
        int[] freq = new int[256];
        int counted = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i) & 0xff;
            freq[c]++;
            counted++;
        }
        double e = 0;
        for (int f : freq) {
            if (f == 0) continue;
            double p = f / (double) counted;
            e -= p * (Math.log(p) / Math.log(2));
        }
        return e;
    }

    private static boolean looksLikeStringDecryptor(MethodNode m) {
        if ((m.access & Opcodes.ACC_STATIC) == 0) return false;
        if (!"(Ljava/lang/String;)Ljava/lang/String;".equals(m.desc)
                && !"(ILjava/lang/String;)Ljava/lang/String;".equals(m.desc)
                && !"(Ljava/lang/String;I)Ljava/lang/String;".equals(m.desc)) return false;
        if (m.instructions == null) return false;
        int size = m.instructions.size();
        if (size < 8 || size > 400) return false;
        boolean sawXorOrAdd = false;
        boolean sawNewString = false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == Opcodes.IXOR || op == Opcodes.IADD || op == Opcodes.ISUB) sawXorOrAdd = true;
            if (insn instanceof MethodInsnNode mi && "java/lang/String".equals(mi.owner)
                    && "<init>".equals(mi.name)) sawNewString = true;
        }
        return sawXorOrAdd && sawNewString;
    }
}
