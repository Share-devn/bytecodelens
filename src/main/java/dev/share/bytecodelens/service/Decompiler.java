package dev.share.bytecodelens.service;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Decompiler {

    public String decompile(String internalName, byte[] classBytes) {
        String expectedPath = internalName + ".class";
        StringBuilder sink = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        ClassFileSource source = new ClassFileSource() {
            @Override
            public void informAnalysisRelativePathDetail(String usePath, String specPath) {
            }

            @Override
            public Collection<String> addJar(String jarPath) {
                return Collections.emptyList();
            }

            @Override
            public String getPossiblyRenamedPath(String path) {
                return path;
            }

            @Override
            public Pair<byte[], String> getClassFileContent(String path) throws java.io.IOException {
                if (path == null) throw new java.io.IOException("null path");
                if (path.equals(expectedPath) || path.equals(internalName)) {
                    return Pair.make(classBytes, path);
                }
                throw new java.io.IOException("Class not available in this standalone view: " + path);
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");
        options.put("silent", "true");
        options.put("recover", "true");
        options.put("recovertypeclash", "true");
        options.put("recovertypehints", "true");
        options.put("decodeenumswitch", "true");
        options.put("sugarenums", "true");
        options.put("decodestringswitch", "true");
        options.put("hidebridgemethods", "true");
        options.put("hideutf", "true");
        options.put("caseinsensitivefs", "true");
        // Suppress the "Decompiled with CFR" banner and "Could not load..." preamble.
        options.put("comments", "false");
        options.put("clobber", "true");
        options.put("usenametable", "true");

        CfrDriver driver = new CfrDriver.Builder()
                .withClassFileSource(source)
                .withOptions(options)
                .withOutputSink(new org.benf.cfr.reader.api.OutputSinkFactory() {
                    @Override
                    public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                        return List.of(SinkClass.STRING);
                    }

                    @Override
                    public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                        return switch (sinkType) {
                            case JAVA -> value -> sink.append(value);
                            case EXCEPTION -> value -> errors.append(value).append(System.lineSeparator());
                            default -> value -> {
                            };
                        };
                    }
                })
                .build();

        try {
            driver.analyse(List.of(expectedPath));
        } catch (Throwable ex) {
            String msg = ex.getMessage();
            if (msg == null) msg = ex.getClass().getSimpleName();
            return "// Decompilation failed: " + msg
                    + (errors.length() > 0 ? System.lineSeparator() + "// " + errors : "");
        }

        String result = stripLeadingBlockComment(sink.toString());
        if (result.isEmpty()) {
            if (errors.length() > 0) {
                return "// Decompiler produced no output." + System.lineSeparator()
                        + "// Details: " + errors;
            }
            return "// No output from decompiler";
        }
        return result;
    }

    /**
     * Removes a leading block comment (e.g. CFR's "Decompiled with CFR"/"Could not load..." banner)
     * so users don't see the noise before the actual source. Only the first block comment is stripped,
     * and only if it appears before any real code.
     */
    private static String stripLeadingBlockComment(String s) {
        if (s == null || s.isEmpty()) return s;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i + 1 >= s.length() || s.charAt(i) != '/' || s.charAt(i + 1) != '*') return s;
        int end = s.indexOf("*/", i + 2);
        if (end < 0) return s;
        int after = end + 2;
        while (after < s.length() && (s.charAt(after) == '\n' || s.charAt(after) == '\r')) after++;
        return s.substring(after);
    }
}
