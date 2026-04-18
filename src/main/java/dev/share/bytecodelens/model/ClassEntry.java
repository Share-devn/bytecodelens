package dev.share.bytecodelens.model;

import java.util.List;

public record ClassEntry(
        String internalName,
        String name,
        String packageName,
        String simpleName,
        String superName,
        List<String> interfaces,
        int access,
        int majorVersion,
        int minorVersion,
        int methodCount,
        int fieldCount,
        int constantPoolSize,
        String sourceFile,
        byte[] bytes,
        ModuleInfo moduleInfo,
        int runtimeVersion
) {
    public String fqn() {
        return name;
    }

    public int size() {
        return bytes.length;
    }

    public boolean isInterface() {
        return (access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isEnum() {
        return (access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0;
    }

    public boolean isAnnotation() {
        return (access & org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0;
    }

    public boolean isRecord() {
        return (access & org.objectweb.asm.Opcodes.ACC_RECORD) != 0;
    }

    public boolean isModule() {
        return moduleInfo != null;
    }

    public boolean isVersioned() {
        return runtimeVersion > 0;
    }
}
