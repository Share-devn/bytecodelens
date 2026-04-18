package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.FieldEntry;
import dev.share.bytecodelens.model.MethodEntry;
import dev.share.bytecodelens.model.ModuleInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ModuleExportNode;
import org.objectweb.asm.tree.ModuleNode;
import org.objectweb.asm.tree.ModuleOpenNode;
import org.objectweb.asm.tree.ModuleProvideNode;
import org.objectweb.asm.tree.ModuleRequireNode;

import java.util.ArrayList;
import java.util.List;

public final class ClassAnalyzer {

    public ClassEntry analyze(byte[] bytes) {
        return analyze(bytes, 0);
    }

    public ClassEntry analyze(byte[] bytes, int runtimeVersion) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);

        String internalName = node.name;
        String dottedName = internalName.replace('/', '.');
        int lastSlash = internalName.lastIndexOf('/');
        String pkg = lastSlash < 0 ? "" : internalName.substring(0, lastSlash).replace('/', '.');
        String simple = lastSlash < 0 ? internalName : internalName.substring(lastSlash + 1);

        int major = reader.readShort(6);
        int minor = reader.readShort(4);
        int cpSize = reader.getItemCount();

        List<String> interfaces = node.interfaces == null ? List.of() : List.copyOf(node.interfaces);

        ModuleInfo moduleInfo = node.module == null ? null : buildModuleInfo(node.module);

        return new ClassEntry(
                internalName,
                dottedName,
                pkg,
                simple,
                node.superName,
                interfaces,
                node.access,
                major,
                minor,
                node.methods == null ? 0 : node.methods.size(),
                node.fields == null ? 0 : node.fields.size(),
                cpSize,
                node.sourceFile,
                bytes,
                moduleInfo,
                runtimeVersion
        );
    }

    private static ModuleInfo buildModuleInfo(ModuleNode module) {
        List<ModuleInfo.Requires> requires = new ArrayList<>();
        if (module.requires != null) {
            for (ModuleRequireNode r : module.requires) {
                requires.add(new ModuleInfo.Requires(r.module, r.access, r.version));
            }
        }
        List<ModuleInfo.Exports> exports = new ArrayList<>();
        if (module.exports != null) {
            for (ModuleExportNode e : module.exports) {
                exports.add(new ModuleInfo.Exports(
                        e.packaze,
                        e.access,
                        e.modules == null ? List.of() : List.copyOf(e.modules)));
            }
        }
        List<ModuleInfo.Opens> opens = new ArrayList<>();
        if (module.opens != null) {
            for (ModuleOpenNode o : module.opens) {
                opens.add(new ModuleInfo.Opens(
                        o.packaze,
                        o.access,
                        o.modules == null ? List.of() : List.copyOf(o.modules)));
            }
        }
        List<String> uses = module.uses == null ? List.of() : List.copyOf(module.uses);
        List<ModuleInfo.Provides> provides = new ArrayList<>();
        if (module.provides != null) {
            for (ModuleProvideNode p : module.provides) {
                provides.add(new ModuleInfo.Provides(
                        p.service,
                        p.providers == null ? List.of() : List.copyOf(p.providers)));
            }
        }
        return new ModuleInfo(
                module.name,
                module.version,
                module.access,
                List.copyOf(requires),
                List.copyOf(exports),
                List.copyOf(opens),
                uses,
                List.copyOf(provides));
    }

    public List<MethodEntry> methods(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        List<MethodEntry> result = new ArrayList<>();
        if (node.methods == null) return result;
        for (MethodNode m : node.methods) {
            int instructions = m.instructions == null ? 0 : m.instructions.size();
            result.add(new MethodEntry(m.name, m.desc, m.access, instructions, m.maxStack, m.maxLocals));
        }
        return result;
    }

    public List<FieldEntry> fields(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        List<FieldEntry> result = new ArrayList<>();
        if (node.fields == null) return result;
        for (FieldNode f : node.fields) {
            result.add(new FieldEntry(f.name, f.desc, f.access, f.value));
        }
        return result;
    }
}
