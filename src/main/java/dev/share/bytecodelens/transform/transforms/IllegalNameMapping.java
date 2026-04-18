package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.mapping.MappingApplier;
import dev.share.bytecodelens.mapping.MappingFormat;
import dev.share.bytecodelens.mapping.MappingModel;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.FieldEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.model.MethodEntry;
import dev.share.bytecodelens.service.ClassAnalyzer;
import dev.share.bytecodelens.transform.JarLevelTransformation;
import dev.share.bytecodelens.transform.TransformContext;

import java.util.Set;

/**
 * Renames classes, methods and fields whose identifiers are not valid Java source names.
 *
 * <p>Decompilers produce unreadable / uncompilable output when a jar uses identifiers that
 * are reserved Java keywords, or contain control characters / confusable unicode. This pass
 * replaces such names with synthetic but valid stand-ins ({@code C_<idx>}, {@code m_<idx>},
 * {@code f_<idx>}), then applies the resulting rename through {@link MappingApplier}.</p>
 */
public final class IllegalNameMapping implements JarLevelTransformation {

    @Override public String id() { return "illegal-name-mapping"; }
    @Override public String name() { return "Illegal Name Mapping"; }
    @Override public String description() {
        return "Replace non-Java / keyword / control-char identifiers with valid stand-ins.";
    }

    // Hot subset of reserved words that obfuscators pick on purpose because they break javac
    // but JVM itself accepts them as identifiers.
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false"
    );

    @Override
    public LoadedJar apply(LoadedJar jar, TransformContext ctx) {
        MappingModel.Builder builder = MappingModel.builder(MappingFormat.PROGUARD);
        ClassAnalyzer analyzer = new ClassAnalyzer();
        int classCounter = 0;
        int methodCounter = 0;
        int fieldCounter = 0;

        for (ClassEntry entry : jar.classes()) {
            if (!isLegalJavaIdentifier(entry.simpleName()) || JAVA_KEYWORDS.contains(entry.simpleName())) {
                String newSimple = "C_" + classCounter++;
                String newInternal = entry.packageName().isEmpty()
                        ? newSimple
                        : entry.packageName().replace('.', '/') + "/" + newSimple;
                builder.mapClass(entry.internalName(), newInternal);
                ctx.inc("classes-renamed");
            }

            for (MethodEntry me : analyzer.methods(entry.bytes())) {
                // Skip <init>, <clinit> — always legal.
                if (me.name().startsWith("<")) continue;
                if (!isLegalJavaIdentifier(me.name()) || JAVA_KEYWORDS.contains(me.name())) {
                    builder.mapMethod(entry.internalName(), me.name(), me.descriptor(), "m_" + methodCounter++);
                    ctx.inc("methods-renamed");
                }
            }

            for (FieldEntry fe : analyzer.fields(entry.bytes())) {
                if (!isLegalJavaIdentifier(fe.name()) || JAVA_KEYWORDS.contains(fe.name())) {
                    builder.mapField(entry.internalName(), fe.name(), fe.descriptor(), "f_" + fieldCounter++);
                    ctx.inc("fields-renamed");
                }
            }
        }

        MappingModel model = builder.build();
        if (model.classCount() == 0 && model.methodCount() == 0 && model.fieldCount() == 0) {
            return jar; // nothing to do
        }
        return new MappingApplier().apply(jar, model);
    }

    static boolean isLegalJavaIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isJavaIdentifierPart(c)) return false;
            // Also reject control characters that are technically "java identifier part"
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }
}
