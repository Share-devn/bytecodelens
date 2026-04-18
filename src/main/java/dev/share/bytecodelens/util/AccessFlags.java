package dev.share.bytecodelens.util;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public final class AccessFlags {

    private AccessFlags() {
    }

    public static List<String> forClass(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_PUBLIC) != 0) flags.add("public");
        if ((access & Opcodes.ACC_PRIVATE) != 0) flags.add("private");
        if ((access & Opcodes.ACC_PROTECTED) != 0) flags.add("protected");
        if ((access & Opcodes.ACC_FINAL) != 0) flags.add("final");
        if ((access & Opcodes.ACC_SUPER) != 0) flags.add("super");
        if ((access & Opcodes.ACC_INTERFACE) != 0) flags.add("interface");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) flags.add("abstract");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & Opcodes.ACC_ANNOTATION) != 0) flags.add("annotation");
        if ((access & Opcodes.ACC_ENUM) != 0) flags.add("enum");
        if ((access & Opcodes.ACC_RECORD) != 0) flags.add("record");
        if ((access & Opcodes.ACC_MODULE) != 0) flags.add("module");
        return flags;
    }

    public static List<String> forMethod(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_PUBLIC) != 0) flags.add("public");
        if ((access & Opcodes.ACC_PRIVATE) != 0) flags.add("private");
        if ((access & Opcodes.ACC_PROTECTED) != 0) flags.add("protected");
        if ((access & Opcodes.ACC_STATIC) != 0) flags.add("static");
        if ((access & Opcodes.ACC_FINAL) != 0) flags.add("final");
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) flags.add("synchronized");
        if ((access & Opcodes.ACC_BRIDGE) != 0) flags.add("bridge");
        if ((access & Opcodes.ACC_VARARGS) != 0) flags.add("varargs");
        if ((access & Opcodes.ACC_NATIVE) != 0) flags.add("native");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) flags.add("abstract");
        if ((access & Opcodes.ACC_STRICT) != 0) flags.add("strict");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        return flags;
    }

    public static List<String> forField(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_PUBLIC) != 0) flags.add("public");
        if ((access & Opcodes.ACC_PRIVATE) != 0) flags.add("private");
        if ((access & Opcodes.ACC_PROTECTED) != 0) flags.add("protected");
        if ((access & Opcodes.ACC_STATIC) != 0) flags.add("static");
        if ((access & Opcodes.ACC_FINAL) != 0) flags.add("final");
        if ((access & Opcodes.ACC_VOLATILE) != 0) flags.add("volatile");
        if ((access & Opcodes.ACC_TRANSIENT) != 0) flags.add("transient");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & Opcodes.ACC_ENUM) != 0) flags.add("enum");
        return flags;
    }

    /** Flags that appear on a {@code requires} directive. */
    public static List<String> forModuleRequires(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_TRANSITIVE) != 0) flags.add("transitive");
        if ((access & Opcodes.ACC_STATIC_PHASE) != 0) flags.add("static");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & Opcodes.ACC_MANDATED) != 0) flags.add("mandated");
        return flags;
    }

    /** Flags that appear on an {@code exports} / {@code opens} directive. */
    public static List<String> forModuleExports(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & Opcodes.ACC_MANDATED) != 0) flags.add("mandated");
        return flags;
    }

    /** Flags on the module itself (module-info access). */
    public static List<String> forModule(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_OPEN) != 0) flags.add("open");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & Opcodes.ACC_MANDATED) != 0) flags.add("mandated");
        return flags;
    }

    public static String javaVersion(int major) {
        return switch (major) {
            case 45 -> "Java 1.1";
            case 46 -> "Java 1.2";
            case 47 -> "Java 1.3";
            case 48 -> "Java 1.4";
            case 49 -> "Java 5";
            case 50 -> "Java 6";
            case 51 -> "Java 7";
            case 52 -> "Java 8";
            case 53 -> "Java 9";
            case 54 -> "Java 10";
            case 55 -> "Java 11";
            case 56 -> "Java 12";
            case 57 -> "Java 13";
            case 58 -> "Java 14";
            case 59 -> "Java 15";
            case 60 -> "Java 16";
            case 61 -> "Java 17";
            case 62 -> "Java 18";
            case 63 -> "Java 19";
            case 64 -> "Java 20";
            case 65 -> "Java 21";
            case 66 -> "Java 22";
            case 67 -> "Java 23";
            case 68 -> "Java 24";
            case 69 -> "Java 25";
            default -> "Unknown (" + major + ")";
        };
    }
}
