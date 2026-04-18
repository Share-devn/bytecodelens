package dev.share.bytecodelens.asm;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Jasmin/Krakatau-style bytecode assembler.
 *
 * <p>Supported syntax:</p>
 * <pre>{@code
 * .class public com/foo/Bar
 * .super java/lang/Object
 *
 * .field public static X I = 42
 *
 * .method public <init>()V
 *     aload_0
 *     invokespecial java/lang/Object.<init>()V
 *     return
 * .end method
 *
 * .method public static greet()Ljava/lang/String;
 *     ldc "hi"
 *     areturn
 * .end method
 * }</pre>
 *
 * <p>Stack map frames and method MAXS are auto-computed by
 * {@link ClassWriter#COMPUTE_FRAMES}. We don't try to support every JVM instruction — just
 * the ones a hand-written hook typically needs.</p>
 */
public final class AsmAssembler {

    /** Parsing/emit error with 1-based line number for UI display. */
    public static final class AsmException extends RuntimeException {
        public final int line;
        public AsmException(int line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }
    }

    public byte[] assemble(String source) {
        String[] lines = source.split("\r?\n", -1);
        ClassState cls = parse(lines);
        return emit(cls);
    }

    // --- parsing ---------------------------------------------------------------

    private static final class ClassState {
        int access = Opcodes.ACC_PUBLIC;
        String name;
        String superName = "java/lang/Object";
        List<String> interfaces = new ArrayList<>();
        List<FieldState> fields = new ArrayList<>();
        List<MethodState> methods = new ArrayList<>();
    }
    private static final class FieldState {
        int access; String name, desc; Object value;
    }
    private static final class MethodState {
        int access; String name, desc;
        List<Insn> insns = new ArrayList<>();
    }
    private record Insn(int line, String mnemonic, String[] args) {}

    private ClassState parse(String[] lines) {
        ClassState cls = new ClassState();
        MethodState currentMethod = null;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = stripComment(raw).trim();
            if (line.isEmpty()) continue;
            int ln = i + 1;

            if (line.startsWith(".class")) {
                String[] toks = line.split("\\s+");
                int[] accHolder = {Opcodes.ACC_PUBLIC};
                int idx = parseAccess(toks, 1, accHolder);
                cls.access = accHolder[0];
                if (idx >= toks.length) throw new AsmException(ln, ".class requires a name");
                cls.name = toks[idx];
            } else if (line.startsWith(".super")) {
                cls.superName = line.substring(".super".length()).trim();
            } else if (line.startsWith(".implements")) {
                cls.interfaces.add(line.substring(".implements".length()).trim());
            } else if (line.startsWith(".field")) {
                cls.fields.add(parseField(line, ln));
            } else if (line.startsWith(".method")) {
                if (currentMethod != null) throw new AsmException(ln, "nested .method not allowed");
                currentMethod = parseMethodHeader(line, ln);
            } else if (line.equals(".end method")) {
                if (currentMethod == null) throw new AsmException(ln, ".end method without .method");
                cls.methods.add(currentMethod);
                currentMethod = null;
            } else {
                if (currentMethod == null) {
                    throw new AsmException(ln, "instruction outside .method: " + line);
                }
                String[] parts = line.split("\\s+", 2);
                String mnemonic = parts[0];
                String[] args = parts.length > 1 ? splitArgs(parts[1]) : new String[0];
                currentMethod.insns.add(new Insn(ln, mnemonic, args));
            }
        }
        if (cls.name == null) throw new AsmException(1, "missing .class directive");
        if (currentMethod != null) throw new AsmException(lines.length, "unterminated .method");
        return cls;
    }

    /**
     * Strip a trailing comment. We use {@code //} rather than the traditional Jasmin
     * {@code ;} because JVM type descriptors legitimately contain {@code ;}
     * ({@code Ljava/lang/String;}) and we don't want to mangle those.
     */
    private static String stripComment(String s) {
        int slash = s.indexOf("//");
        return slash < 0 ? s : s.substring(0, slash);
    }

    /** Split "java/lang/String \"hi\" 5" into 3 args, honouring quoted strings. */
    private static String[] splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    cur.append('"');
                    inQuote = false;
                } else if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
                cur.append('"');
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** Parse {@code public static final} prefix; returns index of first non-flag token. */
    private static int parseAccess(String[] toks, int start, int[] accHolder) {
        int acc = 0;
        int i = start;
        for (; i < toks.length; i++) {
            Integer f = ACCESS_FLAGS.get(toks[i]);
            if (f == null) break;
            acc |= f;
        }
        if (acc != 0) accHolder[0] = acc;
        return i;
    }

    private static final Map<String, Integer> ACCESS_FLAGS = new HashMap<>();
    static {
        ACCESS_FLAGS.put("public", Opcodes.ACC_PUBLIC);
        ACCESS_FLAGS.put("private", Opcodes.ACC_PRIVATE);
        ACCESS_FLAGS.put("protected", Opcodes.ACC_PROTECTED);
        ACCESS_FLAGS.put("static", Opcodes.ACC_STATIC);
        ACCESS_FLAGS.put("final", Opcodes.ACC_FINAL);
        ACCESS_FLAGS.put("abstract", Opcodes.ACC_ABSTRACT);
        ACCESS_FLAGS.put("synthetic", Opcodes.ACC_SYNTHETIC);
        ACCESS_FLAGS.put("synchronized", Opcodes.ACC_SYNCHRONIZED);
        ACCESS_FLAGS.put("native", Opcodes.ACC_NATIVE);
        ACCESS_FLAGS.put("volatile", Opcodes.ACC_VOLATILE);
        ACCESS_FLAGS.put("transient", Opcodes.ACC_TRANSIENT);
    }

    private FieldState parseField(String line, int ln) {
        String body = line.substring(".field".length()).trim();
        // "<flags>... name desc [= value]"
        int eq = body.indexOf('=');
        String valuePart = null;
        if (eq >= 0) {
            valuePart = body.substring(eq + 1).trim();
            body = body.substring(0, eq).trim();
        }
        String[] toks = body.split("\\s+");
        int[] acc = {0};
        int idx = parseAccess(toks, 0, acc);
        if (toks.length - idx < 2) throw new AsmException(ln, ".field requires name and descriptor");
        FieldState f = new FieldState();
        f.access = acc[0];
        f.name = toks[idx];
        f.desc = toks[idx + 1];
        if (valuePart != null) f.value = parseLiteral(valuePart, ln);
        return f;
    }

    private MethodState parseMethodHeader(String line, int ln) {
        String body = line.substring(".method".length()).trim();
        String[] toks = body.split("\\s+");
        int[] acc = {Opcodes.ACC_PUBLIC};
        int idx = parseAccess(toks, 0, acc);
        if (idx >= toks.length) throw new AsmException(ln, ".method missing signature");
        String sig = toks[idx]; // e.g. "greet()Ljava/lang/String;"
        int lp = sig.indexOf('(');
        if (lp < 0) throw new AsmException(ln, ".method signature missing '(': " + sig);
        MethodState m = new MethodState();
        m.access = acc[0];
        m.name = sig.substring(0, lp);
        m.desc = sig.substring(lp);
        return m;
    }

    private static Object parseLiteral(String s, int ln) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        try {
            if (s.endsWith("L")) return Long.parseLong(s.substring(0, s.length() - 1));
            if (s.endsWith("F")) return Float.parseFloat(s.substring(0, s.length() - 1));
            if (s.endsWith("D")) return Double.parseDouble(s.substring(0, s.length() - 1));
            if (s.contains(".")) return Double.parseDouble(s);
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            throw new AsmException(ln, "bad literal: " + s);
        }
    }

    // --- emit ------------------------------------------------------------------

    private byte[] emit(ClassState cls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, cls.access, cls.name, null, cls.superName,
                cls.interfaces.toArray(new String[0]));
        for (FieldState f : cls.fields) {
            cw.visitField(f.access, f.name, f.desc, null, f.value).visitEnd();
        }
        for (MethodState m : cls.methods) {
            MethodVisitor mv = cw.visitMethod(m.access, m.name, m.desc, null, null);
            mv.visitCode();
            Map<String, Label> labels = new HashMap<>();
            for (Insn in : m.insns) {
                if (in.mnemonic.endsWith(":")) {
                    Label l = labels.computeIfAbsent(in.mnemonic.substring(0, in.mnemonic.length() - 1),
                            k -> new Label());
                    mv.visitLabel(l);
                    continue;
                }
                emitInsn(mv, in, labels);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void emitInsn(MethodVisitor mv, Insn in, Map<String, Label> labels) {
        String op = in.mnemonic.toLowerCase();
        Integer simple = SIMPLE_OPCODES.get(op);
        if (simple != null) {
            mv.visitInsn(simple);
            return;
        }
        switch (op) {
            case "bipush" -> mv.visitIntInsn(Opcodes.BIPUSH, intArg(in, 0));
            case "sipush" -> mv.visitIntInsn(Opcodes.SIPUSH, intArg(in, 0));
            case "ldc" -> {
                String a = argOr(in, 0, "");
                if (a.startsWith("\"") && a.endsWith("\"")) {
                    mv.visitLdcInsn(a.substring(1, a.length() - 1));
                } else if (a.endsWith("L")) {
                    mv.visitLdcInsn(Long.parseLong(a.substring(0, a.length() - 1)));
                } else if (a.endsWith("F")) {
                    mv.visitLdcInsn(Float.parseFloat(a.substring(0, a.length() - 1)));
                } else if (a.endsWith("D") || a.contains(".")) {
                    mv.visitLdcInsn(Double.parseDouble(a.replace("D", "")));
                } else {
                    mv.visitLdcInsn(Integer.parseInt(a));
                }
            }
            case "iload", "lload", "fload", "dload", "aload",
                 "istore", "lstore", "fstore", "dstore", "astore" ->
                    mv.visitVarInsn(VAR_OPCODES.get(op), intArg(in, 0));
            case "iload_0", "iload_1", "iload_2", "iload_3" -> mv.visitVarInsn(Opcodes.ILOAD, op.charAt(6) - '0');
            case "aload_0", "aload_1", "aload_2", "aload_3" -> mv.visitVarInsn(Opcodes.ALOAD, op.charAt(6) - '0');
            case "istore_0", "istore_1", "istore_2", "istore_3" -> mv.visitVarInsn(Opcodes.ISTORE, op.charAt(7) - '0');
            case "astore_0", "astore_1", "astore_2", "astore_3" -> mv.visitVarInsn(Opcodes.ASTORE, op.charAt(7) - '0');
            case "new", "checkcast", "instanceof", "anewarray" -> mv.visitTypeInsn(TYPE_OPCODES.get(op), argOr(in, 0, ""));
            case "getstatic", "putstatic", "getfield", "putfield" -> {
                // "owner/Name.field:desc"
                String fullRef = argOr(in, 0, "");
                int colon = fullRef.indexOf(':');
                int dot = fullRef.lastIndexOf('.', colon < 0 ? fullRef.length() : colon);
                if (dot < 0 || colon < 0) throw new AsmException(in.line, "bad field ref: " + fullRef);
                String owner = fullRef.substring(0, dot);
                String name = fullRef.substring(dot + 1, colon);
                String desc = fullRef.substring(colon + 1);
                mv.visitFieldInsn(FIELD_OPCODES.get(op), owner, name, desc);
            }
            case "invokestatic", "invokevirtual", "invokespecial", "invokeinterface" -> {
                // "owner/Name.method(args)ret"
                String fullRef = argOr(in, 0, "");
                int lp = fullRef.indexOf('(');
                int dot = fullRef.lastIndexOf('.', lp < 0 ? fullRef.length() : lp);
                if (dot < 0 || lp < 0) throw new AsmException(in.line, "bad method ref: " + fullRef);
                String owner = fullRef.substring(0, dot);
                String name = fullRef.substring(dot + 1, lp);
                String desc = fullRef.substring(lp);
                boolean itf = op.equals("invokeinterface");
                mv.visitMethodInsn(INVOKE_OPCODES.get(op), owner, name, desc, itf);
            }
            case "goto" -> mv.visitJumpInsn(Opcodes.GOTO, labelFor(labels, argOr(in, 0, "")));
            case "ifeq", "ifne", "iflt", "ifle", "ifgt", "ifge",
                 "if_icmpeq", "if_icmpne", "if_icmplt", "if_icmple", "if_icmpgt", "if_icmpge",
                 "if_acmpeq", "if_acmpne", "ifnull", "ifnonnull" ->
                    mv.visitJumpInsn(JUMP_OPCODES.get(op), labelFor(labels, argOr(in, 0, "")));
            default -> throw new AsmException(in.line, "unknown mnemonic: " + op);
        }
    }

    private static Label labelFor(Map<String, Label> labels, String name) {
        // Accept "foo" or "foo:" interchangeably so users can write either form in jumps.
        String key = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
        return labels.computeIfAbsent(key, k -> new Label());
    }

    private static int intArg(Insn in, int idx) {
        try { return Integer.parseInt(in.args[idx]); }
        catch (Exception ex) { throw new AsmException(in.line, "expected int arg"); }
    }
    private static String argOr(Insn in, int idx, String fallback) {
        return idx < in.args.length ? in.args[idx] : fallback;
    }

    private static final Map<String, Integer> SIMPLE_OPCODES = new HashMap<>();
    static {
        SIMPLE_OPCODES.put("nop", Opcodes.NOP);
        SIMPLE_OPCODES.put("aconst_null", Opcodes.ACONST_NULL);
        SIMPLE_OPCODES.put("iconst_m1", Opcodes.ICONST_M1);
        for (int i = 0; i <= 5; i++) SIMPLE_OPCODES.put("iconst_" + i, Opcodes.ICONST_0 + i);
        SIMPLE_OPCODES.put("lconst_0", Opcodes.LCONST_0); SIMPLE_OPCODES.put("lconst_1", Opcodes.LCONST_1);
        SIMPLE_OPCODES.put("fconst_0", Opcodes.FCONST_0); SIMPLE_OPCODES.put("fconst_1", Opcodes.FCONST_1);
        SIMPLE_OPCODES.put("fconst_2", Opcodes.FCONST_2);
        SIMPLE_OPCODES.put("dconst_0", Opcodes.DCONST_0); SIMPLE_OPCODES.put("dconst_1", Opcodes.DCONST_1);
        SIMPLE_OPCODES.put("pop", Opcodes.POP); SIMPLE_OPCODES.put("pop2", Opcodes.POP2);
        SIMPLE_OPCODES.put("dup", Opcodes.DUP); SIMPLE_OPCODES.put("dup_x1", Opcodes.DUP_X1);
        SIMPLE_OPCODES.put("dup_x2", Opcodes.DUP_X2); SIMPLE_OPCODES.put("dup2", Opcodes.DUP2);
        SIMPLE_OPCODES.put("swap", Opcodes.SWAP);
        SIMPLE_OPCODES.put("iadd", Opcodes.IADD); SIMPLE_OPCODES.put("isub", Opcodes.ISUB);
        SIMPLE_OPCODES.put("imul", Opcodes.IMUL); SIMPLE_OPCODES.put("idiv", Opcodes.IDIV);
        SIMPLE_OPCODES.put("irem", Opcodes.IREM); SIMPLE_OPCODES.put("ineg", Opcodes.INEG);
        SIMPLE_OPCODES.put("ishl", Opcodes.ISHL); SIMPLE_OPCODES.put("ishr", Opcodes.ISHR);
        SIMPLE_OPCODES.put("iushr", Opcodes.IUSHR); SIMPLE_OPCODES.put("iand", Opcodes.IAND);
        SIMPLE_OPCODES.put("ior", Opcodes.IOR); SIMPLE_OPCODES.put("ixor", Opcodes.IXOR);
        SIMPLE_OPCODES.put("return", Opcodes.RETURN); SIMPLE_OPCODES.put("ireturn", Opcodes.IRETURN);
        SIMPLE_OPCODES.put("lreturn", Opcodes.LRETURN); SIMPLE_OPCODES.put("freturn", Opcodes.FRETURN);
        SIMPLE_OPCODES.put("dreturn", Opcodes.DRETURN); SIMPLE_OPCODES.put("areturn", Opcodes.ARETURN);
        SIMPLE_OPCODES.put("arraylength", Opcodes.ARRAYLENGTH);
        SIMPLE_OPCODES.put("athrow", Opcodes.ATHROW);
    }

    private static final Map<String, Integer> VAR_OPCODES = Map.ofEntries(
            Map.entry("iload", Opcodes.ILOAD), Map.entry("lload", Opcodes.LLOAD),
            Map.entry("fload", Opcodes.FLOAD), Map.entry("dload", Opcodes.DLOAD),
            Map.entry("aload", Opcodes.ALOAD),
            Map.entry("istore", Opcodes.ISTORE), Map.entry("lstore", Opcodes.LSTORE),
            Map.entry("fstore", Opcodes.FSTORE), Map.entry("dstore", Opcodes.DSTORE),
            Map.entry("astore", Opcodes.ASTORE));

    private static final Map<String, Integer> TYPE_OPCODES = Map.of(
            "new", Opcodes.NEW, "checkcast", Opcodes.CHECKCAST,
            "instanceof", Opcodes.INSTANCEOF, "anewarray", Opcodes.ANEWARRAY);

    private static final Map<String, Integer> FIELD_OPCODES = Map.of(
            "getstatic", Opcodes.GETSTATIC, "putstatic", Opcodes.PUTSTATIC,
            "getfield", Opcodes.GETFIELD, "putfield", Opcodes.PUTFIELD);

    private static final Map<String, Integer> INVOKE_OPCODES = Map.of(
            "invokestatic", Opcodes.INVOKESTATIC, "invokevirtual", Opcodes.INVOKEVIRTUAL,
            "invokespecial", Opcodes.INVOKESPECIAL, "invokeinterface", Opcodes.INVOKEINTERFACE);

    private static final Map<String, Integer> JUMP_OPCODES = Map.ofEntries(
            Map.entry("ifeq", Opcodes.IFEQ), Map.entry("ifne", Opcodes.IFNE),
            Map.entry("iflt", Opcodes.IFLT), Map.entry("ifle", Opcodes.IFLE),
            Map.entry("ifgt", Opcodes.IFGT), Map.entry("ifge", Opcodes.IFGE),
            Map.entry("if_icmpeq", Opcodes.IF_ICMPEQ), Map.entry("if_icmpne", Opcodes.IF_ICMPNE),
            Map.entry("if_icmplt", Opcodes.IF_ICMPLT), Map.entry("if_icmple", Opcodes.IF_ICMPLE),
            Map.entry("if_icmpgt", Opcodes.IF_ICMPGT), Map.entry("if_icmpge", Opcodes.IF_ICMPGE),
            Map.entry("if_acmpeq", Opcodes.IF_ACMPEQ), Map.entry("if_acmpne", Opcodes.IF_ACMPNE),
            Map.entry("ifnull", Opcodes.IFNULL), Map.entry("ifnonnull", Opcodes.IFNONNULL));
}
