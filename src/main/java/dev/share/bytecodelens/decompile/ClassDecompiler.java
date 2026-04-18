package dev.share.bytecodelens.decompile;

public interface ClassDecompiler {

    String name();

    String decompile(String internalName, byte[] classBytes);
}
