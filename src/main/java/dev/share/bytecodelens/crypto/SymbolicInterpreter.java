package dev.share.bytecodelens.crypto;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight bytecode interpreter for String-decrypt methods.
 * Supports the common patterns emitted by ZKM / Allatori / Stringer:
 * a static method taking a String and returning a decrypted String using
 * XOR / add / sub with integer constants, loops over the char array, etc.
 * Refuses to execute anything it can't interpret safely.
 */
public final class SymbolicInterpreter {

    private static final Logger log = LoggerFactory.getLogger(SymbolicInterpreter.class);
    private static final int MAX_STEPS = 100_000;

    public static final class InterpretException extends RuntimeException {
        InterpretException(String msg) { super(msg); }
    }

    /** Invoke {@code owner#name(desc)} with {@code stringArg} and return the result as String, or null if unsupported. */
    public String invokeStringToString(byte[] classBytes, String owner, String methodName, String desc, String stringArg) {
        try {
            ClassNode node = readClass(classBytes);
            MethodNode target = findMethod(node, methodName, desc);
            if (target == null) return null;
            Object[] locals = new Object[Math.max(target.maxLocals, 4)];
            locals[0] = stringArg;
            Object result = execute(target, locals);
            return coerceToString(result);
        } catch (InterpretException ex) {
            log.debug("Interpreter bailed out on {}.{}{}: {}", owner, methodName, desc, ex.getMessage());
            return null;
        } catch (Throwable ex) {
            log.debug("Unexpected error in interpreter {}.{}{}: {}", owner, methodName, desc, ex.getMessage());
            return null;
        }
    }

    private static String coerceToString(Object result) {
        if (result instanceof String s) return s;
        if (result instanceof StringPending sp) {
            if (sp.value != null) return sp.value;
            if (sp.buffer != null) return sp.buffer.toString();
        }
        return null;
    }

    /** Invoke {@code owner#name(desc)} taking an int and a string; some decryptors use (I;Ljava/lang/String;)Ljava/lang/String;. */
    public String invokeIntStringToString(byte[] classBytes, String owner, String methodName, String desc,
                                          int intArg, String stringArg) {
        try {
            ClassNode node = readClass(classBytes);
            MethodNode target = findMethod(node, methodName, desc);
            if (target == null) return null;
            Object[] locals = new Object[Math.max(target.maxLocals, 4)];
            locals[0] = intArg;
            locals[1] = stringArg;
            Object result = execute(target, locals);
            return coerceToString(result);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node.methods == null) return null;
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    private Object execute(MethodNode method, Object[] locals) {
        Deque<Object> stack = new ArrayDeque<>();
        Map<LabelNode, Integer> labelIndex = buildLabelIndex(method);
        int pc = 0;
        int steps = 0;
        AbstractInsnNode[] insns = method.instructions.toArray();

        while (pc < insns.length) {
            if (++steps > MAX_STEPS) throw new InterpretException("step limit exceeded");
            AbstractInsnNode insn = insns[pc];
            int op = insn.getOpcode();
            if (op < 0) {
                pc++;
                continue;
            }
            switch (op) {
                case Opcodes.ACONST_NULL -> stack.push(null);
                case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> stack.push(op - Opcodes.ICONST_0);
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.push(((IntInsnNode) insn).operand);
                case Opcodes.LDC -> {
                    Object c = ((LdcInsnNode) insn).cst;
                    if (c instanceof Integer i) stack.push(i);
                    else if (c instanceof String s) stack.push(s);
                    else if (c instanceof Long l) stack.push(l);
                    else if (c instanceof Float f) stack.push(f);
                    else if (c instanceof Double d) stack.push(d);
                    else throw new InterpretException("unsupported LDC type: " + c);
                }
                case Opcodes.ILOAD, Opcodes.ALOAD -> stack.push(locals[((VarInsnNode) insn).var]);
                case Opcodes.ISTORE, Opcodes.ASTORE -> locals[((VarInsnNode) insn).var] = stack.pop();
                case Opcodes.DUP -> {
                    Object top = stack.peek();
                    stack.push(top);
                }
                case Opcodes.POP -> stack.pop();
                case Opcodes.SWAP -> {
                    Object v1 = stack.pop();
                    Object v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                }
                case Opcodes.IADD -> stack.push(asInt(stack.pop()) + asInt(stack.pop()));
                case Opcodes.ISUB -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    stack.push(a - b);
                }
                case Opcodes.IMUL -> stack.push(asInt(stack.pop()) * asInt(stack.pop()));
                case Opcodes.IDIV -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    if (b == 0) throw new InterpretException("division by zero");
                    stack.push(a / b);
                }
                case Opcodes.IREM -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    if (b == 0) throw new InterpretException("division by zero");
                    stack.push(a % b);
                }
                case Opcodes.INEG -> stack.push(-asInt(stack.pop()));
                case Opcodes.IAND -> stack.push(asInt(stack.pop()) & asInt(stack.pop()));
                case Opcodes.IOR -> stack.push(asInt(stack.pop()) | asInt(stack.pop()));
                case Opcodes.IXOR -> stack.push(asInt(stack.pop()) ^ asInt(stack.pop()));
                case Opcodes.ISHL -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    stack.push(a << b);
                }
                case Opcodes.ISHR -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    stack.push(a >> b);
                }
                case Opcodes.IUSHR -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    stack.push(a >>> b);
                }
                case Opcodes.I2C -> stack.push((int) (char) asInt(stack.pop()));
                case Opcodes.I2B -> stack.push((int) (byte) asInt(stack.pop()));
                case Opcodes.I2S -> stack.push((int) (short) asInt(stack.pop()));
                case Opcodes.IINC -> {
                    IincInsnNode ii = (IincInsnNode) insn;
                    locals[ii.var] = asInt(locals[ii.var]) + ii.incr;
                }
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                    int v = asInt(stack.pop());
                    if (cmpUnary(op, v)) {
                        pc = labelIndex.get(((JumpInsnNode) insn).label);
                        continue;
                    }
                }
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                     Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                    int b = asInt(stack.pop());
                    int a = asInt(stack.pop());
                    if (cmpBinary(op, a, b)) {
                        pc = labelIndex.get(((JumpInsnNode) insn).label);
                        continue;
                    }
                }
                case Opcodes.GOTO -> {
                    pc = labelIndex.get(((JumpInsnNode) insn).label);
                    continue;
                }
                case Opcodes.IRETURN -> { return asInt(stack.pop()); }
                case Opcodes.ARETURN -> { return stack.pop(); }
                case Opcodes.RETURN -> { return null; }
                case Opcodes.NEWARRAY -> {
                    int type = ((IntInsnNode) insn).operand;
                    int len = asInt(stack.pop());
                    if (len < 0 || len > 1_000_000) throw new InterpretException("bad array len " + len);
                    if (type == 5) stack.push(new char[len]);
                    else if (type == 8) stack.push(new byte[len]);
                    else if (type == 10) stack.push(new int[len]);
                    else throw new InterpretException("unsupported NEWARRAY " + type);
                }
                case Opcodes.ARRAYLENGTH -> {
                    Object arr = stack.pop();
                    if (arr instanceof char[] c) stack.push(c.length);
                    else if (arr instanceof byte[] b) stack.push(b.length);
                    else if (arr instanceof int[] i) stack.push(i.length);
                    else if (arr instanceof String s) stack.push(s.length());
                    else throw new InterpretException("arraylength on " + arr);
                }
                case Opcodes.CASTORE -> {
                    int v = asInt(stack.pop());
                    int i = asInt(stack.pop());
                    Object arr = stack.pop();
                    ((char[]) arr)[i] = (char) v;
                }
                case Opcodes.BASTORE -> {
                    int v = asInt(stack.pop());
                    int i = asInt(stack.pop());
                    Object arr = stack.pop();
                    ((byte[]) arr)[i] = (byte) v;
                }
                case Opcodes.IASTORE -> {
                    int v = asInt(stack.pop());
                    int i = asInt(stack.pop());
                    Object arr = stack.pop();
                    ((int[]) arr)[i] = v;
                }
                case Opcodes.CALOAD -> {
                    int i = asInt(stack.pop());
                    Object arr = stack.pop();
                    stack.push((int) ((char[]) arr)[i]);
                }
                case Opcodes.BALOAD -> {
                    int i = asInt(stack.pop());
                    Object arr = stack.pop();
                    if (arr instanceof byte[] b) stack.push((int) b[i]);
                    else throw new InterpretException("BALOAD on " + arr);
                }
                case Opcodes.IALOAD -> {
                    int i = asInt(stack.pop());
                    Object arr = stack.pop();
                    stack.push(((int[]) arr)[i]);
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    Object result = callMethod(m, stack);
                    // Push if method returns something (descriptor doesn't end with V)
                    if (!m.desc.endsWith(")V")) {
                        stack.push(result);
                    }
                }
                case Opcodes.NEW -> {
                    TypeInsnNode tn = (TypeInsnNode) insn;
                    // Only support java/lang/String via <init>(char[]) pattern. Push a placeholder.
                    if ("java/lang/String".equals(tn.desc) || "java/lang/StringBuilder".equals(tn.desc)) {
                        stack.push(new StringPending(tn.desc));
                    } else {
                        throw new InterpretException("unsupported NEW " + tn.desc);
                    }
                }
                case Opcodes.CHECKCAST -> {
                    // Leave value unchanged; we're not enforcing cast checks
                }
                case Opcodes.NOP -> {
                }
                default -> throw new InterpretException("unsupported opcode " + op);
            }
            pc++;
        }
        throw new InterpretException("fell off end of method");
    }

    private Object callMethod(MethodInsnNode m, Deque<Object> stack) {
        // Gather args by descriptor
        int argCount = countArgs(m.desc);
        Object[] args = new Object[argCount];
        for (int i = argCount - 1; i >= 0; i--) args[i] = stack.pop();

        Object receiver = null;
        if (m.getOpcode() != Opcodes.INVOKESTATIC) {
            receiver = stack.pop();
        }

        String owner = m.owner;
        String name = m.name;

        if ("java/lang/String".equals(owner)) {
            return callString(name, m.desc, receiver, args);
        }
        if ("java/lang/StringBuilder".equals(owner)) {
            return callStringBuilder(name, m.desc, receiver, args);
        }
        if ("java/lang/Integer".equals(owner)) {
            return callInteger(name, m.desc, args);
        }
        if ("java/lang/Character".equals(owner)) {
            return callCharacter(name, m.desc, args);
        }
        throw new InterpretException("unsupported call " + owner + "." + name + m.desc);
    }

    private Object callString(String name, String desc, Object receiver, Object[] args) {
        String self = receiver instanceof String s ? s : null;
        switch (name) {
            case "<init>" -> {
                // receiver is StringPending; we "replace" it on stack when used
                if (receiver instanceof StringPending sp) {
                    if (args.length == 1 && args[0] instanceof char[] c) {
                        sp.value = new String(c);
                        return null;
                    }
                    if (args.length == 1 && args[0] instanceof byte[] b) {
                        sp.value = new String(b);
                        return null;
                    }
                    if (args.length == 1 && args[0] instanceof String s) {
                        sp.value = s;
                        return null;
                    }
                    if (args.length == 3 && args[0] instanceof char[] c) {
                        int off = asInt(args[1]);
                        int len = asInt(args[2]);
                        sp.value = new String(c, off, len);
                        return null;
                    }
                }
                throw new InterpretException("unsupported String.<init> " + desc);
            }
            case "length" -> { return self == null ? 0 : self.length(); }
            case "charAt" -> { return self == null ? 0 : (int) self.charAt(asInt(args[0])); }
            case "toCharArray" -> { return self == null ? new char[0] : self.toCharArray(); }
            case "intern" -> { return self; }
            case "substring" -> {
                if (args.length == 1) return self == null ? "" : self.substring(asInt(args[0]));
                return self == null ? "" : self.substring(asInt(args[0]), asInt(args[1]));
            }
            case "hashCode" -> { return self == null ? 0 : self.hashCode(); }
            default -> throw new InterpretException("unsupported String." + name);
        }
    }

    private Object callStringBuilder(String name, String desc, Object receiver, Object[] args) {
        if (!(receiver instanceof StringPending)) {
            throw new InterpretException("StringBuilder receiver not a pending instance");
        }
        StringPending sp = (StringPending) receiver;
        switch (name) {
            case "<init>" -> {
                if (sp.buffer == null) sp.buffer = new StringBuilder();
                if (args.length == 1 && args[0] instanceof String s) sp.buffer.append(s);
                return null;
            }
            case "append" -> {
                if (sp.buffer == null) sp.buffer = new StringBuilder();
                Object a = args[0];
                if (a instanceof StringPending sp2) {
                    sp.buffer.append(sp2.buffer != null ? sp2.buffer.toString() : (sp2.value == null ? "null" : sp2.value));
                } else if (a instanceof char[] c) {
                    sp.buffer.append(c);
                } else if (a instanceof Integer i) {
                    sp.buffer.append((int) i);
                } else {
                    sp.buffer.append(String.valueOf(a));
                }
                return sp;
            }
            case "toString" -> { return sp.buffer == null ? "" : sp.buffer.toString(); }
            default -> throw new InterpretException("unsupported StringBuilder." + name);
        }
    }

    private Object callInteger(String name, String desc, Object[] args) {
        switch (name) {
            case "parseInt" -> {
                String s = (String) args[0];
                if (args.length == 1) return Integer.parseInt(s);
                return Integer.parseInt(s, asInt(args[1]));
            }
            case "toString" -> { return Integer.toString(asInt(args[0])); }
            default -> throw new InterpretException("unsupported Integer." + name);
        }
    }

    private Object callCharacter(String name, String desc, Object[] args) {
        switch (name) {
            case "toLowerCase" -> { return (int) Character.toLowerCase((char) asInt(args[0])); }
            case "toUpperCase" -> { return (int) Character.toUpperCase((char) asInt(args[0])); }
            default -> throw new InterpretException("unsupported Character." + name);
        }
    }

    private Object throwUnsupp(String msg) { throw new InterpretException(msg); }

    private static Map<LabelNode, Integer> buildLabelIndex(MethodNode method) {
        Map<LabelNode, Integer> idx = new HashMap<>();
        AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] instanceof LabelNode ln) idx.put(ln, i);
        }
        return idx;
    }

    private static boolean cmpUnary(int op, int v) {
        return switch (op) {
            case Opcodes.IFEQ -> v == 0;
            case Opcodes.IFNE -> v != 0;
            case Opcodes.IFLT -> v < 0;
            case Opcodes.IFGE -> v >= 0;
            case Opcodes.IFGT -> v > 0;
            case Opcodes.IFLE -> v <= 0;
            default -> false;
        };
    }

    private static boolean cmpBinary(int op, int a, int b) {
        return switch (op) {
            case Opcodes.IF_ICMPEQ -> a == b;
            case Opcodes.IF_ICMPNE -> a != b;
            case Opcodes.IF_ICMPLT -> a < b;
            case Opcodes.IF_ICMPGE -> a >= b;
            case Opcodes.IF_ICMPGT -> a > b;
            case Opcodes.IF_ICMPLE -> a <= b;
            default -> false;
        };
    }

    private static int asInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Character c) return c;
        if (v instanceof Byte b) return b;
        if (v instanceof Short s) return s;
        if (v instanceof Boolean b) return b ? 1 : 0;
        throw new InterpretException("expected int-like, got " + (v == null ? "null" : v.getClass()));
    }

    private static int countArgs(String desc) {
        int count = 0;
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') {
                count++;
                while (desc.charAt(i) != ';') i++;
                i++;
            } else if (c == '[') {
                while (desc.charAt(i) == '[') i++;
                if (desc.charAt(i) == 'L') {
                    while (desc.charAt(i) != ';') i++;
                }
                i++;
                count++;
            } else {
                count++;
                i++;
            }
        }
        return count;
    }

    /** Wrapper used while constructing a String/StringBuilder on the symbolic stack. */
    static final class StringPending {
        final String kind;
        String value;
        StringBuilder buffer;
        StringPending(String kind) { this.kind = kind; }

        @Override
        public String toString() {
            if (value != null) return value;
            if (buffer != null) return buffer.toString();
            return "";
        }
    }
}
