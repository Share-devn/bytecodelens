package dev.share.bytecodelens.decompile;

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

public final class VineflowerDecompiler implements ClassDecompiler {

    @Override
    public String name() {
        return "Vineflower";
    }

    @Override
    public String decompile(String internalName, byte[] classBytes) {
        StringBuilder out = new StringBuilder();
        try {
            Map<String, Object> options = new HashMap<>();
            options.put("log", "WARN");
            options.put("ren", "0");
            options.put("dgs", "1");
            options.put("rsy", "1");
            options.put("rbr", "1");
            options.put("hes", "1");
            options.put("hdc", "1");
            options.put("fdi", "0");

            IResultSaver nopSaver = new NopResultSaver();
            BaseDecompiler decompiler = new BaseDecompiler(nopSaver, options, new VineflowerLogger());
            decompiler.addSource(new SingleClassSource(internalName, classBytes, out));
            decompiler.decompileContext();

            String result = out.toString();
            return result.isEmpty() ? "// Vineflower produced no output" : result;
        } catch (Throwable ex) {
            String msg = ex.getMessage();
            if (msg == null) msg = ex.getClass().getSimpleName();
            return "// Vineflower decompilation failed: " + msg;
        }
    }

    /** IResultSaver that ignores everything — Vineflower requires one but we use an IOutputSink instead. */
    private static final class NopResultSaver implements IResultSaver {
        @Override public void saveFolder(String path) {}
        @Override public void copyFile(String source, String path, String entryName) {}
        @Override public void saveClassFile(String path, String qualifiedName, String entryName,
                                            String content, int[] mapping) {}
        @Override public void createArchive(String path, String archiveName, Manifest manifest) {}
        @Override public void saveDirEntry(String path, String archiveName, String entryName) {}
        @Override public void copyEntry(String source, String path, String archiveName, String entry) {}
        @Override public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                             String entryName, String content) {}
        @Override public void closeArchive(String path, String archiveName) {}
    }

    private static final class VineflowerLogger extends IFernflowerLogger {
        @Override public void writeMessage(String message, Severity severity) {}
        @Override public void writeMessage(String message, Severity severity, Throwable t) {}
    }

    /** Feeds a single class into Vineflower and collects the decompiled text via its IOutputSink. */
    private static final class SingleClassSource implements IContextSource {
        private final String internalName;
        private final byte[] bytes;
        private final StringBuilder sink;

        SingleClassSource(String internalName, byte[] bytes, StringBuilder sink) {
            this.internalName = internalName;
            this.bytes = bytes;
            this.sink = sink;
        }

        @Override
        public String getName() {
            return internalName;
        }

        @Override
        public Entries getEntries() {
            Entry entry = Entry.atBase(internalName);
            return new Entries(List.of(entry), List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resource) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override public void begin() {}
                @Override public void acceptClass(String qualifiedName, String fileName,
                                                  String content, int[] mapping) {
                    if (content != null) sink.append(content);
                }
                @Override public void acceptDirectory(String directory) {}
                @Override public void acceptOther(String path) {}
                @Override public void close() throws IOException {}
            };
        }
    }
}
