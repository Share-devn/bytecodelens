package dev.share.bytecodelens.service;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class BytecodePrinter {

    public String print(byte[] bytes) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            ClassReader reader = new ClassReader(bytes);
            TraceClassVisitor visitor = new TraceClassVisitor(null, new Textifier(), pw);
            reader.accept(visitor, ClassReader.SKIP_FRAMES);
        }
        return sw.toString();
    }
}
