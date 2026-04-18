package dev.share.bytecodelens.diff;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ResourceReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JarDiffer {

    private static final Logger log = LoggerFactory.getLogger(JarDiffer.class);

    private final ResourceReader resourceReader = new ResourceReader();

    public JarDiffResult diff(LoadedJar a, LoadedJar b) {
        long start = System.currentTimeMillis();

        List<ClassDiff> classes = diffClasses(a, b);
        List<ResourceDiff> resources = diffResources(a, b);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Jar diff: {} class entries, {} resource entries in {}ms",
                classes.size(), resources.size(), elapsed);
        return new JarDiffResult(a.source(), b.source(), classes, resources, elapsed);
    }

    private List<ClassDiff> diffClasses(LoadedJar a, LoadedJar b) {
        Map<String, ClassEntry> byNameA = new HashMap<>();
        Map<String, ClassEntry> byNameB = new HashMap<>();
        for (ClassEntry c : a.classes()) byNameA.put(c.name(), c);
        for (ClassEntry c : b.classes()) byNameB.put(c.name(), c);

        Set<String> all = new java.util.TreeSet<>();
        all.addAll(byNameA.keySet());
        all.addAll(byNameB.keySet());

        List<ClassDiff> out = new ArrayList<>();
        for (String fqn : all) {
            ClassEntry ca = byNameA.get(fqn);
            ClassEntry cb = byNameB.get(fqn);

            if (ca == null) {
                out.add(new ClassDiff(ChangeType.ADDED, fqn,
                        cb.packageName(), cb.simpleName(),
                        null, cb.bytes(), List.of(), List.of(), List.of()));
            } else if (cb == null) {
                out.add(new ClassDiff(ChangeType.REMOVED, fqn,
                        ca.packageName(), ca.simpleName(),
                        ca.bytes(), null, List.of(), List.of(), List.of()));
            } else if (Arrays.equals(ca.bytes(), cb.bytes())) {
                out.add(new ClassDiff(ChangeType.UNCHANGED, fqn,
                        ca.packageName(), ca.simpleName(),
                        ca.bytes(), cb.bytes(), List.of(), List.of(), List.of()));
            } else {
                out.add(diffClassBodies(ca, cb));
            }
        }
        return out;
    }

    private ClassDiff diffClassBodies(ClassEntry ca, ClassEntry cb) {
        ClassNode na = readNode(ca);
        ClassNode nb = readNode(cb);

        List<String> headers = new ArrayList<>();
        if (na.access != nb.access) {
            headers.add("access: " + na.access + " -> " + nb.access);
        }
        if (!safeEq(na.superName, nb.superName)) {
            headers.add("super: " + na.superName + " -> " + nb.superName);
        }
        if (!safeEq(na.interfaces, nb.interfaces)) {
            headers.add("interfaces: " + na.interfaces + " -> " + nb.interfaces);
        }
        if (na.version != nb.version) {
            headers.add("version: " + na.version + " -> " + nb.version);
        }

        List<MemberDiff> methods = diffMethods(na, nb);
        List<MemberDiff> fields = diffFields(na, nb);

        return new ClassDiff(ChangeType.MODIFIED, ca.name(),
                ca.packageName(), ca.simpleName(),
                ca.bytes(), cb.bytes(),
                methods, fields, headers);
    }

    private List<MemberDiff> diffMethods(ClassNode a, ClassNode b) {
        Map<String, MethodNode> ma = indexMethods(a);
        Map<String, MethodNode> mb = indexMethods(b);
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(ma.keySet());
        keys.addAll(mb.keySet());

        List<MemberDiff> out = new ArrayList<>();
        for (String k : keys) {
            MethodNode x = ma.get(k);
            MethodNode y = mb.get(k);
            String name = k.substring(0, k.indexOf('|'));
            String desc = k.substring(k.indexOf('|') + 1);
            if (x == null) {
                out.add(new MemberDiff(MemberDiff.Kind.METHOD, ChangeType.ADDED,
                        name, desc, 0, y.access, ""));
            } else if (y == null) {
                out.add(new MemberDiff(MemberDiff.Kind.METHOD, ChangeType.REMOVED,
                        name, desc, x.access, 0, ""));
            } else {
                String hashA = methodHash(x);
                String hashB = methodHash(y);
                if (!hashA.equals(hashB) || x.access != y.access) {
                    String detail = x.access != y.access
                            ? ("access " + x.access + " -> " + y.access)
                            : "bytecode changed";
                    out.add(new MemberDiff(MemberDiff.Kind.METHOD, ChangeType.MODIFIED,
                            name, desc, x.access, y.access, detail));
                } else {
                    out.add(new MemberDiff(MemberDiff.Kind.METHOD, ChangeType.UNCHANGED,
                            name, desc, x.access, y.access, ""));
                }
            }
        }
        return out;
    }

    private List<MemberDiff> diffFields(ClassNode a, ClassNode b) {
        Map<String, FieldNode> fa = indexFields(a);
        Map<String, FieldNode> fb = indexFields(b);
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(fa.keySet());
        keys.addAll(fb.keySet());

        List<MemberDiff> out = new ArrayList<>();
        for (String k : keys) {
            FieldNode x = fa.get(k);
            FieldNode y = fb.get(k);
            String name = k.substring(0, k.indexOf('|'));
            String desc = k.substring(k.indexOf('|') + 1);
            if (x == null) {
                out.add(new MemberDiff(MemberDiff.Kind.FIELD, ChangeType.ADDED,
                        name, desc, 0, y.access, ""));
            } else if (y == null) {
                out.add(new MemberDiff(MemberDiff.Kind.FIELD, ChangeType.REMOVED,
                        name, desc, x.access, 0, ""));
            } else {
                boolean changed = x.access != y.access || !safeEq(x.value, y.value);
                if (changed) {
                    String detail = x.access != y.access
                            ? ("access " + x.access + " -> " + y.access)
                            : ("value " + x.value + " -> " + y.value);
                    out.add(new MemberDiff(MemberDiff.Kind.FIELD, ChangeType.MODIFIED,
                            name, desc, x.access, y.access, detail));
                } else {
                    out.add(new MemberDiff(MemberDiff.Kind.FIELD, ChangeType.UNCHANGED,
                            name, desc, x.access, y.access, ""));
                }
            }
        }
        return out;
    }

    private List<ResourceDiff> diffResources(LoadedJar a, LoadedJar b) {
        Map<String, JarResource> byPathA = new HashMap<>();
        Map<String, JarResource> byPathB = new HashMap<>();
        for (JarResource r : a.resources()) byPathA.put(r.path(), r);
        for (JarResource r : b.resources()) byPathB.put(r.path(), r);

        Set<String> all = new java.util.TreeSet<>();
        all.addAll(byPathA.keySet());
        all.addAll(byPathB.keySet());

        List<ResourceDiff> out = new ArrayList<>();
        for (String path : all) {
            JarResource ra = byPathA.get(path);
            JarResource rb = byPathB.get(path);
            if (ra == null) {
                out.add(new ResourceDiff(ChangeType.ADDED, path, 0, rb.size()));
            } else if (rb == null) {
                out.add(new ResourceDiff(ChangeType.REMOVED, path, ra.size(), 0));
            } else {
                // Compare content hashes
                ChangeType change = ChangeType.UNCHANGED;
                try {
                    byte[] bytesA = resourceReader.read(a.source(), path);
                    byte[] bytesB = resourceReader.read(b.source(), path);
                    if (!Arrays.equals(bytesA, bytesB)) change = ChangeType.MODIFIED;
                } catch (Exception ex) {
                    if (ra.size() != rb.size()) change = ChangeType.MODIFIED;
                }
                out.add(new ResourceDiff(change, path, ra.size(), rb.size()));
            }
        }
        return out;
    }

    private static Map<String, MethodNode> indexMethods(ClassNode n) {
        Map<String, MethodNode> out = new HashMap<>();
        if (n.methods == null) return out;
        for (MethodNode m : n.methods) out.put(m.name + "|" + m.desc, m);
        return out;
    }

    private static Map<String, FieldNode> indexFields(ClassNode n) {
        Map<String, FieldNode> out = new HashMap<>();
        if (n.fields == null) return out;
        for (FieldNode f : n.fields) out.put(f.name + "|" + f.desc, f);
        return out;
    }

    private static ClassNode readNode(ClassEntry entry) {
        ClassNode node = new ClassNode();
        new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    private static String methodHash(MethodNode m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.desc).append('|').append(m.access).append('|');
        if (m.instructions != null) {
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                sb.append(insn.getOpcode()).append(';');
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            return sb.toString();
        }
    }

    private static boolean safeEq(Object x, Object y) {
        if (x == null && y == null) return true;
        if (x == null || y == null) return false;
        return x.equals(y);
    }
}
