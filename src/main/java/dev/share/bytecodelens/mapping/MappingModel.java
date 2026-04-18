package dev.share.bytecodelens.mapping;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory mapping from obfuscated names to deobfuscated names.
 *
 * <p>All keys/values use JVM internal form: classes are {@code com/foo/Bar}, field keys are
 * {@code owner/name:desc}, method keys are {@code owner/name desc}. This lets the same
 * lookup functions work regardless of the source format.</p>
 *
 * <p>Immutable. Use {@link Builder} to construct.</p>
 */
public final class MappingModel {

    private final MappingFormat sourceFormat;
    private final Map<String, String> classMap;        // "com/foo/Bar" -> "com/example/Renamed"
    private final Map<String, String> fieldMap;        // "com/foo/Bar.field:I" -> "renamed"
    private final Map<String, String> methodMap;       // "com/foo/Bar.method(II)V" -> "renamed"

    private MappingModel(MappingFormat sourceFormat,
                         Map<String, String> classMap,
                         Map<String, String> fieldMap,
                         Map<String, String> methodMap) {
        this.sourceFormat = sourceFormat;
        this.classMap = Collections.unmodifiableMap(classMap);
        this.fieldMap = Collections.unmodifiableMap(fieldMap);
        this.methodMap = Collections.unmodifiableMap(methodMap);
    }

    public MappingFormat sourceFormat() { return sourceFormat; }
    public Map<String, String> classMap() { return classMap; }
    public Map<String, String> fieldMap() { return fieldMap; }
    public Map<String, String> methodMap() { return methodMap; }

    public int classCount() { return classMap.size(); }
    public int fieldCount() { return fieldMap.size(); }
    public int methodCount() { return methodMap.size(); }

    public String mapClass(String internalName) {
        return classMap.getOrDefault(internalName, internalName);
    }

    public static String fieldKey(String owner, String name, String desc) {
        return owner + "." + name + ":" + desc;
    }

    public static String methodKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    public static Builder builder(MappingFormat source) {
        return new Builder(source);
    }

    public static final class Builder {
        private final MappingFormat source;
        private final Map<String, String> classMap = new HashMap<>();
        private final Map<String, String> fieldMap = new HashMap<>();
        private final Map<String, String> methodMap = new HashMap<>();

        private Builder(MappingFormat source) { this.source = source; }

        public Builder mapClass(String fromInternal, String toInternal) {
            classMap.put(fromInternal, toInternal);
            return this;
        }

        public Builder mapField(String ownerInternal, String fromName, String desc, String toName) {
            fieldMap.put(fieldKey(ownerInternal, fromName, desc), toName);
            return this;
        }

        public Builder mapMethod(String ownerInternal, String fromName, String desc, String toName) {
            methodMap.put(methodKey(ownerInternal, fromName, desc), toName);
            return this;
        }

        public MappingModel build() {
            return new MappingModel(source, classMap, fieldMap, methodMap);
        }
    }
}
