package dev.share.bytecodelens.usage;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UsageIndex {

    private static final Logger log = LoggerFactory.getLogger(UsageIndex.class);

    private final LoadedJar jar;
    // key = owner + "#" + name + desc (for methods); owner + "." + name + ":" + desc (for fields)
    private final Map<String, List<CallSite>> methodCalls = new ConcurrentHashMap<>();
    private final Map<String, List<CallSite>> fieldAccesses = new ConcurrentHashMap<>();
    private final Map<String, List<CallSite>> classUses = new ConcurrentHashMap<>();

    public UsageIndex(LoadedJar jar) {
        this.jar = jar;
    }

    public LoadedJar jar() {
        return jar;
    }

    public void build() {
        long start = System.currentTimeMillis();
        jar.classes().parallelStream().forEach(this::scan);
        log.info("Usage index built in {}ms: {} method calls, {} field accesses, {} class uses",
                System.currentTimeMillis() - start,
                methodCalls.values().stream().mapToInt(List::size).sum(),
                fieldAccesses.values().stream().mapToInt(List::size).sum(),
                classUses.values().stream().mapToInt(List::size).sum());
    }

    public java.util.stream.Stream<CallSite> allMethodCalls() {
        return methodCalls.values().stream().flatMap(List::stream);
    }

    public java.util.stream.Stream<CallSite> allFieldAccesses() {
        return fieldAccesses.values().stream().flatMap(List::stream);
    }

    public java.util.stream.Stream<CallSite> allClassUses() {
        return classUses.values().stream().flatMap(List::stream);
    }

    public List<CallSite> findUsages(UsageTarget target) {
        if (target instanceof UsageTarget.Method m) {
            return methodCalls.getOrDefault(methodKey(m.ownerInternal(), m.name(), m.desc()), List.of());
        }
        if (target instanceof UsageTarget.Field f) {
            return fieldAccesses.getOrDefault(fieldKey(f.ownerInternal(), f.name(), f.desc()), List.of());
        }
        if (target instanceof UsageTarget.Class c) {
            // Class usages are aggregated on demand from three sources:
            //  - type-level uses (super/interfaces/NEW/CHECKCAST/INSTANCEOF/ANEWARRAY) stored in classUses
            //  - method invocations whose owner is this class
            //  - field accesses whose owner is this class
            // Storing the method/field duplicates in classUses upfront would double memory on big jars.
            String owner = c.internalName();
            List<CallSite> result = new ArrayList<>(classUses.getOrDefault(owner, List.of()));
            methodCalls.forEach((k, list) -> {
                if (k.startsWith(owner + "#")) result.addAll(list);
            });
            fieldAccesses.forEach((k, list) -> {
                if (k.startsWith(owner + ".")) result.addAll(list);
            });
            return result;
        }
        return List.of();
    }

    private void scan(ClassEntry entry) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES);
        } catch (Exception ex) {
            log.debug("Skip {}: {}", entry.name(), ex.getMessage());
            return;
        }

        String inClass = entry.internalName();

        // Track class uses from super/interfaces
        if (node.superName != null) {
            recordClassUse(node.superName, inClass, "<class>", "", CallSite.Kind.TYPE_IN_SIGNATURE, 0);
        }
        if (node.interfaces != null) {
            for (String iface : node.interfaces) {
                recordClassUse(iface, inClass, "<class>", "", CallSite.Kind.TYPE_IN_SIGNATURE, 0);
            }
        }

        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            int currentLine = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LineNumberNode ln) {
                    currentLine = ln.line;
                    continue;
                }
                scanInsn(inClass, m, insn, currentLine);
            }
        }
    }

    private void scanInsn(String inClass, MethodNode m, AbstractInsnNode insn, int line) {
        if (insn instanceof MethodInsnNode mi) {
            CallSite.Kind kind = switch (mi.getOpcode()) {
                case Opcodes.INVOKEVIRTUAL -> CallSite.Kind.INVOKE_VIRTUAL;
                case Opcodes.INVOKESTATIC -> CallSite.Kind.INVOKE_STATIC;
                case Opcodes.INVOKESPECIAL -> CallSite.Kind.INVOKE_SPECIAL;
                case Opcodes.INVOKEINTERFACE -> CallSite.Kind.INVOKE_INTERFACE;
                default -> CallSite.Kind.INVOKE_VIRTUAL;
            };
            CallSite cs = new CallSite(inClass, m.name, m.desc, kind,
                    mi.owner, mi.name, mi.desc, line);
            methodCalls.computeIfAbsent(methodKey(mi.owner, mi.name, mi.desc),
                    k -> Collections.synchronizedList(new ArrayList<>())).add(cs);
            // NOTE: classUses for method invocations is reconstructed on demand in findUsages(Class)
            // to keep memory bounded on very large jars (see UsageIndex#findUsages).
        } else if (insn instanceof FieldInsnNode fi) {
            CallSite.Kind kind = switch (fi.getOpcode()) {
                case Opcodes.GETFIELD -> CallSite.Kind.GETFIELD;
                case Opcodes.PUTFIELD -> CallSite.Kind.PUTFIELD;
                case Opcodes.GETSTATIC -> CallSite.Kind.GETSTATIC;
                case Opcodes.PUTSTATIC -> CallSite.Kind.PUTSTATIC;
                default -> CallSite.Kind.GETFIELD;
            };
            CallSite cs = new CallSite(inClass, m.name, m.desc, kind,
                    fi.owner, fi.name, fi.desc, line);
            fieldAccesses.computeIfAbsent(fieldKey(fi.owner, fi.name, fi.desc),
                    k -> Collections.synchronizedList(new ArrayList<>())).add(cs);
            // NOTE: see above — classUses aggregated on demand.
        } else if (insn instanceof TypeInsnNode tn) {
            CallSite.Kind kind = switch (tn.getOpcode()) {
                case Opcodes.NEW -> CallSite.Kind.NEW;
                case Opcodes.CHECKCAST -> CallSite.Kind.CHECKCAST;
                case Opcodes.INSTANCEOF -> CallSite.Kind.INSTANCEOF;
                case Opcodes.ANEWARRAY -> CallSite.Kind.ANEWARRAY;
                default -> CallSite.Kind.NEW;
            };
            CallSite cs = new CallSite(inClass, m.name, m.desc, kind,
                    tn.desc, "", "", line);
            classUses.computeIfAbsent(tn.desc,
                    k -> Collections.synchronizedList(new ArrayList<>())).add(cs);
        }
    }

    private void recordClassUse(String owner, String inClass, String inMethod, String inDesc,
                                CallSite.Kind kind, int line) {
        CallSite cs = new CallSite(inClass, inMethod, inDesc, kind, owner, "", "", line);
        classUses.computeIfAbsent(owner,
                k -> Collections.synchronizedList(new ArrayList<>())).add(cs);
    }

    public static String methodKey(String owner, String name, String desc) {
        return owner + "#" + name + desc;
    }

    public static String fieldKey(String owner, String name, String desc) {
        return owner + "." + name + ":" + desc;
    }
}
