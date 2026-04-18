package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Restores readable names on obfuscated enum constants by reading the literal name
 * passed to the {@code Enum.<init>(String, int)} super-call inside {@code <clinit>}.
 *
 * <p>Compilers always emit the canonical pattern for each enum constant:
 * <pre>
 *   NEW p/E
 *   DUP
 *   LDC "ORIGINAL_NAME"   ; literal name is preserved verbatim
 *   ICONST_N              ; ordinal
 *   INVOKESPECIAL p/E.&lt;init&gt;(Ljava/lang/String;I)V
 *   PUTSTATIC p/E.someField : Lp/E;
 * </pre>
 * Obfuscators rename {@code someField} but leave the LDC literal alone (otherwise
 * {@code Enum.name()} would lie). When the field name is a junk identifier (e.g. {@code l},
 * {@code aaa}, anything not equal to the literal) and the literal is itself a valid Java
 * identifier, this transform renames the field to match.</p>
 *
 * <p>Per-class scope: only fields and field accesses inside the same class are rewritten.
 * Cross-class {@code GETSTATIC p/E.l} calls in other classes survive untouched and would
 * need a jar-level rename pass to also fix them.</p>
 */
public final class EnumNameRestoration implements Transformation {

    @Override public String id() { return "enum-name-restoration"; }
    @Override public String name() { return "Enum Name Restoration"; }
    @Override public String description() {
        return "Recover obfuscated enum constant names from the literal in <clinit>.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if ((node.access & Opcodes.ACC_ENUM) == 0) return;
        if (node.methods == null || node.fields == null) return;

        MethodNode clinit = findClinit(node);
        if (clinit == null || clinit.instructions == null) return;

        Map<String, String> renames = collectRenames(node, clinit);
        if (renames.isEmpty()) return;

        renameFields(node, renames, ctx);
        rewriteFieldRefsInClass(node, renames);
    }

    private static MethodNode findClinit(ClassNode node) {
        for (MethodNode m : node.methods) {
            if ("<clinit>".equals(m.name) && "()V".equals(m.desc)) return m;
        }
        return null;
    }

    /** Walk &lt;clinit&gt; for the canonical NEW/DUP/LDC/.../INVOKESPECIAL/PUTSTATIC pattern. */
    private Map<String, String> collectRenames(ClassNode node, MethodNode clinit) {
        Map<String, String> out = new HashMap<>();
        AbstractInsnNode insn = clinit.instructions.getFirst();
        while (insn != null) {
            // NEW node.name
            if (insn.getOpcode() == Opcodes.NEW
                    && insn instanceof TypeInsnNode tn
                    && tn.desc.equals(node.name)) {
                AbstractInsnNode dup = nextReal(insn);
                if (dup != null && dup.getOpcode() == Opcodes.DUP) {
                    AbstractInsnNode ldc = nextReal(dup);
                    if (ldc instanceof LdcInsnNode l && l.cst instanceof String literal) {
                        // After LDC name + ICONST_n + (anything else for enum classes with
                        // extra ctor args) we need the matching INVOKESPECIAL <init> + PUTSTATIC.
                        AbstractInsnNode cursor = nextReal(ldc);
                        // Skip until INVOKESPECIAL <init> on this class.
                        while (cursor != null) {
                            if (cursor instanceof MethodInsnNode mi
                                    && mi.getOpcode() == Opcodes.INVOKESPECIAL
                                    && mi.owner.equals(node.name)
                                    && "<init>".equals(mi.name)) {
                                AbstractInsnNode put = nextReal(cursor);
                                if (put instanceof FieldInsnNode fi
                                        && fi.getOpcode() == Opcodes.PUTSTATIC
                                        && fi.owner.equals(node.name)) {
                                    if (isValidJavaIdentifier(literal)
                                            && !literal.equals(fi.name)
                                            && !out.containsValue(literal)
                                            && fieldExists(node, fi.name)) {
                                        out.put(fi.name, literal);
                                    }
                                }
                                break;
                            }
                            // Bail if we wander into another NEW — the pattern was malformed.
                            if (cursor.getOpcode() == Opcodes.NEW) break;
                            cursor = nextReal(cursor);
                        }
                    }
                }
            }
            insn = insn.getNext();
        }
        return out;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode n) {
        AbstractInsnNode cur = n.getNext();
        while (cur != null && cur.getOpcode() == -1) cur = cur.getNext();
        return cur;
    }

    private static boolean fieldExists(ClassNode node, String name) {
        for (FieldNode f : node.fields) {
            if (f.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        // Reject Java reserved words as targets.
        return !RESERVED.contains(s);
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while");

    private void renameFields(ClassNode node, Map<String, String> renames, TransformContext ctx) {
        for (FieldNode f : node.fields) {
            String nu = renames.get(f.name);
            if (nu != null) {
                f.name = nu;
                ctx.inc("fields-renamed");
            }
        }
    }

    private void rewriteFieldRefsInClass(ClassNode node, Map<String, String> renames) {
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode fi
                        && fi.owner.equals(node.name)
                        && renames.containsKey(fi.name)) {
                    fi.name = renames.get(fi.name);
                }
            }
        }
    }
}
