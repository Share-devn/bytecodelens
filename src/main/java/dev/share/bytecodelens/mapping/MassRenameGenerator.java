package dev.share.bytecodelens.mapping;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link MappingModel} by running a user-supplied regex over every class,
 * method and field name in a jar. Only names that match the pattern are rewritten;
 * everything else is passed through untouched by {@link MappingApplier}.
 *
 * <p>Typical use: stripping obfuscator suffixes ("{@code (.+)_impl$}" → "{@code $1}"),
 * bulk-replacing a vendor prefix, or giving numeric-name classes a friendlier
 * pattern ("{@code C_(\d+)}" → "{@code Class_$1}").</p>
 */
public final class MassRenameGenerator {

    public record Rules(Pattern pattern, String replacement,
                        boolean renameClasses, boolean renameMethods, boolean renameFields) {}

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    /** Build a MappingModel for every name in {@code jar} that {@code rules} would rename. */
    public MappingModel generate(LoadedJar jar, Rules rules) {
        MappingModel.Builder b = MappingModel.builder(MappingFormat.PROGUARD);

        for (ClassEntry entry : jar.classes()) {
            // Class itself — always treat the simple name; skip package-qualified form.
            if (rules.renameClasses()) {
                String simple = entry.simpleName();
                String renamed = apply(rules.pattern(), rules.replacement(), simple);
                if (renamed != null && !renamed.equals(simple)) {
                    String newInternal = entry.packageName().isEmpty()
                            ? renamed
                            : entry.packageName().replace('.', '/') + "/" + renamed;
                    b.mapClass(entry.internalName(), newInternal);
                }
            }
            if (rules.renameMethods()) {
                for (var m : analyzer.methods(entry.bytes())) {
                    if (m.name().startsWith("<")) continue; // <init>, <clinit>
                    String renamed = apply(rules.pattern(), rules.replacement(), m.name());
                    if (renamed != null && !renamed.equals(m.name())) {
                        b.mapMethod(entry.internalName(), m.name(), m.descriptor(), renamed);
                    }
                }
            }
            if (rules.renameFields()) {
                for (var f : analyzer.fields(entry.bytes())) {
                    String renamed = apply(rules.pattern(), rules.replacement(), f.name());
                    if (renamed != null && !renamed.equals(f.name())) {
                        b.mapField(entry.internalName(), f.name(), f.descriptor(), renamed);
                    }
                }
            }
        }
        return b.build();
    }

    /** Null if pattern doesn't match (caller treats as "no rename"). */
    private static String apply(Pattern p, String replacement, String name) {
        Matcher m = p.matcher(name);
        if (!m.find()) return null;
        try {
            return m.replaceAll(replacement);
        } catch (Exception ex) {
            // Bad replacement (e.g. $9 with only 3 groups) — caller skips this one.
            return null;
        }
    }
}
